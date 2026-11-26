package dev.sakura.mobile

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

internal fun discoverPc(context: Context, fingerprint: String) = callbackFlow {
    val nsd = context.getSystemService(NsdManager::class.java)
    val prefix = "Sakura-${fingerprint.take(16)}"
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            close(IllegalStateException("PC discovery unavailable ($code)"))
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        override fun onServiceLost(service: NsdServiceInfo) = Unit

        @Suppress("DEPRECATION")
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceName != prefix) return
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    info.host?.hostAddress?.let { trySend(it to info.port) }
                }
            })
        }
    }
    nsd.discoverServices("_sakura-mobile._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
}
