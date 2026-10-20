package com.valt.offlinensdshare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valt.offlinensdshare.data.BROADCAST_ID
import com.valt.offlinensdshare.data.ChatMessage
import com.valt.offlinensdshare.data.DeliveryStatus
import com.valt.offlinensdshare.data.Role
import com.valt.offlinensdshare.net.ClientStatus
import com.valt.offlinensdshare.ui.AppViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    peerId: String,
    onBack: () -> Unit,
    onHubLost: () -> Unit,
) {
    val repository = viewModel.repository
    val peers by repository.peers.collectAsState()
    val allMessages by repository.messages.collectAsState()
    val clientStatus by viewModel.clientStatus.collectAsState()

    // Mark the conversation as read while it is on screen.
    DisposableEffect(peerId) {
        repository.openConversation(peerId)
        onDispose { repository.closeConversation(peerId) }
    }

    if (viewModel.role == Role.CLIENT) {
        LaunchedEffect(clientStatus) {
            if (clientStatus !is ClientStatus.Connected) onHubLost()
        }
    }

    val isBroadcast = peerId == BROADCAST_ID
    val peer = peers.find { it.id == peerId }
    val messages = remember(allMessages, peerId) { allMessages.filter { it.conversationId == peerId } }
    val online = isBroadcast || peer != null
    val title = when {
        isBroadcast -> "Everyone"
        peer != null -> peer.name
        else -> messages.lastOrNull { !it.outgoing }?.fromName ?: "Unknown"
    }
    val subtitle = when {
        isBroadcast -> "${peers.size} recipient(s)"
        peer == null -> "Offline"
        peer.isHub -> "Hub"
        else -> "Online"
    }

    var draft by rememberSaveable(peerId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(topBar = { AppTopBar(title = title, subtitle = subtitle, onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(if (isBroadcast) "Messages here go to everyone." else "Say hello to $title.")
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message, showSender = isBroadcast && !message.outgoing)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(if (online) "Message" else "$title is offline") },
                    enabled = online,
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        viewModel.send(peerId, draft)
                        draft = ""
                    },
                    enabled = online && draft.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, showSender: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (message.outgoing) colors.primary else colors.surfaceVariant,
            contentColor = if (message.outgoing) colors.onPrimary else colors.onSurfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showSender) {
                    Text(message.fromName, style = MaterialTheme.typography.labelMedium, color = colors.primary)
                }
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
        val time = remember(message.timestamp) {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp))
        }
        val status = if (message.outgoing) deliveryLabel(message) else null
        Text(
            text = if (status != null) "$time · $status" else time,
            style = MaterialTheme.typography.labelSmall,
            color = when (message.status) {
                DeliveryStatus.FAILED -> colors.error
                DeliveryStatus.DELIVERED -> colors.primary
                else -> colors.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

private fun deliveryLabel(message: ChatMessage): String = when (message.status) {
    DeliveryStatus.SENDING -> "Sending…"
    DeliveryStatus.SENT ->
        if (message.recipientCount > 1 && message.deliveredTo.isNotEmpty()) {
            "✓✓ Delivered to ${message.deliveredTo.size} of ${message.recipientCount}"
        } else {
            "✓ Sent"
        }
    DeliveryStatus.DELIVERED ->
        if (message.recipientCount > 1) "✓✓ Delivered to all ${message.recipientCount}" else "✓✓ Delivered"
    DeliveryStatus.FAILED -> "Not delivered"
    DeliveryStatus.RECEIVED -> ""
}
