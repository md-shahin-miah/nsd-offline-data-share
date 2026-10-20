package com.valt.offlinensdshare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valt.offlinensdshare.data.BROADCAST_ID
import com.valt.offlinensdshare.ui.AppViewModel
import com.valt.offlinensdshare.ui.HubStatus

@Composable
fun HubScreen(
    viewModel: AppViewModel,
    onOpenChat: (peerId: String) -> Unit,
    onBack: () -> Unit,
) {
    val status by viewModel.hubStatus.collectAsState()
    val clients by viewModel.repository.peers.collectAsState()
    val unread by viewModel.repository.unread.collectAsState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Hub · ${viewModel.myName}",
                onBack = onBack,
                actions = { TextButton(onClick = onBack) { Text("Stop") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HubStatusCard(status, clients.size)
            if (clients.isEmpty()) {
                EmptyState(
                    text = "Waiting for clients to connect…\nDevices must be on the same Wi-Fi or hotspot.",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (status is HubStatus.Advertising) CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        PeerRow(
                            name = "Everyone",
                            subtitle = "Broadcast to all ${clients.size} client(s)",
                            unread = unread[BROADCAST_ID] ?: 0,
                            onClick = { onOpenChat(BROADCAST_ID) },
                        )
                    }
                    items(clients, key = { it.id }) { client ->
                        PeerRow(
                            name = client.name,
                            subtitle = "Connected",
                            unread = unread[client.id] ?: 0,
                            onClick = { onOpenChat(client.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HubStatusCard(status: HubStatus, clientCount: Int) {
    val isError = status is HubStatus.Error
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status is HubStatus.Starting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Column {
                when (status) {
                    HubStatus.Stopped, HubStatus.Starting -> Text("Starting hub…")
                    is HubStatus.Advertising -> {
                        Text("Visible as \"${status.serviceName}\"", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Port ${status.port} · $clientCount client(s) connected",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is HubStatus.Error -> Text(status.message, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}
