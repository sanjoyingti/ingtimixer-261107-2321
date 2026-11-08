package com.ingti.mixer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SmallMixer(vm: StudioViewModel) {
    Column {
        vm.studio.project.tracks.forEachIndexed { index, track ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.name,
                    modifier = Modifier.width(80.dp),
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = track.gain,
                    onValueChange = { track.gain = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(
                    text = "%.0f%%".format(track.gain * 100),
                    modifier = Modifier.width(40.dp),
                    style = MaterialTheme.typography.labelSmall
                )
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
