package com.valt.offlinensdshare.net

import com.valt.offlinensdshare.data.Peer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Messages exchanged between hub and clients. Each one travels as a single line of JSON
 * terminated by '\n' over the TCP socket.
 */
sealed interface Message {
    /** First message a client sends after connecting. */
    data class Hello(val id: String, val name: String) : Message

    /** Hub's answer to [Hello]: who the hub is and who is already connected. */
    data class Welcome(val hubId: String, val hubName: String, val peers: List<Peer>) : Message

    data class PeerJoined(val peer: Peer) : Message

    data class PeerLeft(val id: String) : Message

    /** [to] is a peer id, the hub id, or the broadcast id. */
    data class Chat(
        val msgId: String,
        val from: String,
        val fromName: String,
        val to: String,
        val text: String,
        val timestamp: Long,
    ) : Message

    /** Hub → sender: the hub forwarded the [Chat] with [msgId] to its recipient. */
    data class Relayed(val msgId: String) : Message

    /**
     * Delivery receipt. The recipient [by] of a [Chat] sends it back towards the original sender [to];
     * the hub forwards it (or consumes it when [to] is the hub).
     */
    data class Delivered(val msgId: String, val by: String, val to: String) : Message

    /** Sent by the hub when it could not handle a request; [msgId] refers to the failed [Chat]. */
    data class Error(val msgId: String?, val reason: String) : Message

    data object Ping : Message

    data object Pong : Message
}

object Protocol {

    fun encode(message: Message): String {
        val json = JSONObject()
        when (message) {
            is Message.Hello -> json.put("type", "HELLO")
                .put("id", message.id)
                .put("name", message.name)

            is Message.Welcome -> json.put("type", "WELCOME")
                .put("hubId", message.hubId)
                .put("hubName", message.hubName)
                .put("peers", JSONArray().apply { message.peers.forEach { put(peerToJson(it)) } })

            is Message.PeerJoined -> json.put("type", "PEER_JOINED")
                .put("peer", peerToJson(message.peer))

            is Message.PeerLeft -> json.put("type", "PEER_LEFT")
                .put("id", message.id)

            is Message.Chat -> json.put("type", "CHAT")
                .put("msgId", message.msgId)
                .put("from", message.from)
                .put("fromName", message.fromName)
                .put("to", message.to)
                .put("text", message.text)
                .put("ts", message.timestamp)

            is Message.Relayed -> json.put("type", "RELAYED")
                .put("msgId", message.msgId)

            is Message.Delivered -> json.put("type", "DELIVERED")
                .put("msgId", message.msgId)
                .put("by", message.by)
                .put("to", message.to)

            is Message.Error -> json.put("type", "ERROR")
                .put("msgId", message.msgId ?: JSONObject.NULL)
                .put("reason", message.reason)

            Message.Ping -> json.put("type", "PING")
            Message.Pong -> json.put("type", "PONG")
        }
        // JSONObject.toString() escapes newlines, so the result is always a single line.
        return json.toString()
    }

    /** Returns null for malformed or unknown lines so a bad peer can't crash the reader. */
    fun decode(line: String): Message? = try {
        val json = JSONObject(line)
        when (json.getString("type")) {
            "HELLO" -> Message.Hello(json.getString("id"), json.getString("name"))
            "WELCOME" -> {
                val array = json.getJSONArray("peers")
                Message.Welcome(
                    hubId = json.getString("hubId"),
                    hubName = json.getString("hubName"),
                    peers = List(array.length()) { peerFromJson(array.getJSONObject(it)) },
                )
            }
            "PEER_JOINED" -> Message.PeerJoined(peerFromJson(json.getJSONObject("peer")))
            "PEER_LEFT" -> Message.PeerLeft(json.getString("id"))
            "CHAT" -> Message.Chat(
                msgId = json.getString("msgId"),
                from = json.getString("from"),
                fromName = json.getString("fromName"),
                to = json.getString("to"),
                text = json.getString("text"),
                timestamp = json.getLong("ts"),
            )
            "RELAYED" -> Message.Relayed(json.getString("msgId"))
            "DELIVERED" -> Message.Delivered(json.getString("msgId"), json.getString("by"), json.getString("to"))
            "ERROR" -> Message.Error(
                msgId = if (json.isNull("msgId")) null else json.getString("msgId"),
                reason = json.getString("reason"),
            )
            "PING" -> Message.Ping
            "PONG" -> Message.Pong
            else -> null
        }
    } catch (e: JSONException) {
        null
    }

    private fun peerToJson(peer: Peer) = JSONObject().put("id", peer.id).put("name", peer.name)

    private fun peerFromJson(json: JSONObject) = Peer(json.getString("id"), json.getString("name"))
}
