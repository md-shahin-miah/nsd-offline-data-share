package com.valt.offlinensdshare.net

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.Socket

/**
 * Line-based message channel over a TCP socket, shared by the hub and the client.
 *
 * All methods block on network I/O, so call them from [kotlinx.coroutines.Dispatchers.IO].
 * Heartbeat traffic ([Message.Ping]/[Message.Pong]) is handled here and never reaches callers.
 */
class Connection(private val socket: Socket) {

    private val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
    private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
    private val writeLock = Any()

    @Volatile
    private var lastReceivedAt = System.currentTimeMillis()

    val isOpen: Boolean get() = !socket.isClosed

    /** Returns false (and closes the connection) if the message could not be written. */
    fun send(message: Message): Boolean = try {
        synchronized(writeLock) {
            writer.write(Protocol.encode(message))
            writer.write("\n")
            writer.flush()
        }
        true
    } catch (e: IOException) {
        close()
        false
    }

    /**
     * Reads the next application message, waiting at most [timeoutMs] (0 = forever).
     * Returns null once the connection is closed or the timeout expires.
     */
    fun readMessage(timeoutMs: Int = 0): Message? = try {
        socket.soTimeout = timeoutMs
        var result: Message? = null
        while (result == null) {
            val line = reader.readLine() ?: break
            lastReceivedAt = System.currentTimeMillis()
            when (val message = Protocol.decode(line)) {
                null, Message.Pong -> Unit
                Message.Ping -> send(Message.Pong)
                else -> result = message
            }
        }
        result
    } catch (e: IOException) {
        null
    }

    /** Delivers messages to [onMessage] until the connection closes, then closes the socket. */
    fun readMessages(onMessage: (Message) -> Unit) {
        try {
            while (true) {
                val message = readMessage() ?: break
                onMessage(message)
            }
        } finally {
            close()
        }
    }

    /**
     * Pings the other side every [intervalMs] and closes the connection when nothing has been
     * received for [timeoutMs]. Closing the socket also unblocks a pending [readMessages].
     */
    suspend fun runHeartbeat(intervalMs: Long = 5_000, timeoutMs: Long = 15_000) {
        while (isOpen) {
            delay(intervalMs)
            if (System.currentTimeMillis() - lastReceivedAt > timeoutMs) {
                close()
                break
            }
            send(Message.Ping)
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
