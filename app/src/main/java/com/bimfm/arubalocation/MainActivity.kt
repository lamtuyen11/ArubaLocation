package com.bimfm.arubalocation

// MainActivity.kt

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bimfm.arubalocation.sensor.Pdr
import com.bimfm.arubalocation.viewmodel.FusionViewModel
import com.bimfm.arubalocation.viewmodel.RttViewModel
import androidx.compose.foundation.lazy.items


class MainActivity : ComponentActivity() {

    private val vm: RttViewModel by viewModels()
    val fusionVm: FusionViewModel by viewModels()


    private val rttStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED) {
                val rtt = getSystemService(WifiRttManager::class.java)
                vm.updateRttAvailability(rtt.isAvailable)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rtt = getSystemService(WifiRttManager::class.java)
        vm.updateRttAvailability(rtt.isAvailable)

        registerReceiver(
            rttStateReceiver,
            IntentFilter(WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED),
            RECEIVER_EXPORTED
        )

        setContent {
            MaterialTheme {
                RttScreen(vm,fusionVm)
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(rttStateReceiver)
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RttScreen(vm: RttViewModel, fusionVm: FusionViewModel) {
    val ctx = LocalContext.current
    val ui by vm.state.collectAsState()

    // ---- Permissions: Wi-Fi + Activity recognition (for PDR) ----
    val wifiPerms = remember {
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val needsActivityReco = remember { Build.VERSION.SDK_INT >= 29 }

    val wifiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshRttAps(ctx) }

    val activityPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op; PDR starts after grant via lifecycle */ }

    fun requestAll() {
        wifiPermLauncher.launch(wifiPerms)
        if (needsActivityReco) {
            activityPermLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    // ---- PDR: create, wire to FusionVM, start/stop with lifecycle ----
    val pdr = remember { com.bimfm.arubalocation.sensor.Pdr(ctx) }
    LaunchedEffect(Unit) {
        pdr.onDisplacement { dx, dy, tNanos ->
            fusionVm.onStep(dx, dy, tNanos)
        }
    }
    DisposableEffect(Unit) {
        pdr.start()
        onDispose { pdr.stop() }
    }

    // ---- Push RTT fix -> FusionVM whenever trilateration updates ----
    LaunchedEffect(ui.phoneX, ui.phoneY) {
        val x = ui.phoneX
        val y = ui.phoneY
        if (x != null && y != null) {
            val sigmaRtt = (ui.readings.map { it.stdDevM }.average().takeIf { !it.isNaN() } ?: 1.3) * 1.5
            fusionVm.onRttFix(x, y, sigmaRtt.coerceIn(1.0, 6.0))
        }
    }

    // ---- Observe fused position for drawing ----
    val fused by fusionVm.fusedFix.collectAsState()

    // ---- Initial Wi-Fi scan once ----
    LaunchedEffect(Unit) { vm.refreshRttAps(ctx) }

    val stroke = with(LocalDensity.current) { 1.dp.toPx() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Wi-Fi RTT Aruba Location") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Anchor editor
            item {
                AnchorEditor(
                    vm = vm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }

            // Status + actions
            item {
                Text("RTT available: ${ui.rttAvailable}", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { requestAll() }) { Text("Grant Permissions") }
                    Button(onClick = { vm.refreshRttAps(ctx) }) { Text("Scan RTT APs") }
                    Button(onClick = { vm.startRanging(ctx) }) { Text("Range") }
                }
                ui.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("⚠️ $it", color = MaterialTheme.colorScheme.error)
                }
            }

            // RTT-capable APs list
            if (ui.rttAps.isNotEmpty()) {
                item { Text("RTT-capable APs (BSSID):") }
                items(ui.rttAps) { bssid ->
                    Text("• $bssid")
                }
            }

            // Readings list
            if (ui.readings.isNotEmpty()) {
                item { Text("Readings:") }
                items(ui.readings) { r ->
                    Text("• ${r.bssid}: ${"%.2f".format(r.distanceM)} m (±${"%.2f".format(r.stdDevM)}), RSSI ${r.rssi}")
                }
            }

            // Canvas with fused position (fixed height bounds)
            item {
                val scale = 30f // 1 m = 30 px
                Card(Modifier.fillMaxWidth().height(240.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f

                            drawLine(
                                color = Color.LightGray,
                                start = Offset(0f, cy),
                                end = Offset(size.width, cy),
                                strokeWidth = stroke
                            )
                            drawLine(
                                color = Color.LightGray,
                                start = Offset(cx, 0f),
                                end = Offset(cx, size.height),
                                strokeWidth = stroke
                            )

                            fused?.let { fix ->
                                val px = cx + (fix.x.toFloat() * scale)
                                val py = cy - (fix.y.toFloat() * scale)
                                drawCircle(
                                    color = Color.Red,
                                    radius = 8f,
                                    center = Offset(px, py)
                                )
                            }
                        }
                    }
                }
            }

            // Summary
            item {
                val phone = ui.phoneX?.let { x -> ui.phoneY?.let { y -> x to y } }
                Column {
                    Text(
                        text = fused?.let {
                            "Fused: x=${"%.2f".format(it.x)} m, y=${"%.2f".format(it.y)} m  (±${"%.1f".format(it.sigma)} m)"
                        } ?: "Fused: —",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = phone?.let { "RTT-only: x=${"%.2f".format(it.first)} m, y=${"%.2f".format(it.second)} m" }
                            ?: "RTT-only: —",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

//}


