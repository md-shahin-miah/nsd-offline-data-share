package com.valt.offlinensdshare.nsd

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Keeps the Wi-Fi driver from filtering multicast packets. Some devices drop mDNS traffic
 * without it, which makes hubs invisible even on the same network.
 */
class MulticastLockHolder(context: Context) {

    private val lock = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.createMulticastLock("OfflineNSDShare")
        ?.apply { setReferenceCounted(false) }

    fun acquire() {
        lock?.acquire()
    }

    fun release() {
        if (lock?.isHeld == true) lock.release()
    }
}
