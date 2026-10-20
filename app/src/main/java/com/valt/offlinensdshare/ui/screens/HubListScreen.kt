package com.valt.offlinensdshare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valt.offlinensdshare.net.ClientStatus
import com.valt.offlinensdshare.ui.AppViewModel

@Composable
fun HubListScreen(
    viewModel: AppViewModel,
    onConnected: () -> Unit,
    onBack: () -> Unit,
) {
    val hubs by viewModel.discoveredHubs.collectAsState()
    val status by viewModel.clientStatus.collectAsState()
    val discoveryError by viewModel.discoveryError.collectAsState()

    LaunchedEffect(status) {
        if (status is ClientStatus.Connected) onConnected()
    }

    val connectingTo = (status as? ClientStatus.Connecting)?.hubName
    val message = when {
        discoveryError != null -> discoveryError
        status is ClientStatus.Disconnected -> (status as ClientStatus.Disconnected).reason
        else -> null
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Find a hub",
                subtitle = "You are ${viewModel.myName}",
                onBack = onBack,
                actions = { TextButton(onClick = viewModel::restartDiscovery) { Text("Refresh") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (message != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (hubs.isEmpty()) {
                EmptyState(
                    text = "Searching for hubs…\nMake sure a hub is running on the same Wi-Fi or hotspot.",
                    modifier = Modifier.fillMaxWidth(),
                ) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(hubs, key = { it.serviceName }) { hub ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(hub.serviceName)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    hub.serviceName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "Hub",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (connectingTo == hub.serviceName) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text("Connecting…", style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.connect(hub) },
                                    enabled = connectingTo == null,
                                ) { Text("Connect") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
