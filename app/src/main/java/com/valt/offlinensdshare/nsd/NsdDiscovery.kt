package com.valt.offlinensdshare.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ResolvedService(val host: InetAddress, val port: Int)

/** Finds hubs advertised with [SERVICE_TYPE] and resolves them to an address on demand. */
class NsdDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null

    private val _services = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    /** Hubs currently visible on the network, keyed by service name. */
    val services: StateFlow<List<NsdServiceInfo>> = _services.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Before API 34 NsdManager can resolve only one service at a time. */
    private val legacyResolveLock = Mutex()

    /**
     * Before API 34 a resolve can't be cancelled, and one that never answers makes its NsdManager
     * refuse every later resolve until the app restarts. After a timeout, resolves move to a fresh one.
     */
    @Volatile
    private var legacyResolver: NsdManager = nsdManager

    fun start() {
        if (listener != null) return
        _error.value = null
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // Found types come back as "_nsdshare._tcp." (trailing dot), so match loosely.
                if (!info.serviceType.contains(SERVICE_TYPE)) return
                _services.update { list -> list.filterNot { it.serviceName == info.serviceName } + info }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                _services.update { list -> list.filterNot { it.serviceName == info.serviceName } }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (listener === this) listener = null
                _error.value = "Discovery failed (error $errorCode)"
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discoveryListener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        val current = listener
        listener = null
        _services.value = emptyList()
        if (current != null) {
            try {
                nsdManager.stopServiceDiscovery(current)
            } catch (_: IllegalArgumentException) {
                // Discovery had already failed to start.
            }
        }
    }

    /** Fails after [RESOLVE_TIMEOUT_MS] instead of waiting forever if the hub never answers. */
    suspend fun resolve(info: NsdServiceInfo): ResolvedService {
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        return try {
            withTimeout(RESOLVE_TIMEOUT_MS) {
                if (legacy) resolveLegacy(info) else resolveWithCallback(info)
            }
        } catch (e: TimeoutCancellationException) {
            if (legacy) legacyResolver = freshNsdManager()
            throw IOException("Hub did not answer. Tap Refresh and try again.")
        }
    }

    /** The system tracks pending resolves per NsdManager, and each context creates its own. */
    private fun freshNsdManager(): NsdManager =
        context.createConfigurationContext(context.resources.configuration)
            .getSystemService(NsdManager::class.java)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveWithCallback(info: NsdServiceInfo): ResolvedService {
        var callback: NsdManager.ServiceInfoCallback? = null
        try {
            return suspendCancellableCoroutine { cont ->
                val serviceInfoCallback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        if (cont.isActive) cont.resumeWithException(IOException("Resolve failed (error $errorCode)"))
                    }

                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        val addresses = serviceInfo.hostAddresses
                        val host = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
                        if (host != null && serviceInfo.port > 0 && cont.isActive) {
                            cont.resume(ResolvedService(host, serviceInfo.port))
                        }
                    }

                    override fun onServiceLost() {
                        if (cont.isActive) cont.resumeWithException(IOException("Hub is no longer available"))
                    }

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                callback = serviceInfoCallback
                nsdManager.registerServiceInfoCallback(info, context.mainExecutor, serviceInfoCallback)
            }
        } finally {
            callback?.let {
                try {
                    nsdManager.unregisterServiceInfoCallback(it)
                } catch (_: IllegalArgumentException) {
                    // Registration failed, so there is nothing to unregister.
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(info: NsdServiceInfo): ResolvedService = legacyResolveLock.withLock {
        suspendCancellableCoroutine { cont ->
            legacyResolver.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resumeWithException(IOException("Resolve failed (error $errorCode)"))
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(ResolvedService(serviceInfo.host, serviceInfo.port))
                }
            })
        }
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 10_000L
    }
}
