# How Hub and Clients Connect

Offline NSD Share lets phones on the **same Wi-Fi or hotspot** chat with each other **without internet** and **without anyone typing an IP address**.

---

## 1. The two roles

| Role | What it does |
|---|---|
| **Hub** | The meeting point. It announces itself on the network, accepts every client automatically, and passes messages along. |
| **Client** | Finds hubs, connects to one, then can message the hub, any other client, or everyone. |

Every client connects **only to the hub**. When client A writes to client B, the hub passes the message on.

```
            ┌──────────── HUB ────────────┐
            │  "I'm here!" (NSD announce) │
            └───┬──────────┬──────────┬───┘
                │          │          │      one connection per client
            Client A   Client B   Client C
```

---

## 2. Identity: who is who

Each device has three things that identify it:

| Name | Example | Who sees it | Purpose |
|---|---|---|---|
| **Display name** | `Pixel 8`, `Alice` | Everyone | Typed on the first screen; shown in lists and chats. |
| **Peer ID** | Hub: `hub` · Client: `3f2a…-…c91` (random UUID) | Only the app | The real address used to deliver messages. Names can repeat; IDs never do. |
| **Service name** | `Alice` or `Alice (2)` | Clients looking for hubs | The hub's name on the network. Android adds `(2)` if two hubs share a name. |

- A client gets a **new random ID each time it chooses "Client"**. The ID stays the same if the client reconnects in that session.
- The special ID `ALL` means "everyone".
- **The hub trusts the connection, not the message.** Each client's ID is tied to its connection when it joins. If a message claims to come from someone else, the hub overwrites the sender with the real one.

---

## 3. Connecting, step by step

```
 HUB                                   CLIENT
  │ 1. Open a server on a free port      │
  │ 2. Announce "_nsdshare._tcp"         │
  │    name = "Alice", port = 41234      │
  │ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─▶ │ 3. Discover: "Alice" appears in the hub list
  │                                      │ 4. User taps CONNECT
  │                                      │ 5. Resolve "Alice" → 192.168.1.20 : 41234
  │ ◀──────── 6. TCP connect ─────────── │
  │ ◀──────── HELLO {id, name} ───────── │
  │ 7. Auto-accept, register the client  │
  │ ───────── WELCOME {hub, peers} ────▶ │ 8. Shows: hub + all other clients
  │ ── PEER_JOINED ──▶ (other clients)   │
```

1. **Hub starts.** It opens a TCP server on any free port.
2. **Hub announces.** NSD (Network Service Discovery, also called mDNS / Bonjour) broadcasts on the local network: *"A `_nsdshare._tcp` service called Alice is on port 41234."*
3. **Client discovers.** It listens for `_nsdshare._tcp` announcements and lists every hub it hears. Hubs appear and disappear from the list automatically.
4. **User taps Connect.**
5. **Client resolves** the hub's name to its current IP and port. This is how nobody ever has to know an IP.
6. **Client connects and says `HELLO`** with its ID and display name.
7. **Hub auto-accepts.** There's no prompt. It only refuses a duplicate ID.
8. **Hub replies `WELCOME`** with its own name and the list of everyone already connected. It tells the other clients `PEER_JOINED`, so every list updates live.

---

## 4. Sending a message

Every message carries a `from` ID and a `to` ID: a client ID, `hub`, or `ALL`.

| `to` | What the hub does |
|---|---|
| `hub` | Keeps the message (it's for the hub). |
| a client ID | Forwards it to that client only. |
| `ALL` | Keeps a copy, and forwards it to every client except the sender. |

The hub sends its own messages directly to a client, or to all clients.

### Did it arrive? (receipts)

```
 Alice ──CHAT──▶ HUB ──CHAT──▶ Bob
 Alice ◀─RELAYED─ HUB                    ✓  Sent       (the hub has it)
 Alice ◀────DELIVERED───── HUB ◀─ Bob    ✓✓ Delivered  (Bob's phone has it)
```

| Label in chat | Meaning |
|---|---|
| Sending… | Not confirmed by the hub yet |
| ✓ Sent | Hub has it, receiver hasn't confirmed |
| ✓✓ Delivered | Receiver's device confirmed it |
| ✓✓ Delivered to 2 of 3 | "Everyone" message, partly confirmed |
| Not delivered | Receiver offline, or connection lost first |

---

## 5. Staying connected and leaving

- **Heartbeat:** both sides send a `PING` every 5 s. If one side hears nothing for 15 s, it treats the connection as lost.
- **Client leaves:** the hub removes it and tells everyone `PEER_LEFT`. Messages sent to that client after that show *Not delivered*.
- **Hub stops:** every client gets disconnected and returns to the hub list with "Connection to hub lost".

---

## 6. Messages on the wire

Each message is one line of JSON over the TCP connection:

| Type | Direction | Purpose |
|---|---|---|
| `HELLO` | client → hub | "I'm `id`, call me `name`" |
| `WELCOME` | hub → client | Hub's name + who's already here |
| `PEER_JOINED` / `PEER_LEFT` | hub → clients | Keep everyone's list up to date |
| `CHAT` | anyone → hub → target | The actual message |
| `RELAYED` | hub → sender | "I forwarded it" |
| `DELIVERED` | receiver → hub → sender | "I received it" |
| `ERROR` | hub → client | e.g. "Recipient is offline" |
| `PING` / `PONG` | both ways | Heartbeat |

Example:

```json
{"type":"CHAT","msgId":"7d1e…","from":"3f2a…","fromName":"Alice","to":"hub","text":"Hi!","ts":1760950000000}
```

---

## 7. Requirements and troubleshooting

- All devices must be on the **same Wi-Fi network or the same phone hotspot**. Internet is not needed.
- If no hub shows up:
  - Check the router doesn't have **AP / client isolation** turned on. That setting blocks phones from seeing each other.
  - Tap **Refresh** on the hub list.
- Emulators can't discover each other reliably. Test on real phones.
- The connection lives while the app is open. Message history is kept only in memory.

---

### Where this lives in the code

| Part | File |
|---|---|
| Announce / discover / resolve | `nsd/NsdAdvertiser.kt`, `nsd/NsdDiscovery.kt` |
| Hub server, auto-accept, routing | `net/HubServer.kt` |
| Client connection | `net/ClientConnection.kt` |
| Message format | `net/Protocol.kt` |
| Sessions, peers, receipts state | `data/ChatRepository.kt`, `ui/AppViewModel.kt` |
