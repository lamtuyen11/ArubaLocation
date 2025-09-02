package com.bimfm.arubalocation.util

// PermissionUtils.kt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionUtils {

    /** Runtime permissions required for Wi-Fi RTT by API level. */
    fun hasWifiRttPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** System Location must be ON (GPS or Network provider). */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java)
        return try {
            (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) ||
                    (lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true)
        } catch (_: Exception) { false }
    }
}
