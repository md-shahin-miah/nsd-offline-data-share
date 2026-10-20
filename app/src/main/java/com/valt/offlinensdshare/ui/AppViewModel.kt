package com.valt.offlinensdshare.ui

import android.app.Application
import android.net.nsd.NsdServiceInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.valt.offlinensdshare.data.ChatRepository
import com.valt.offlinensdshare.data.HUB_ID
import com.valt.offlinensdshare.data.Role
import com.valt.offlinensdshare.net.ClientConnection
import com.valt.offlinensdshare.net.ClientStatus
import com.valt.offlinensdshare.net.HubServer
import com.valt.offlinensdshare.nsd.MulticastLockHolder
import com.valt.offlinensdshare.nsd.NsdAdvertiser
import com.valt.offlinensdshare.nsd.NsdDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

sealed interface HubStatus {
    data object Stopped : HubStatus
    data object Starting : HubStatus
    data class Advertising(val serviceName: String, val port: Int) : HubStatus
    data class Error(val message: String) : HubStatus
}

/**
 * Owns the current session (hub or client). Scoped to the activity, so it survives rotation and
 * everything is shut down when the app's screen is finished.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val repository = ChatRepository()

    private val advertiser = NsdAdvertiser(application)
    private val discovery = NsdDiscovery(application)
    private val multicastLock = MulticastLockHolder(application)

    var role: Role? = null
        private set
    var myName: String = ""
        private set

    private var hubServer: HubServer? = null
    private var hubStartJob: Job? = null
    private val _hubStatus = MutableStateFlow<HubStatus>(HubStatus.Stopped)
    val hubStatus: StateFlow<HubStatus> = _hubStatus.asStateFlow()

    private val client = MutableStateFlow<ClientConnection?>(null)
    val clientStatus: StateFlow<ClientStatus> = client
        .flatMapLatest { it?.status ?: flowOf(ClientStatus.Idle) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ClientStatus.Idle)

    val discoveredHubs: StateFlow<List<NsdServiceInfo>> = discovery.services
    val discoveryError: StateFlow<String?> = discovery.error

    fun startHub(name: String) {
        stopSession()
        role = Role.HUB
        myName = name
        repository.reset(HUB_ID)
        multicastLock.acquire()
        _hubStatus.value = HubStatus.Starting
        hubStartJob = viewModelScope.launch {
            val server = HubServer(name, repository, viewModelScope)
            val port = try {
                server.start()
            } catch (e: CancellationException) {
                server.stop() // session ended while the socket was opening
                throw e
            } catch (e: IOException) {
                _hubStatus.value = HubStatus.Error("Could not open server: ${e.message}")
                return@launch
            }
            hubServer = server
            advertiser.register(
                name = name,
                port = port,
                onRegistered = { serviceName -> _hubStatus.value = HubStatus.Advertising(serviceName, port) },
                onFailed = { code -> _hubStatus.value = HubStatus.Error("Could not advertise hub (error $code)") },
            )
        }
    }

    fun startClient(name: String) {
        stopSession()
        role = Role.CLIENT
        myName = name
        val myId = UUID.randomUUID().toString()
        repository.reset(myId)
        client.value = ClientConnection(myId, name, repository, viewModelScope)
        multicastLock.acquire()
        discovery.start()
    }

    fun restartDiscovery() {
        discovery.stop()
        discovery.start()
    }

    fun connect(hub: NsdServiceInfo) {
        val connection = client.value ?: return
        connection.connecting(hub.serviceName)
        viewModelScope.launch {
            try {
                val resolved = discovery.resolve(hub)
                connection.connect(resolved.host, resolved.port, hub.serviceName)
            } catch (e: IOException) {
                connection.fail(e.message ?: "Could not find hub")
            }
        }
    }

    fun disconnect() {
        client.value?.disconnect()
    }

    fun send(to: String, text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            when (role) {
                Role.HUB -> hubServer?.send(to, message)
                Role.CLIENT -> client.value?.send(to, message)
                null -> Unit
            }
        }
    }

    /** Ends whatever session is running and returns to a clean state (used by the role screen). */
    fun stopSession() {
        hubStartJob?.cancel()
        hubStartJob = null
        advertiser.unregister()
        val server = hubServer
        hubServer = null
        val connection = client.value
        client.value = null
        discovery.stop()
        multicastLock.release()
        // Closing sockets doesn't block, so this is safe on the main thread and finishes before
        // a new session resets the repository.
        server?.stop()
        connection?.shutdown()
        _hubStatus.value = HubStatus.Stopped
        role = null
    }

    override fun onCleared() {
        stopSession()
    }
}
