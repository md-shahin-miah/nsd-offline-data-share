package com.valt.offlinensdshare.net

import com.valt.offlinensdshare.data.BROADCAST_ID
import com.valt.offlinensdshare.data.ChatRepository
import com.valt.offlinensdshare.data.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

sealed interface ClientStatus {
    data object Idle : ClientStatus
    data class Connecting(val hubName: String) : ClientStatus
    data class Connected(val hubName: String) : ClientStatus
    /** [reason] is null when the user disconnected on purpose. */
    data class Disconnected(val reason: String?) : ClientStatus
}

/** A client's single TCP connection to the hub. Every message, even client-to-client, goes through it. */
class ClientConnection(
    private val myId: String,
    private val myName: String,
    private val repository: ChatRepository,
    private val scope: CoroutineScope,
) {

    private val _status = MutableStateFlow<ClientStatus>(ClientStatus.Idle)
    val status: StateFlow<ClientStatus> = _status.asStateFlow()

    @Volatile
    private var connection: Connection? = null
    private var readJob: Job? = null

    @Volatile
    private var isShutdown = false

    fun connecting(hubName: String) {
        _status.value = ClientStatus.Connecting(hubName)
    }

    fun fail(reason: String) {
        _status.value = ClientStatus.Disconnected(reason)
    }

    suspend fun connect(host: InetAddress, port: Int, hubName: String) {
        disconnect()
        _status.value = ClientStatus.Connecting(hubName)
        val result = withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val conn = Connection(socket)
                conn.send(Message.Hello(myId, myName))
                when (val reply = conn.readMessage(timeoutMs = WELCOME_TIMEOUT_MS)) {
                    is Message.Welcome -> Result.success(conn to reply)
                    is Message.Error -> {
                        conn.close()
                        Result.failure(IOException(reply.reason))
                    }
                    else -> {
                        conn.close()
                        Result.failure(IOException("Hub did not answer"))
                    }
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

        val (conn, welcome) = result.getOrElse {
            _status.value = ClientStatus.Disconnected("Could not connect: ${it.message ?: "unknown error"}")
            return
        }
        if (isShutdown) {
            // The session ended while we were connecting.
            conn.close()
            return
        }
        connection = conn
        repository.setPeers(listOf(Peer(welcome.hubId, welcome.hubName, isHub = true)) + welcome.peers)
        _status.value = ClientStatus.Connected(welcome.hubName)

        readJob = scope.launch(Dispatchers.IO) {
            val heartbeat = launch { conn.runHeartbeat() }
            conn.readMessages { handle(conn, it) }
            heartbeat.cancel()
            // Only report a lost connection if this is still the active one (not a user disconnect).
            if (connection === conn) {
                connection = null
                repository.setPeers(emptyList())
                repository.failPending()
                _status.value = ClientStatus.Disconnected("Connection to hub lost")
            }
        }
    }

    /** Blocking; call from an IO dispatcher. */
    fun send(to: String, text: String) {
        val chat = Message.Chat(
            msgId = UUID.randomUUID().toString(),
            from = myId,
            fromName = myName,
            to = to,
            text = text,
            timestamp = System.currentTimeMillis(),
        )
        // A broadcast should be confirmed by every peer, the hub included.
        val recipientCount = if (to == BROADCAST_ID) repository.peers.value.size else 1
        repository.addOutgoing(chat, recipientCount)
        // Stays SENDING until the hub answers with Relayed/Delivered (or Error).
        if (connection?.send(chat) != true) repository.markFailed(chat.msgId)
    }

    /** Disconnects and prevents an in-flight [connect] from completing. */
    fun shutdown() {
        isShutdown = true
        disconnect()
    }

    fun disconnect() {
        val conn = connection ?: return
        connection = null
        conn.close()
        readJob?.cancel()
        readJob = null
        repository.setPeers(emptyList())
        repository.failPending()
        _status.value = ClientStatus.Disconnected(null)
    }

    private fun handle(conn: Connection, message: Message) {
        when (message) {
            is Message.Chat -> {
                repository.addIncoming(message)
                // Tell the original sender (via the hub) that this device has the message.
                conn.send(Message.Delivered(message.msgId, by = myId, to = message.from))
            }
            is Message.Relayed -> repository.markSent(message.msgId)
            is Message.Delivered -> repository.markDelivered(message.msgId, message.by)
            is Message.PeerJoined -> repository.addPeer(message.peer)
            is Message.PeerLeft -> repository.removePeer(message.id)
            is Message.Error -> message.msgId?.let(repository::markFailed)
            else -> Unit
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val WELCOME_TIMEOUT_MS = 10_000
    }
}
