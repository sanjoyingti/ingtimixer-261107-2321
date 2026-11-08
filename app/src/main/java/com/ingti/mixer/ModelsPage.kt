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
    Column(Modifier.padding(16.dp)) {
        Text("Models", style = MaterialTheme.typography.headlineSmall)
        Text("Download separation models for offline use.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("HTDemucs (2-stem)", style = MaterialTheme.typography.titleMedium)
                        Text("Vocals + Instrumental separation. ~150 MB", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                    if (vm.modelReady) {
                        Text("Ready", color = MaterialTheme.colorScheme.primary)
                    } else if (vm.busy) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(vm.status, color = MaterialTheme.colorScheme.primary)
                            LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.width(120.dp))
                        }
                    } else {
                        Button(onClick = { vm.downloadModel() }) {
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}
