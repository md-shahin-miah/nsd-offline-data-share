package com.valt.offlinensdshare.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** DNS-SD service type shared by hub (advertise) and clients (discover). */
const val SERVICE_TYPE = "_nsdshare._tcp"

/** Announces the hub's server port on the local network so clients can find it without an IP. */
class NsdAdvertiser(context: Context) {

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    /**
     * [onRegistered] receives the final service name, which NSD may change (e.g. "Pixel (2)")
     * if another hub already uses the requested one. Callbacks arrive on a binder thread.
     */
    fun register(
        name: String,
        port: Int,
        onRegistered: (String) -> Unit,
        onFailed: (Int) -> Unit,
    ) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = onRegistered(info.serviceName)
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                if (listener === this) listener = null
                onFailed(errorCode)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = registrationListener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregister() {
        val current = listener ?: return
        listener = null
        try {
            nsdManager.unregisterService(current)
        } catch (_: IllegalArgumentException) {
            // Registration had already failed or been removed.
        }
    }
}
