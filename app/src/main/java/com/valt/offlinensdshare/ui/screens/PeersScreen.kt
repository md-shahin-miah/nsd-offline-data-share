package com.valt.offlinensdshare.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.valt.offlinensdshare.data.BROADCAST_ID
import com.valt.offlinensdshare.data.Peer
import com.valt.offlinensdshare.net.ClientStatus
import com.valt.offlinensdshare.ui.AppViewModel

/** Client's view once connected: the hub plus every other client, all reachable through the hub. */
@Composable
fun PeersScreen(
    viewModel: AppViewModel,
    onOpenChat: (peerId: String) -> Unit,
    onLeave: () -> Unit,
) {
    val status by viewModel.clientStatus.collectAsState()
    val peers by viewModel.repository.peers.collectAsState()
    val unread by viewModel.repository.unread.collectAsState()

    // Hub went away (or we disconnected): go back to the hub list, which shows the reason.
    LaunchedEffect(status) {
        if (status !is ClientStatus.Connected) onLeave()
    }

    val disconnect = { viewModel.disconnect() }
    BackHandler(onBack = disconnect)

    val hubName = (status as? ClientStatus.Connected)?.hubName.orEmpty()
    val sorted = peers.sortedWith(compareByDescending<Peer> { it.isHub }.thenBy { it.name })

    Scaffold(
        topBar = {
            AppTopBar(
                title = hubName,
                subtitle = "Connected as ${viewModel.myName}",
                onBack = disconnect,
                actions = { TextButton(onClick = disconnect) { Text("Disconnect") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                PeerRow(
                    name = "Everyone",
                    subtitle = "Broadcast to hub and all clients",
                    unread = unread[BROADCAST_ID] ?: 0,
                    onClick = { onOpenChat(BROADCAST_ID) },
                )
            }
            items(sorted, key = { it.id }) { peer ->
                PeerRow(
                    name = peer.name,
                    subtitle = if (peer.isHub) "Hub" else "Client",
                    tag = if (peer.isHub) "HUB" else null,
                    unread = unread[peer.id] ?: 0,
                    onClick = { onOpenChat(peer.id) },
                )
            }
        }
    }
}
