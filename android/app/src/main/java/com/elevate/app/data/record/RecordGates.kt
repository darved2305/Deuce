package com.elevate.app.data.record

/** Android's `PowerManager.THERMAL_STATUS_*` values (stable API constants), named. */
object Thermal {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
    const val EMERGENCY = 5
    const val SHUTDOWN = 6
}

/** The phone's condition at one moment. */
data class DeviceSnapshot(
    val batteryPercent: Int,
    val charging: Boolean,
    val thermalStatus: Int,
    val freeBytes: Long,
)

sealed interface Gate {
    data object Ok : Gate
    /** Recording may go on; tell the user. */
    data class Warn(val message: String) : Gate
    /** Do not start. */
    data class Block(val message: String) : Gate
    /** Stop the recording now, cleanly, so what was recorded stays valid. */
    data class Stop(val message: String) : Gate
}

/**
 * Battery, heat and storage decisions. Pure: the numbers are read elsewhere ([DeviceSignals]).
 * Thresholds are explained in RECORDING.md.
 */
object RecordGates {
    const val BATTERY_WARN_PERCENT = 20
    const val BATTERY_BLOCK_PERCENT = 8
    const val BATTERY_STOP_PERCENT = 4
    const val STORAGE_MARGIN_BYTES = 100L * 1024 * 1024

    /**
     * Free space the recording needs: the pieces, plus the joined local copy made at the end (both
     * exist at once), plus a margin for the system.
     */
    fun requiredStorageBytes(estimatedBytes: Long): Long = estimatedBytes * 2 + STORAGE_MARGIN_BYTES

    /** Checked on the pre-record screen and again when Record is tapped. */
    fun beforeStart(s: DeviceSnapshot, estimatedBytes: Long): Gate {
        val need = requiredStorageBytes(estimatedBytes)
        if (s.freeBytes < need) {
            return Gate.Block("Not enough free space: about ${mb(need)} MB needed, ${mb(s.freeBytes)} MB free.")
        }
        if (s.thermalStatus >= Thermal.SEVERE) return Gate.Block("The phone is too hot to record. Let it cool down first.")
        if (!s.charging && s.batteryPercent <= BATTERY_BLOCK_PERCENT) {
            return Gate.Block("Battery is at ${s.batteryPercent} percent. Plug in before recording.")
        }
        if (s.thermalStatus >= Thermal.MODERATE) return Gate.Warn("The phone is getting warm. Recording may stop early if it heats up.")
        if (!s.charging && s.batteryPercent < BATTERY_WARN_PERCENT) {
            return Gate.Warn("Battery is at ${s.batteryPercent} percent. A plugged-in phone records longer.")
        }
        return Gate.Ok
    }

    /** Checked every few seconds while recording. Throttling stops the recording before the OS does it for us. */
    fun whileRecording(s: DeviceSnapshot): Gate {
        if (s.thermalStatus >= Thermal.SEVERE) return Gate.Stop("The phone is overheating, so the recording was stopped and saved.")
        if (!s.charging && s.batteryPercent <= BATTERY_STOP_PERCENT) {
            return Gate.Stop("Battery is almost empty, so the recording was stopped and saved.")
        }
        if (s.thermalStatus >= Thermal.MODERATE) return Gate.Warn("The phone is warm. Recording stops if it overheats.")
        if (!s.charging && s.batteryPercent < BATTERY_WARN_PERCENT) return Gate.Warn("Battery is low (${s.batteryPercent} percent).")
        return Gate.Ok
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)
}

/** Where pieces may be sent. */
enum class UploadNetworkPolicy { WifiOnly, WifiOrMobile }

/** What the phone's connection is right now. */
data class NetworkState(val online: Boolean, val unmetered: Boolean)

object UploadNetwork {
    /** Whether a piece may be sent now. When false, pieces wait on the phone and nothing is lost. */
    fun allowed(policy: UploadNetworkPolicy, n: NetworkState): Boolean = when (policy) {
        UploadNetworkPolicy.WifiOnly -> n.online && n.unmetered
        UploadNetworkPolicy.WifiOrMobile -> n.online
    }

    fun waitingMessage(policy: UploadNetworkPolicy, n: NetworkState): String? = when {
        !n.online -> "No connection. Pieces are kept on this phone and sent when you are back online."
        policy == UploadNetworkPolicy.WifiOnly && !n.unmetered -> "Waiting for Wi-Fi. Turn on mobile data upload to send now."
        else -> null
    }
}
