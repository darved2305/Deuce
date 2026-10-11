package com.elevate.app.data.record

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Reads the phone's battery, heat, storage and connection for the pure gates in [RecordGates]. */
class DeviceSignals(private val context: Context) {
    private val app = context.applicationContext

    fun snapshot(storageDir: File): DeviceSnapshot {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        // Thermal status exists from API 29. Below that the phone reports nothing, so heat cannot be gated.
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus ?: Thermal.NONE
        } else Thermal.NONE
        storageDir.mkdirs()
        val free = runCatching { StatFs(storageDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        return DeviceSnapshot(percent, charging, thermal, free)
    }

    fun networkState(): NetworkState {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        return NetworkState(online, unmetered)
    }

    /** Live connection state; the callback is removed by the returned [AutoCloseable]. */
    fun watchNetwork(): Pair<StateFlow<NetworkState>, AutoCloseable> {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val state = MutableStateFlow(networkState())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = networkState() }
            override fun onLost(network: Network) { state.value = networkState() }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { state.value = networkState() }
        }
        cm.registerDefaultNetworkCallback(cb)
        return state.asStateFlow() to AutoCloseable { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
}
