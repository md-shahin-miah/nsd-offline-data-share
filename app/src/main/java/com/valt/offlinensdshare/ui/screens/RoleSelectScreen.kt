package com.valt.offlinensdshare.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.valt.offlinensdshare.ui.AppViewModel

@Composable
fun RoleSelectScreen(
    viewModel: AppViewModel,
    onHub: (name: String) -> Unit,
    onClient: (name: String) -> Unit,
) {
    // Coming back to this screen always ends the previous hub/client session.
    LaunchedEffect(Unit) { viewModel.stopSession() }

    var name by rememberSaveable { mutableStateOf(Build.MODEL ?: "Device") }
    val validName = name.trim().take(MAX_NAME_LENGTH)

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Offline NSD Share", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Chat with nearby devices on the same Wi-Fi or hotspot. No internet needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onHub(validName) },
                enabled = validName.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Start as HUB") }
            Text(
                "Others connect to you. You relay messages between everyone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            OutlinedButton(
                onClick = { onClient(validName) },
                enabled = validName.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Join as CLIENT") }
            Text(
                "Find a hub on this network and connect to it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// NSD service names are limited to 63 bytes; keep names comfortably below that.
private const val MAX_NAME_LENGTH = 30
