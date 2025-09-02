package com.bimfm.arubalocation.viewmodel

// RttViewModel.kt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.net.wifi.rtt.*
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bimfm.arubalocation.util.PermissionUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.*

data class RttReading(
    val bssid: MacAddress?,
    val distanceM: Double,
    val stdDevM: Double,
    val rssi: Int
)
data class AnchorEntry(val bssid: String, val x: Double, val y: Double)

data class Anchor(val x: Double, val y: Double) // meters in your floor coordinate system

data class UiState(
    val rttAvailable: Boolean = false,
    val rttAps: List<String> = emptyList(),        // BSSIDs of 802.11mc responders
    val readings: List<RttReading> = emptyList(),
    val phoneX: Double? = null,
    val phoneY: Double? = null,
    val error: String? = null
)

class RttViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** Fill this with your AP coordinates (meters) in a local 2D map frame. */
    private val apMap: MutableMap<String, Anchor> = mutableMapOf(
        // "aa:bb:cc:dd:ee:ff" to Anchor(xMeters, yMeters),
        // Example:
        // "7c:8b:ca:12:34:56" to Anchor( 0.0,  0.0),
        // "7c:8b:ca:12:34:57" to Anchor(10.0,  0.0),
        // "7c:8b:ca:12:34:58" to Anchor( 5.0,  8.0),
    )
    private val _anchors = MutableStateFlow<List<AnchorEntry>>(emptyList())
    val anchors = _anchors.asStateFlow()
    private fun emitAnchors() {
        _anchors.value = apMap.entries.map { (bssid, a) ->
            AnchorEntry(bssid, a.x, a.y)
        }.sortedBy { it.bssid }
    }
    private fun normalizeBssid(raw: String): String =
        raw.trim().lowercase()

    /** Add or update an anchor by BSSID. */
    fun upsertAnchor(bssidRaw: String, x: Double, y: Double) {
        val bssid = normalizeBssid(bssidRaw)
        if (bssid.isBlank()) {
            _state.value = _state.value.copy(error = "BSSID is empty.")
            return
        }
        apMap[bssid] = Anchor(x, y)
        emitAnchors()
        // Clear any previous error
        _state.value = _state.value.copy(error = null)
    }

    /** Remove an anchor by BSSID. */
    fun removeAnchor(bssidRaw: String) {
        val bssid = normalizeBssid(bssidRaw)
        apMap.remove(bssid)
        emitAnchors()
    }

    /** Optional: clear all anchors. */
    fun clearAnchors() {
        apMap.clear()
        emitAnchors()
    }


    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun refreshRttAps(context: Context) {
        if (!PermissionUtils.hasWifiRttPermissions(context)) {
            _state.value = _state.value.copy(error = "Missing permission (Location ≤32 or Nearby Wi-Fi ≥33).")
            return
        }
        if (!PermissionUtils.isLocationEnabled(context)) {
            _state.value = _state.value.copy(error = "Turn ON Location Services to use Wi-Fi RTT.")
            return
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val responders = wifi.scanResults.filter { it.is80211mcResponder }
        _state.value = _state.value.copy(
            rttAps = responders.map { it.BSSID.lowercase() },
            error = if (responders.isEmpty()) "No RTT-capable APs found. Ensure AP-505 FTM is enabled." else null
        )
    }

    fun updateRttAvailability(isAvailable: Boolean) {
        _state.value = _state.value.copy(rttAvailable = isAvailable)
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun startRanging(context: Context) {
        val rtt = context.getSystemService(WifiRttManager::class.java)

        // Availability & permissions
        if (!PermissionUtils.hasWifiRttPermissions(context)) {
            _state.value = _state.value.copy(error = "Missing permission (Location ≤32 or Nearby Wi-Fi ≥33).")
            return
        }
        if (!PermissionUtils.isLocationEnabled(context)) {
            _state.value = _state.value.copy(error = "Turn ON Location Services to use Wi-Fi RTT.")
            return
        }
        if (!_state.value.rttAvailable) {
            _state.value = _state.value.copy(error = "RTT unavailable (Wi-Fi/Location off, or hardware not supported).")
            return
        }

        // Pick responders
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val responders = wifi.scanResults.filter { it.is80211mcResponder }.take(10)
        if (responders.size < 3) {
            _state.value = _state.value.copy(error = "Need ≥ 3 RTT APs for 2D position (found ${responders.size}).")
            return
        }

        val request = RangingRequest.Builder().apply {
            responders.forEach { addAccessPoint(it) }
        }.build()

        rtt.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
            override fun onRangingResults(results: MutableList<RangingResult>) {
                val ok = results.filter { it.status == RangingResult.STATUS_SUCCESS }
                val readings = ok.map {
                    RttReading(
                        bssid = it.macAddress,
                        distanceM = it.distanceMm / 1000.0,
                        stdDevM = it.distanceStdDevMm / 1000.0,
                        rssi = it.rssi
                    )
                }
                _state.value = _state.value.copy(readings = readings, error = null)

                val anchors = readings.mapNotNull { r ->
                    apMap[r.bssid.toString().lowercase()]?.let { a ->
                        Triple(a, r.distanceM, r.bssid.toString())
                    }
                }

                if (anchors.size >= 3) {
                    val pos = solve2DLeastSquares(anchors.map { (a, d, _) -> Triple(a.x, a.y, d) })
                    _state.value = _state.value.copy(phoneX = pos.first, phoneY = pos.second)
                } else {
                    _state.value = _state.value.copy(
                        error = "Have ${anchors.size} anchors with coordinates; define AP coordinates (apMap)."
                    )
                }
            }

            override fun onRangingFailure(code: Int) {
                _state.value = _state.value.copy(error = "Ranging failed: code $code")
            }
        })
    }

    /** Linearized 2D multilateration (least squares). Triples are (ax, ay, d). */
    private fun solve2DLeastSquares(anchors: List<Triple<Double, Double, Double>>): Pair<Double, Double> {
        require(anchors.size >= 3)
        val (x0, y0, d0) = anchors[0]

        // Build A and b from equations: (x-xi)^2+(y-yi)^2 = di^2  => linearized against anchor 0
        var a00 = 0.0; var a01 = 0.0; var a11 = 0.0
        var b0 = 0.0;  var b1 = 0.0
        for (i in 1 until anchors.size) {
            val (xi, yi, di) = anchors[i]
            val Ai0 = 2.0 * (xi - x0)
            val Ai1 = 2.0 * (yi - y0)
            val bi  = (d0*d0 - di*di) + (xi*xi - x0*x0) + (yi*yi - y0*y0)
            a00 += Ai0*Ai0
            a01 += Ai0*Ai1
            a11 += Ai1*Ai1
            b0  += Ai0*bi
            b1  += Ai1*bi
        }
        val det = a00*a11 - a01*a01
        val x = ( b0*a11 - a01*b1) / det
        val y = (-b0*a01 + a00*b1) / det
        return Pair(x, y)
    }
}
