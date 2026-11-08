package com.ingti.mixer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ModelsPage(vm: StudioViewModel) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Models", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Download separation models. Each model provides different quality/speed trade-offs.",
            color = androidx.compose.ui.graphics.Color.Gray,
            style = MaterialTheme.typography.bodySmall
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        
        val ctx = vm.getApplication()
        val modelNames = listOf("Fast (2s)", "Balanced (6s)", "Studio (12s)")
        
        modelNames.forEachIndexed { index, name ->
            val model = Models(ctx, index)
            val installed = remember { mutableStateOf(model.installed()) }
            var downloading by remember { mutableStateOf(false) }
            var progress by remember { mutableStateOf(0f) }
            
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${model.entry.getString("description")} • ${model.entry.getLong("bytes") / 1024 / 1024} MB",
                                color = androidx.compose.ui.graphics.Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (installed.value) {
                            Text("Installed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        } else if (downloading) {
                            Column(Modifier.width(120.dp)) {
                                LinearProgressIndicator(progress = progress)
                                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Button(onClick = {
                                downloading = true
                                vm.ioScope.launch {
                                    try {
                                        model.download({ progress = it })
                                        installed.value = true
                                    } catch (e: Exception) {
                                        vm.error = "Download failed: ${e.message}"
                                    }
                                    downloading = false
                                }
                            }) {
                                Text("Download")
                            }
                        }
                    }
                    if (downloading) {
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        }
    }
}
