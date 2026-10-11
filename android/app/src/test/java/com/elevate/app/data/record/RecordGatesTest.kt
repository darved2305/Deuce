package com.elevate.app.data.record

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordGatesTest {
    private val mib = 1024L * 1024
    private fun snap(battery: Int = 80, charging: Boolean = false, thermal: Int = Thermal.NONE, free: Long = 10_000 * mib) =
        DeviceSnapshot(battery, charging, thermal, free)

    @Test fun `a healthy phone may record`() {
        assertThat(RecordGates.beforeStart(snap(), 250 * mib)).isEqualTo(Gate.Ok)
        assertThat(RecordGates.whileRecording(snap())).isEqualTo(Gate.Ok)
    }

    @Test fun `storage needs the pieces, the joined copy and a margin`() {
        assertThat(RecordGates.requiredStorageBytes(250 * mib)).isEqualTo(250 * mib * 2 + 100 * mib)
        val need = RecordGates.requiredStorageBytes(250 * mib)
        assertThat(RecordGates.beforeStart(snap(free = need), 250 * mib)).isEqualTo(Gate.Ok)
        assertThat(RecordGates.beforeStart(snap(free = need - 1), 250 * mib)).isInstanceOf(Gate.Block::class.java)
    }

    @Test fun `low battery warns then blocks when not charging`() {
        assertThat(RecordGates.beforeStart(snap(battery = 25), 100 * mib)).isEqualTo(Gate.Ok)
        assertThat(RecordGates.beforeStart(snap(battery = 19), 100 * mib)).isInstanceOf(Gate.Warn::class.java)
        assertThat(RecordGates.beforeStart(snap(battery = 8), 100 * mib)).isInstanceOf(Gate.Block::class.java)
    }

    @Test fun `a charging phone is never held back by battery`() {
        assertThat(RecordGates.beforeStart(snap(battery = 3, charging = true), 100 * mib)).isEqualTo(Gate.Ok)
        assertThat(RecordGates.whileRecording(snap(battery = 2, charging = true))).isEqualTo(Gate.Ok)
    }

    @Test fun `battery stops a running recording only near empty`() {
        assertThat(RecordGates.whileRecording(snap(battery = 10))).isInstanceOf(Gate.Warn::class.java)
        assertThat(RecordGates.whileRecording(snap(battery = 5))).isInstanceOf(Gate.Warn::class.java)
        assertThat(RecordGates.whileRecording(snap(battery = 4))).isInstanceOf(Gate.Stop::class.java)
    }

    @Test fun `thermal status moves from warn to stop`() {
        assertThat(RecordGates.whileRecording(snap(thermal = Thermal.LIGHT))).isEqualTo(Gate.Ok)
        assertThat(RecordGates.whileRecording(snap(thermal = Thermal.MODERATE))).isInstanceOf(Gate.Warn::class.java)
        for (t in listOf(Thermal.SEVERE, Thermal.CRITICAL, Thermal.EMERGENCY, Thermal.SHUTDOWN)) {
            assertThat(RecordGates.whileRecording(snap(thermal = t))).isInstanceOf(Gate.Stop::class.java)
        }
    }

    @Test fun `a hot phone cannot start`() {
        assertThat(RecordGates.beforeStart(snap(thermal = Thermal.MODERATE), 100 * mib)).isInstanceOf(Gate.Warn::class.java)
        assertThat(RecordGates.beforeStart(snap(thermal = Thermal.SEVERE), 100 * mib)).isInstanceOf(Gate.Block::class.java)
    }

    @Test fun `blocks win over warnings`() {
        val s = snap(battery = 5, thermal = Thermal.MODERATE, free = 1 * mib)
        assertThat(RecordGates.beforeStart(s, 100 * mib)).isInstanceOf(Gate.Block::class.java)
    }

    @Test fun `wifi only waits for an unmetered network`() {
        val wifi = NetworkState(online = true, unmetered = true)
        val mobile = NetworkState(online = true, unmetered = false)
        val off = NetworkState(online = false, unmetered = false)
        assertThat(UploadNetwork.allowed(UploadNetworkPolicy.WifiOnly, wifi)).isTrue()
        assertThat(UploadNetwork.allowed(UploadNetworkPolicy.WifiOnly, mobile)).isFalse()
        assertThat(UploadNetwork.allowed(UploadNetworkPolicy.WifiOrMobile, mobile)).isTrue()
        assertThat(UploadNetwork.allowed(UploadNetworkPolicy.WifiOrMobile, off)).isFalse()
        assertThat(UploadNetwork.waitingMessage(UploadNetworkPolicy.WifiOnly, mobile)).contains("Wi-Fi")
        assertThat(UploadNetwork.waitingMessage(UploadNetworkPolicy.WifiOrMobile, off)).contains("No connection")
        assertThat(UploadNetwork.waitingMessage(UploadNetworkPolicy.WifiOnly, wifi)).isNull()
    }
}
