package com.valt.offlinensdshare.net

import com.valt.offlinensdshare.data.BROADCAST_ID
import com.valt.offlinensdshare.data.ChatRepository
import com.valt.offlinensdshare.data.HUB_ID
import com.valt.offlinensdshare.data.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * TCP server run by the hub. It accepts every client automatically and relays chat messages,
 * so clients only ever need the hub's address to reach each other.
 */
class HubServer(
    private val hubName: String,
    private val repository: ChatRepository,
    private val scope: CoroutineScope,
) {

    private class ClientSession(val peer: Peer, val connection: Connection)

    private val clients = ConcurrentHashMap<String, ClientSession>()
    private val membershipLock = Any()
    private var stopped = false // guarded by membershipLock
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** Opens the server on a free port and returns that port. */
    suspend fun start(): Int = withContext(Dispatchers.IO) {
        val server = ServerSocket(0)
        serverSocket = server
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
        server.localPort
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        synchronized(membershipLock) {
            stopped = true
            clients.values.forEach { it.connection.close() }
            clients.clear()
        }
    }

    /** Sends a message from the hub itself to one client or to [BROADCAST_ID]. */
    fun send(to: String, text: String) {
        val chat = Message.Chat(
            msgId = UUID.randomUUID().toString(),
            from = HUB_ID,
            fromName = hubName,
            to = to,
            text = text,
            timestamp = System.currentTimeMillis(),
        )
        val recipients = if (to == BROADCAST_ID) clients.values.toList() else listOfNotNull(clients[to])
        // Record before writing so a fast delivery receipt always finds the message.
        repository.addOutgoing(chat, recipientCount = recipients.size)
        val written = recipients.map { it.connection.send(chat) }.any { it }
        // SENT = written to the recipient's socket; DELIVERED follows when its receipt arrives.
        if (written) repository.markSent(chat.msgId) else repository.markFailed(chat.msgId)
    }

    private suspend fun acceptLoop(server: ServerSocket) = withContext(Dispatchers.IO) {
        while (isActive) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break // server socket closed by stop()
            }
            // Auto-accept: every incoming connection gets its own session without asking the user.
            launch { handleClient(socket) }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val connection = Connection(socket)
        val hello = connection.readMessage(timeoutMs = HELLO_TIMEOUT_MS) as? Message.Hello
        if (hello == null) {
            connection.close()
            return@withContext
        }
        val peer = Peer(hello.id, hello.name)
        val session = ClientSession(peer, connection)
        // Joins and leaves are serialized so every client sees a consistent peer list, and the
        // newcomer gets its Welcome before any PeerJoined/PeerLeft about others.
        val joined = synchronized(membershipLock) {
            if (stopped || hello.id == HUB_ID || hello.id == BROADCAST_ID || clients.containsKey(hello.id)) {
                false
            } else {
                connection.send(Message.Welcome(HUB_ID, hubName, clients.values.map { it.peer }))
                clients[peer.id] = session
                broadcast(Message.PeerJoined(peer), except = peer.id)
                true
            }
        }
        if (!joined) {
            connection.send(Message.Error(null, "Id already in use"))
            connection.close()
            return@withContext
        }
        repository.addPeer(peer)

        val heartbeat = launch { connection.runHeartbeat() }
        try {
            connection.readMessages { message ->
                // Never trust sender fields from the wire; the socket identifies the client.
                when (message) {
                    is Message.Chat -> route(message.copy(from = peer.id, fromName = peer.name))
                    is Message.Delivered -> routeReceipt(message.copy(by = peer.id))
                    else -> Unit
                }
            }
        } finally {
            heartbeat.cancel()
            val left = synchronized(membershipLock) {
                clients.remove(peer.id, session).also { removed ->
                    if (removed) broadcast(Message.PeerLeft(peer.id), except = null)
                }
            }
            if (left) repository.removePeer(peer.id)
        }
    }

    private fun route(chat: Message.Chat) {
        val sender = clients[chat.from]?.connection
        when (chat.to) {
            HUB_ID -> {
                repository.addIncoming(chat)
                sender?.send(Message.Delivered(chat.msgId, by = HUB_ID, to = chat.from))
            }
            BROADCAST_ID -> {
                repository.addIncoming(chat)
                sender?.send(Message.Delivered(chat.msgId, by = HUB_ID, to = chat.from))
                // Each other client sends its own receipt, which routeReceipt forwards to the sender.
                broadcast(chat, except = chat.from)
            }
            else -> {
                val target = clients[chat.to]
                if (target != null && target.connection.send(chat)) {
                    sender?.send(Message.Relayed(chat.msgId))
                } else {
                    sender?.send(Message.Error(chat.msgId, "Recipient is offline"))
                }
            }
        }
    }

    private fun routeReceipt(receipt: Message.Delivered) {
        if (receipt.to == HUB_ID) {
            repository.markDelivered(receipt.msgId, receipt.by)
        } else {
            // If the original sender has left there is nobody to tell.
            clients[receipt.to]?.connection?.send(receipt)
        }
    }

    private fun broadcast(message: Message, except: String?) {
        clients.forEach { (id, session) -> if (id != except) session.connection.send(message) }
    }

    private companion object {
        const val HELLO_TIMEOUT_MS = 10_000
    }
}
