package com.ingti.mixer

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: StudioViewModel by viewModels()
    private var lyricSrt = false
    private var audioKind = "Mix"
    private val audio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm::importAudio)
        }
    private val lyrics =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm::importLyrics)
        }
    private val exportLyrics =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            it?.let { vm.exportLyrics(it, lyricSrt) }
        }
    private val exportAudio =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
            it?.let { vm.exportAudio(it, audioKind) }
        }
    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) vm.startRecording()
            else vm.error = "Microphone access was denied. Playback is still available."
        }

    private var studioKind = "Full mix"
    private val trackPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm.studio::addTrack)
        }
    private val studioExport =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) {
            it?.let { vm.studio.export(it, studioKind) }
        }
    private val projectSaver =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
            it?.let(vm.studio::saveProject)
        }
    private val projectPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm.studio::openProject)
        }
    private val tunerPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) vm.studio.startTuner() else vm.error = "Microphone permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFFFF4055),
                        background = Color(0xFF101118),
                        surface = Color(0xFF191B25),
                    )
            ) {
                App()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.studio.stopTuner()
        if (vm.record) vm.stopRecording()
        vm.player.pause()
    }

    @Composable
    private fun App() {
        val songs by vm.songs.collectAsState()
        var mixer by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(
                            "ϟ INGTI MIXER",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            "YOUR MUSIC. YOUR STAGE.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                        )
                    }
                    Text("0.3", color = Color.Gray)
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                            "Player",
                            "My Songs",
                            "Lyrics",
                            "Models",
                            "Separate",
                            "Multitrack",
                            "Learning",
                            "Guitar",
                            "Effects",
                            "Export",
                            "Studio",
                        )
                        .forEach { tab ->
                            TextButton(onClick = { vm.screen = tab }) {
                                Text(
                                    tab,
                                    color =
                                        if (vm.screen == tab) MaterialTheme.colorScheme.primary
                                        else Color.LightGray,
                                )
                            }
                        }
                }
                if (vm.error.isNotEmpty())
                    Card {
                        Row(Modifier.padding(8.dp)) {
                            Text(vm.error, Modifier.weight(1f), color = Color(0xFFFF8A96))
                            TextButton(onClick = { vm.error = "" }) { Text("Dismiss") }
                        }
                    }
                if (vm.busy) {
                    Text(vm.status)
                    LinearProgressIndicator(
                        progress = { vm.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = vm::cancel) { Text("Cancel") }
                }
                when (vm.screen) {
                    "My Songs" -> {
                        Button(
                            enabled = !vm.busy && !vm.record,
                            onClick = { audio.launch(arrayOf("audio/*")) },
                        ) {
                            Text("Import music from phone")
                        }
                        LazyColumn {
                            items(songs, key = { it.uri }) { s ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(Modifier.padding(12.dp)) {
                                        Column(
                                            Modifier.weight(1f).clickable(
                                                enabled = !vm.busy && !vm.record
                                            ) {
                                                vm.open(s)
                                                vm.screen = "Player"
                                            }
                                        ) {
                                            Text(s.title)
                                            Text(
                                                if (s.stemsDir.isEmpty()) "Original audio"
                                                else "Separated audio",
                                                color = Color.Gray,
                                            )
                                        }
                                        TextButton(onClick = { vm.favorite(s) }) {
                                            Text(if (s.favorite) "★" else "☆")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "Lyrics" -> LyricsEditor()
                    "Models" -> ModelsPage(vm)
                    "Separate",
                    "Multitrack",
                    "Learning",
                    "Guitar",
                    "Effects",
                    "Export" ->
                        StudioPage(
                            vm,
                            { trackPicker.launch(arrayOf("audio/*")) },
                            { kind ->
                                studioKind = kind
                                studioExport.launch(
                                    "Ingti-${kind.replace(" ", "-")}.${vm.studio.format.lowercase()}"
                                )
                            },
                            { projectSaver.launch("Ingti-project.ingti") },
                            { projectPicker.launch(arrayOf("*/*")) },
                            { tunerPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        )
                    "Studio" -> Studio()
                    else -> {
                        Text(
                            vm.current?.title ?: "Choose your first song",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                        )
                        if (!vm.current?.artist.isNullOrBlank())
                            Text(vm.current!!.artist, color = Color.Gray)
                        if (vm.current == null)
                            Button(onClick = { audio.launch(arrayOf("audio/*")) }) {
                                Text("Import music")
                            }
                        Slider(
                            value =
                                vm.position
                                    .toFloat()
                                    .coerceIn(0f, vm.duration.coerceAtLeast(1).toFloat()),
                            onValueChange = { vm.seek(it.toLong()) },
                            valueRange = 0f..vm.duration.coerceAtLeast(1).toFloat(),
                            enabled = !vm.record,
                        )
                        Text(
                            "${clock(vm.position)} / ${clock(vm.duration)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.seek(vm.position - 10000) }) { Text("−10s") }
                            Button(
                                enabled = vm.current != null && !vm.record,
                                onClick = vm::togglePlay,
                            ) {
                                Text(if (vm.playing) "Pause" else "Play")
                            }
                            TextButton(onClick = { vm.seek(vm.position + 10000) }) { Text("+10s") }
                        }
                        TextButton(onClick = { mixer = !mixer }) {
                            Text(if (mixer) "▴ Instrument Mixer" else "▾ Instrument Mixer")
                        }
                        if (mixer) {
                            if (vm.hasStems() || vm.studio.project.tracks.isNotEmpty()) Mixer()
                            else
                                Text(
                                    "Separate this song in Studio to enable stem controls.",
                                    color = Color.Gray,
                                )
                        }
                        Row {
                            TextButton(
                                enabled = vm.current != null,
                                onClick = { lyrics.launch(arrayOf("*/*")) },
                            ) {
                                Text("Add Lyrics / Subtitles")
                            }
                            TextButton(onClick = { vm.screen = "Lyrics" }) { Text("Edit") }
                        }
                        LyricDisplay(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled =
                                    (vm.hasStems() || vm.studio.project.tracks.isNotEmpty()) &&
                                        !vm.record &&
                                        !vm.busy,
                                onClick = vm::karaoke,
                            ) {
                                Text(if (vm.karaoke) "Karaoke ON" else "Karaoke")
                            }
                            Button(
                                enabled = !vm.busy && !vm.finalizing,
                                onClick = {
                                    vm.studio.stopTuner()
                                    if (vm.record) vm.stopRecording()
                                    else microphone.launch(Manifest.permission.RECORD_AUDIO)
                                },
                            ) {
                                Text(
                                    if (vm.finalizing) "Saving take…"
                                    else if (vm.record) "Stop recording" else "● Record"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LyricDisplay(modifier: Modifier = Modifier) {
        var visible by remember { mutableStateOf(true) }
        val s = vm.current
        val cues =
            remember(s?.lyrics) {
                runCatching { Lyrics.parse(s?.lyrics ?: "") }.getOrDefault(emptyList())
            }
        val time = vm.position - (s?.offset ?: 0)
        val active = Lyrics.current(cues, time)
        val list = rememberLazyListState()
        LaunchedEffect(active) { if (active >= 0) list.animateScrollToItem(active) }
        Column(modifier) {
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Hide lyrics" else "Show lyrics")
            }
            if (visible)
                LazyColumn(state = list) {
                    if (cues.isEmpty())
                        item {
                            Text("Add LRC, SRT, VTT or TXT from your phone.", color = Color.Gray)
                        }
                    itemsIndexed(cues) { i, c ->
                        Text(
                            buildAnnotatedString {
                                if (c.words.isEmpty()) append(c.text)
                                else
                                    c.words.forEach { w ->
                                        withStyle(
                                            SpanStyle(
                                                color =
                                                    if (i == active && time >= w.time)
                                                        Color(0xFFFF4055)
                                                    else Color.LightGray
                                            )
                                        ) {
                                            append(w.text)
                                        }
                                    }
                            },
                            color = if (i == active) Color(0xFFFF4055) else Color.LightGray,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
        }
    }

    @Composable
    private fun Mixer() {
        if (vm.studio.project.tracks.isNotEmpty()) {
            SmallMixer(vm)
            return
        }
        Column {
            listOf("Vocals", "Instrumental").forEachIndexed { i, name ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.width(85.dp)) {
                        Text(name)
                        StemWaveform(
                            vm.current?.stemsDir ?: "",
                            if (i == 0) "vocals.pcm" else "instrumental.pcm",
                        )
                    }
                    Slider(
                        if (i == 0) vm.gain0 else vm.gain1,
                        { if (i == 0) vm.gain0 = it else vm.gain1 = it },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { if (i == 0) vm.mute0 = !vm.mute0 else vm.mute1 = !vm.mute1 }
                    ) {
                        Text(if (if (i == 0) vm.mute0 else vm.mute1) "Unmute" else "Mute")
                    }
                    TextButton(onClick = { vm.solo = if (vm.solo == i) -1 else i }) {
                        Text(if (vm.solo == i) "All" else "Solo")
                    }
                }
            }
            Button(enabled = !vm.busy && !vm.record, onClick = vm::previewMix) {
                Text("Apply & preview mix")
            }
        }
    }

    @Composable
    private fun LyricsEditor() {
        val song = vm.current
        if (song == null) {
            Text("Import a song first.")
            return
        }
        var text by remember(song.uri, song.lyrics) { mutableStateOf(song.lyrics) }
        var offset by remember(song.uri) { mutableStateOf(song.offset.toString()) }
        var next by remember(song.uri) { mutableIntStateOf(0) }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Lyrics & subtitles", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Edit LRC timestamps or SRT/VTT start and end times directly. For TXT, play the song and tap Stamp next line at each lyric.",
                color = Color.Gray,
            )
            OutlinedTextField(
                text,
                { text = it },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                label = { Text("Lyrics and timestamps") },
            )
            Row {
                Button(
                    onClick = {
                        runCatching { Lyrics.parse(text) }
                            .onSuccess { vm.saveLyrics(text) }
                            .onFailure { vm.error = it.message ?: "Invalid lyrics" }
                    }
                ) {
                    Text("Save")
                }
                TextButton(
                    onClick = {
                        text = ""
                        vm.saveLyrics("")
                        next = 0
                    }
                ) {
                    Text("Remove")
                }
                TextButton(onClick = { lyrics.launch(arrayOf("*/*")) }) { Text("Replace") }
            }
            Row {
                TextButton(enabled = !vm.record, onClick = vm::togglePlay) {
                    Text(if (vm.playing) "Pause" else "Play")
                }
                TextButton(
                    onClick = {
                        val lines = text.lines().filter { it.isNotBlank() }.toMutableList()
                        if (next < lines.size) {
                            lines[next] =
                                "[${Lyrics.format(vm.position-song.offset)}]" +
                                    lines[next].replace(Regex("^\\[[^]]+\\]"), "")
                            text = lines.joinToString("\n")
                            next++
                        }
                    }
                ) {
                    Text("Stamp line ${next+1}")
                }
                TextButton(onClick = { next = 0 }) { Text("Reset cursor") }
            }
            OutlinedTextField(
                offset,
                {
                    offset = it
                    it.toLongOrNull()?.let(vm::offset)
                },
                label = { Text("Global offset in milliseconds") },
            )
            Row {
                Button(
                    onClick = {
                        lyricSrt = false
                        exportLyrics.launch("lyrics.lrc")
                    }
                ) {
                    Text("Export LRC")
                }
                TextButton(
                    onClick = {
                        lyricSrt = true
                        exportLyrics.launch("lyrics.srt")
                    }
                ) {
                    Text("Export SRT")
                }
            }
        }
    }

    @Composable
    private fun Studio() {
        var mode by remember { mutableIntStateOf(1) }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Separation & practice", style = MaterialTheme.typography.headlineSmall)
            Row {
                listOf("Fast", "Balanced", "Studio").forEachIndexed { i, s ->
                    FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(s) })
                }
            }
            Text(
                "6 / 12 / 24-second windows; longer windows use more memory. All use the same two-stem model.",
                color = Color.Gray,
            )
            Button(
                enabled = vm.current != null && vm.modelReady && !vm.busy && !vm.record,
                onClick = { vm.separate(mode) },
            ) {
                Text(if (vm.hasStems()) "Reprocess stems" else "Separate song")
            }
            if (!vm.modelReady)
                TextButton(onClick = { vm.screen = "Models" }) { Text("Download a model first") }
            HorizontalDivider()
            Text("Tempo ${"%.2f".format(vm.speed)}× • pitch ${vm.pitch.toInt()} semitones")
            Slider(
                vm.speed,
                { vm.tempo(it, vm.pitch) },
                valueRange = 0.5f..1.5f,
                enabled = !vm.record,
            )
            Slider(
                vm.pitch,
                { vm.tempo(vm.speed, it.toInt().toFloat()) },
                valueRange = -12f..12f,
                steps = 23,
                enabled = !vm.record,
            )
            Row {
                TextButton(onClick = { vm.loopA = vm.position }) { Text("Set A") }
                TextButton(onClick = { if (vm.position > vm.loopA) vm.loopB = vm.position }) {
                    Text("Set B")
                }
                TextButton(
                    onClick = {
                        vm.loopA = -1
                        vm.loopB = -1
                    }
                ) {
                    Text("Clear loop")
                }
                TextButton(onClick = { vm.tempo(1f, 0f) }) { Text("Reset") }
            }
            Text("A ${clock(vm.loopA)} · B ${clock(vm.loopB)}", color = Color.Gray)
            HorizontalDivider()
            Text("Export", style = MaterialTheme.typography.titleLarge)
            listOf("Mix", "Vocals", "Instrumental", "Recording", "Karaoke take").forEach { kind ->
                TextButton(
                    enabled =
                        !vm.busy &&
                            !vm.record &&
                            !vm.finalizing &&
                            (if (kind == "Recording") vm.recordingFile != null
                            else if (kind == "Karaoke take") vm.mixedRecording != null
                            else vm.hasStems()),
                    onClick = {
                        audioKind = kind
                        exportAudio.launch("Ingti-$kind.wav")
                    },
                ) {
                    Text("Save $kind as WAV")
                }
            }
            OutlinedTextField(
                vm.latencyMs,
                { vm.latencyMs = it },
                label = { Text("Recording latency correction (ms)") },
                enabled = !vm.record,
            )
            if (vm.recordingFile != null)
                Button(onClick = vm::previewRecording) { Text("Preview microphone take") }
            Text(
                "Karaoke recordings include the selected backing mix and preserve the microphone-only take. Use headphones to avoid speaker bleed. Open Effects, Export and Separate for the advanced tools.",
                color = Color.Gray,
            )
        }
    }

    @Composable
    private fun StemWaveform(dir: String, name: String) {
        var peaks by remember(dir, name) { mutableStateOf(FloatArray(64)) }
        LaunchedEffect(dir, name, vm.busy) {
            if (dir.isNotEmpty() && !vm.busy) {
                peaks =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val file = java.io.File(dir, name)
                        val result = FloatArray(64)
                        if (file.exists())
                            java.io.RandomAccessFile(file, "r").use { f ->
                                val total = f.length() / 4
                                var at = 0L
                                while (at < total) {
                                    val a =
                                        AudioFiles.floats(
                                            f,
                                            at,
                                            kotlin.math.min(8192L, total - at).toInt(),
                                        )
                                    for (i in a[0].indices) {
                                        val bucket =
                                            ((at + i) * 64 / total.coerceAtLeast(1))
                                                .toInt()
                                                .coerceIn(0, 63)
                                        result[bucket] =
                                            kotlin.math.max(
                                                result[bucket],
                                                kotlin.math.sqrt(a[0][i]
