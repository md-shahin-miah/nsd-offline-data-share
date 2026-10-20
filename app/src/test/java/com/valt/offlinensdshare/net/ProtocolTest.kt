package com.valt.offlinensdshare.net

import com.valt.offlinensdshare.data.Peer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {

    private fun roundTrip(message: Message) = Protocol.decode(Protocol.encode(message))

    @Test
    fun everyMessageTypeSurvivesRoundTrip() {
        val messages = listOf(
            Message.Hello("id-1", "Alice"),
            Message.Welcome("hub", "Living room", listOf(Peer("a", "Alice"), Peer("b", "Bob"))),
            Message.Welcome("hub", "Empty hub", emptyList()),
            Message.PeerJoined(Peer("c", "Carol")),
            Message.PeerLeft("c"),
            Message.Chat("m1", "a", "Alice", "b", "Hi Bob", 1_700_000_000_000),
            Message.Relayed("m1"),
            Message.Delivered("m1", by = "b", to = "a"),
            Message.Error("m1", "Recipient is offline"),
            Message.Error(null, "Id already in use"),
            Message.Ping,
            Message.Pong,
        )
        messages.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun multilineAndUnicodeTextIsEncodedOnOneLine() {
        val chat = Message.Chat("m2", "a", "Ålice 😀", "ALL", "line one\nline two\r\n\"quoted\" ✓", 1L)
        val encoded = Protocol.encode(chat)
        assertFalse(encoded.contains('\n'))
        assertEquals(chat, Protocol.decode(encoded))
    }

    @Test
    fun malformedInputIsIgnored() {
        assertNull(Protocol.decode("not json"))
        assertNull(Protocol.decode("""{"type":"SOMETHING_NEW"}"""))
        assertNull(Protocol.decode("""{"type":"CHAT","msgId":"x"}"""))
        assertNull(Protocol.decode(""))
    }
}
