package com.valt.offlinensdshare.data

/** Fixed id of the hub. Clients use a random UUID. */
const val HUB_ID = "hub"

/** Recipient id meaning "every connected device except the sender". */
const val BROADCAST_ID = "ALL"

enum class Role { HUB, CLIENT }

data class Peer(
    val id: String,
    val name: String,
    val isHub: Boolean = id == HUB_ID,
)

enum class DeliveryStatus {
    /** Outgoing, not yet confirmed by the hub. */
    SENDING,
    /** Outgoing, accepted by the hub (or written to the recipient by the hub); recipient hasn't confirmed. */
    SENT,
    /** Outgoing, every recipient's device confirmed it received the message. */
    DELIVERED,
    /** Outgoing, could not reach the recipient. */
    FAILED,
    /** Incoming message. */
    RECEIVED,
}

data class ChatMessage(
    val id: String,
    /** Peer id of the conversation this message belongs to, or [BROADCAST_ID]. */
    val conversationId: String,
    val fromId: String,
    val fromName: String,
    val text: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val status: DeliveryStatus,
    /** How many devices should confirm delivery (1 for a direct message, peer count for a broadcast). */
    val recipientCount: Int = 1,
    /** Peer ids that confirmed delivery of this outgoing message. */
    val deliveredTo: Set<String> = emptySet(),
)
