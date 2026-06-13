package com.epson.epos2.discovery

import android.content.Context

/**
 * Faux Discovery ePOS2 (test classpath). Simule la découverte d'une imprimante
 * en appelant immédiatement le listener fourni par l'adapter (via proxy réflexif).
 */
object Discovery {
    @JvmField val TYPE_PRINTER = 0
    var started = false

    @JvmStatic
    fun start(context: Context, filter: FilterOption, listener: DiscoveryListener) {
        started = true
        listener.onDiscovery(DeviceInfo("TCP:192.168.1.50", "TM-m30"))
    }

    @JvmStatic
    fun stop() { started = false }
}

class FilterOption {
    @JvmField var deviceType = 0
    fun setDeviceType(type: Int) { deviceType = type }
}

interface DiscoveryListener {
    fun onDiscovery(deviceInfo: DeviceInfo)
}

class DeviceInfo(private val target: String, private val name: String) {
    fun getTarget(): String = target
    fun getDeviceName(): String = name
}
