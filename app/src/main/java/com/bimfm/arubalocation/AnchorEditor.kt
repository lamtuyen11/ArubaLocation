package com.bimfm.arubalocation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bimfm.arubalocation.viewmodel.AnchorEntry
import com.bimfm.arubalocation.viewmodel.RttViewModel
import androidx.compose.foundation.lazy.items


@Composable
fun AnchorEditor(
    vm: RttViewModel,
    modifier: Modifier = Modifier
) {
    val anchors by vm.anchors.collectAsState()
    var bssid by remember { mutableStateOf("") }
    var xText by remember { mutableStateOf("") }
    var yText by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        Text("Anchor Coordinates", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))

        // Inputs
        OutlinedTextField(
            value = bssid,
            onValueChange = { bssid = it },
            label = { Text("BSSID (aa:bb:cc:dd:ee:ff)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = xText,
                onValueChange = { xText = it },
                label = { Text("X (meters)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = yText,
                onValueChange = { yText = it },
                label = { Text("Y (meters)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val x = xText.toDoubleOrNull()
                val y = yText.toDoubleOrNull()
                if (x == null || y == null) {
                    // surface the error via your UiState if you prefer
                    return@Button
                }
                vm.upsertAnchor(bssid, x, y)
                // optional: clear fields after add/update
                // bssid = ""; xText = ""; yText = ""
            }) {
                Text("Add / Update")
            }

            OutlinedButton(onClick = {
                vm.removeAnchor(bssid)
            }) {
                Text("Delete by BSSID")
            }

            OutlinedButton(onClick = {
                vm.clearAnchors()
            }) {
                Text("Clear All")
            }
        }

        Spacer(Modifier.height(12.dp))

        // Current anchors list
        Text("Current Anchors (${anchors.size})", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))

        if (anchors.isEmpty()) {
            Text("— No anchors yet —", color = MaterialTheme.colorScheme.outline)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    items = anchors,
                    key = { it.bssid }   // optional but recommended for stable IDs
                ) { a ->
                    AnchorRow(
                        a,
                        onDelete = { vm.removeAnchor(a.bssid) },
                        onFill = {
                            bssid = a.bssid
                            xText = a.x.toString()
                            yText = a.y.toString()
                        }
                    )
                }
            }

        }
    }
}

@Composable
private fun AnchorRow(
    a: AnchorEntry,
    onDelete: () -> Unit,
    onFill: () -> Unit
) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(a.bssid, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("x=${"%.2f".format(a.x)} m, y=${"%.2f".format(a.y)} m", color = MaterialTheme.colorScheme.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onFill) { Text("Fill") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
