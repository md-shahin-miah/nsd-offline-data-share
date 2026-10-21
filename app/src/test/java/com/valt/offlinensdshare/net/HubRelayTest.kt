package com.valt.offlinensdshare.net

import com.valt.offlinensdshare.data.BROADCAST_ID
import com.valt.offlinensdshare.data.ChatRepository
import com.valt.offlinensdshare.data.DeliveryStatus
import com.valt.offlinensdshare.data.HUB_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

/** Runs a real hub and clients over loopback sockets (NSD is only needed to find the port). */
class HubRelayTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hubRepo = ChatRepository().apply { reset(HUB_ID) }
    private val hub = HubServer("Hub", hubRepo, scope)

    @After
    fun tearDown() {
        hub.stop()
        scope.cancel()
    }

    private suspend fun connectClient(id: String, name: String, port: Int): Pair<ClientConnection, ChatRepository> {
        val repo = ChatRepository().apply { reset(id) }
        val client = ClientConnection(id, name, repo, scope)
        client.connect(InetAddress.getLoopbackAddress(), port, "Hub")
        assertEquals(ClientStatus.Connected("Hub"), client.status.value)
        return client to repo
    }

    private suspend fun awaitUntil(description: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $description", e)
        }
    }

    private fun ChatRepository.has(text: String, conversation: String) =
        messages.value.any { it.text == text && it.conversationId == conversation }

    @Test
    fun peersSeeEachOtherAndTheHub() = runBlocking {
        val port = hub.start()
        val (_, aliceRepo) = connectClient("a", "Alice", port)
        val (_, bobRepo) = connectClient("b", "Bob", port)

        awaitUntil("everyone sees two peers") {
            hubRepo.peers.value.size == 2 && aliceRepo.peers.value.size == 2 && bobRepo.peers.value.size == 2
        }
        assertEquals(setOf("a", "b"), hubRepo.peers.value.map { it.id }.toSet())
        assertEquals(setOf(HUB_ID, "b"), aliceRepo.peers.value.map { it.id }.toSet())
        assertEquals(setOf(HUB_ID, "a"), bobRepo.peers.value.map { it.id }.toSet())
        assertTrue(aliceRepo.peers.value.single { it.id == HUB_ID }.isHub)
    }

    @Test
    fun messagesFlowInEveryDirection() = runBlocking {
        val port = hub.start()
        val (alice, aliceRepo) = connectClient("a", "Alice", port)
        val (bob, bobRepo) = connectClient("b", "Bob", port)
        awaitUntil("both connected") { aliceRepo.peers.value.size == 2 }

        alice.send(HUB_ID, "client to hub")
        awaitUntil("hub receives from Alice") { hubRepo.has("client to hub", conversation = "a") }

        hub.send("b", "hub to client")
        awaitUntil("Bob receives from hub") { bobRepo.has("hub to client", conversation = HUB_ID) }

        alice.send("b", "client to client")
        awaitUntil("Bob receives from Alice") { bobRepo.has("client to client", conversation = "a") }
        assertTrue("hub only relays private messages", hubRepo.messages.value.none { it.text == "client to client" })

        bob.send(BROADCAST_ID, "client to everyone")
        awaitUntil("broadcast reaches Alice and hub") {
            aliceRepo.has("client to everyone", BROADCAST_ID) && hubRepo.has("client to everyone", BROADCAST_ID)
        }
        assertEquals("sender is not echoed", 1, bobRepo.messages.value.count { it.text == "client to everyone" })
        assertEquals("Bob", aliceRepo.messages.value.single { it.text == "client to everyone" }.fromName)

        hub.send(BROADCAST_ID, "hub to everyone")
        awaitUntil("hub broadcast reaches both") {
            aliceRepo.has("hub to everyone", BROADCAST_ID) && bobRepo.has("hub to everyone", BROADCAST_ID)
        }
    }

    @Test
    fun hubOverridesSpoofedSender() = runBlocking {
        val port = hub.start()
        connectClient("a", "Alice", port)
        val (_, bobRepo) = connectClient("b", "Bob", port)

        // A hand-rolled client that joins as Mallory but claims to be Alice in its chat message.
        val mallory = Connection(Socket(InetAddress.getLoopbackAddress(), port))
        mallory.send(Message.Hello("m", "Mallory"))
        assertTrue(mallory.readMessage(timeoutMs = 5_000) is Message.Welcome)
        mallory.send(Message.Chat("x1", from = "a", fromName = "Alice", to = "b", text = "trust me", timestamp = 0))

        awaitUntil("Bob receives") { bobRepo.messages.value.any { it.text == "trust me" } }
        val received = bobRepo.messages.value.single { it.text == "trust me" }
        assertEquals("m", received.fromId)
        assertEquals("Mallory", received.fromName)
        assertEquals("m", received.conversationId)
        mallory.close()
    }

    @Test
    fun leavingAndHubShutdownAreReported() = runBlocking {
        val port = hub.start()
        val (alice, aliceRepo) = connectClient("a", "Alice", port)
        val (bob, bobRepo) = connectClient("b", "Bob", port)
        awaitUntil("both connected") { aliceRepo.peers.value.size == 2 && bobRepo.peers.value.size == 2 }

        alice.disconnect()
        awaitUntil("Bob and hub see Alice leave") {
            bobRepo.peers.value.none { it.id == "a" } && hubRepo.peers.value.none { it.id == "a" }
        }

        bob.send("a", "are you there?")
        awaitUntil("message to departed peer fails") {
            bobRepo.messages.value.single().status == DeliveryStatus.FAILED
        }

        hub.stop()
        awaitUntil("Bob notices the hub is gone") {
            (bob.status.value as? ClientStatus.Disconnected)?.reason != null
        }
        assertTrue(bobRepo.peers.value.isEmpty())
    }

    private fun ChatRepository.statusOf(text: String) = messages.value.single { it.outgoing && it.text == text }.status

    @Test
    fun receiptsComeBackInEveryDirection() = runBlocking {
        val port = hub.start()
        val (alice, aliceRepo) = connectClient("a", "Alice", port)
        val (bob, bobRepo) = connectClient("b", "Bob", port)
        awaitUntil("both connected") { aliceRepo.peers.value.size == 2 && bobRepo.peers.value.size == 2 }

        alice.send(HUB_ID, "to hub")
        awaitUntil("hub confirms") { aliceRepo.statusOf("to hub") == DeliveryStatus.DELIVERED }
        assertEquals(setOf(HUB_ID), aliceRepo.messages.value.single { it.text == "to hub" }.deliveredTo)

        hub.send("a", "to Alice")
        awaitUntil("Alice confirms to hub") { hubRepo.statusOf("to Alice") == DeliveryStatus.DELIVERED }

        alice.send("b", "to Bob")
        awaitUntil("Bob confirms to Alice via hub") { aliceRepo.statusOf("to Bob") == DeliveryStatus.DELIVERED }
        assertEquals(setOf("b"), aliceRepo.messages.value.single { it.text == "to Bob" }.deliveredTo)

        bob.send(BROADCAST_ID, "to all from Bob")
        awaitUntil("hub and Alice both confirm") { bobRepo.statusOf("to all from Bob") == DeliveryStatus.DELIVERED }
        val broadcast = bobRepo.messages.value.single { it.text == "to all from Bob" }
        assertEquals(2, broadcast.recipientCount)
        assertEquals(setOf(HUB_ID, "a"), broadcast.deliveredTo)

        hub.send(BROADCAST_ID, "to all from hub")
        awaitUntil("both clients confirm") { hubRepo.statusOf("to all from hub") == DeliveryStatus.DELIVERED }
        assertEquals(setOf("a", "b"), hubRepo.messages.value.single { it.text == "to all from hub" }.deliveredTo)
    }

    @Test
    fun messageStaysSentUntilReceiverConfirms() = runBlocking {
        val port = hub.start()
        val (alice, aliceRepo) = connectClient("a", "Alice", port)

        // A raw client that receives messages but only acknowledges when we tell it to.
        val silent = Connection(Socket(InetAddress.getLoopbackAddress(), port))
        silent.send(Message.Hello("s", "Silent"))
        assertTrue(silent.readMessage(timeoutMs = 5_000) is Message.Welcome)
        awaitUntil("Alice sees Silent") { aliceRepo.peers.value.any { it.id == "s" } }

        alice.send("s", "did you get this?")
        val chat = silent.readMessage(timeoutMs = 5_000) as Message.Chat
        awaitUntil("hub relayed it") { aliceRepo.statusOf("did you get this?") == DeliveryStatus.SENT }
        delay(200)
        assertEquals("no receipt yet", DeliveryStatus.SENT, aliceRepo.statusOf("did you get this?"))

        // Receipt claims to be from someone else; the hub must attribute it to the real socket.
        silent.send(Message.Delivered(chat.msgId, by = "someone-else", to = chat.from))
        awaitUntil("receipt arrives") { aliceRepo.statusOf("did you get this?") == DeliveryStatus.DELIVERED }
        assertEquals(setOf("s"), aliceRepo.messages.value.single { it.text == "did you get this?" }.deliveredTo)
        silent.close()
    }

    @Test
    fun unconfirmedMessagesFailWhenHubConnectionDrops() = runBlocking {
        val port = hub.start()
        val (alice, aliceRepo) = connectClient("a", "Alice", port)
        hub.stop()
        awaitUntil("Alice notices") { alice.status.value is ClientStatus.Disconnected }

        alice.send(HUB_ID, "into the void")
        assertEquals(DeliveryStatus.FAILED, aliceRepo.statusOf("into the void"))
    }

    @Test
    fun duplicateIdIsRejected() = runBlocking {
        val port = hub.start()
        connectClient("a", "Alice", port)
        awaitUntil("hub registers Alice") { hubRepo.peers.value.size == 1 }

        val impostor = ClientConnection("a", "Impostor", ChatRepository().apply { reset("a") }, scope)
        impostor.connect(InetAddress.getLoopbackAddress(), port, "Hub")
        assertTrue(impostor.status.value is ClientStatus.Disconnected)
        assertEquals(1, hubRepo.peers.value.size)
    }

    @Test
    fun disallowedConnectionNeverJoins() = runBlocking {
        val strictHub = HubServer("Hub", hubRepo, scope, allowConnection = { false })
        try {
            val port = strictHub.start()
            val outsider = ClientConnection("a", "Outsider", ChatRepository().apply { reset("a") }, scope)
            outsider.connect(InetAddress.getLoopbackAddress(), port, "Hub")
            assertTrue(outsider.status.value is ClientStatus.Disconnected)
            assertEquals(0, hubRepo.peers.value.size)
        } finally {
            strictHub.stop()
        }
    }
}
