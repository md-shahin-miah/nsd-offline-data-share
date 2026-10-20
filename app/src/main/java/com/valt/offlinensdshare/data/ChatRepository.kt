package com.valt.offlinensdshare.data

import com.valt.offlinensdshare.net.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory state of the current session: who is reachable and every message sent or received.
 * Thread-safe; network threads write to it and Compose reads the flows.
 */
class ChatRepository {

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    /** Everyone this device can message (excluding itself). */
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Unread message count per conversation id. */
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    @Volatile
    var myId: String = ""
        private set

    @Volatile
    private var openConversation: String? = null

    fun reset(myId: String) {
        this.myId = myId
        openConversation = null
        _peers.value = emptyList()
        _messages.value = emptyList()
        _unread.value = emptyMap()
    }

    fun setPeers(peers: List<Peer>) {
        _peers.value = peers.filter { it.id != myId }
    }

    fun addPeer(peer: Peer) {
        if (peer.id == myId) return
        _peers.update { list -> list.filterNot { it.id == peer.id } + peer }
    }

    fun removePeer(id: String) {
        _peers.update { list -> list.filterNot { it.id == id } }
    }

    fun addOutgoing(chat: Message.Chat, recipientCount: Int = 1) =
        add(chat, outgoing = true, DeliveryStatus.SENDING, recipientCount)

    fun addIncoming(chat: Message.Chat) {
        add(chat, outgoing = false, DeliveryStatus.RECEIVED, recipientCount = 1)
        val conversation = conversationOf(chat)
        if (conversation != openConversation) {
            _unread.update { it + (conversation to (it[conversation] ?: 0) + 1) }
        }
    }

    /** The hub has the message. Receipts can arrive out of order, so this never downgrades DELIVERED. */
    fun markSent(msgId: String) = updateOutgoing(msgId) {
        if (it.status == DeliveryStatus.SENDING) it.copy(status = DeliveryStatus.SENT) else it
    }

    /** Recipient [by] confirmed it received the message. */
    fun markDelivered(msgId: String, by: String) = updateOutgoing(msgId) {
        val deliveredTo = it.deliveredTo + by
        val status = if (deliveredTo.size >= it.recipientCount) DeliveryStatus.DELIVERED else DeliveryStatus.SENT
        it.copy(deliveredTo = deliveredTo, status = status)
    }

    fun markFailed(msgId: String) = updateOutgoing(msgId) {
        if (it.status == DeliveryStatus.DELIVERED) it else it.copy(status = DeliveryStatus.FAILED)
    }

    /** Connection lost: anything the hub never confirmed can no longer be confirmed. */
    fun failPending() {
        _messages.update { list ->
            list.map { if (it.status == DeliveryStatus.SENDING) it.copy(status = DeliveryStatus.FAILED) else it }
        }
    }

    private inline fun updateOutgoing(msgId: String, crossinline transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.id == msgId && it.outgoing) transform(it) else it } }
    }

    fun openConversation(id: String) {
        openConversation = id
        _unread.update { it - id }
    }

    fun closeConversation(id: String) {
        if (openConversation == id) openConversation = null
    }

    private fun add(chat: Message.Chat, outgoing: Boolean, status: DeliveryStatus, recipientCount: Int) {
        val message = ChatMessage(
            id = chat.msgId,
            conversationId = conversationOf(chat),
            fromId = chat.from,
            fromName = chat.fromName,
            text = chat.text,
            timestamp = chat.timestamp,
            outgoing = outgoing,
            status = status,
            recipientCount = recipientCount,
        )
        _messages.update { it + message }
    }

    private fun conversationOf(chat: Message.Chat): String = when {
        chat.to == BROADCAST_ID -> BROADCAST_ID
        chat.from == myId -> chat.to
        else -> chat.from
    }
}
