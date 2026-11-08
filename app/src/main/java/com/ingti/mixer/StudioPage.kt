package com.ingti.mixer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StudioPage(
    vm: StudioViewModel,
    onAddTrack: () -> Unit,
    onExport: (String) -> Unit,
    onSaveProject: () -> Unit,
    onOpenProject: () -> Unit,
    onTuner: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Multitrack Studio", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Record, mix and export multi-track projects. Add audio tracks from your device.",
            color = androidx.compose.ui.graphics.Color.Gray,
            style = MaterialTheme.typography.bodySmall
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        
        // Track list
        Text("Tracks", style = MaterialTheme.typography.titleMedium)
        if (vm.studio.project.tracks.isEmpty()) {
            Text("No tracks yet. Tap + to add audio files.", color = androidx.compose.ui.graphics.Color.Gray)
        } else {
            LazyColumn {
                items(vm.studio.project.tracks) { track ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(track.name, style = MaterialTheme.typography.bodyMedium)
                                Text("Gain: ${(track.gain * 100).toInt()}% • ${if (track.mute) "Muted" else "Active"}", 
                                    color = androidx.compose.ui.graphics.Color.Gray,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                IconButton(onClick = { track.mute = !track.mute }) {
                                    Icon(
                                        imageVector = if (track.mute) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                        contentDescription = if (track.mute) "Unmute" else "Mute"
                                    )
                                }
                                IconButton(onClick = { track.solo = !track.solo }) {
                                    Icon(
                                        imageVector = Icons.Default.Headphones,
                                        contentDescription = if (track.solo) "Unsolo" else "Solo"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        
        // Controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddTrack) {
                Text("+ Add Track")
            }
            OutlinedButton(onClick = onTuner) {
                Text("🎤 Tuner")
            }
        }
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onExport("Mix") }) {
                Text("Export Mix")
            }
            OutlinedButton(onClick = { onExport("Stems") }) {
                Text("Export Stems")
            }
        }
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSaveProject) {
                Text("Save Project")
            }
            OutlinedButton(onClick = onOpenProject) {
                Text("Open Project")
            }
        }
        
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        
        // Tempo
        Text("Tempo: ${vm.studio.project.tempo.toInt()} BPM", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = vm.studio.project.tempo,
            onValueChange = { vm.studio.project.tempo = it },
            valueRange = 60f..200f,
            modifier = Modifier.fillMaxWidth()
        )
        
        // Time signature
        Row {
            Text("Time Signature: ", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = vm.studio.project.timeSig,
                onValueChange = { vm.studio.project.timeSig = it },
                modifier = Modifier.width(80.dp),
                label = { Text("4/4") }
            )
        }
    }
}
