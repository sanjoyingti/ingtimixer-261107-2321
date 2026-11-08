package com.ingti.mixer

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.microsoft.onnxruntime.OrtEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class StudioViewModel(private val context: Context) : ViewModel() {
    // Database
    private val library = Room.databaseBuilder(context, Library::class.java, "library.db")
        .fallbackToDestructiveMigration()
        .build()
    val songs = library.songs().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    // UI State
    var screen by mutableStateOf("Player")
    var error by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var finalizing by mutableStateOf(false)
    
    // Current song
    var current: Song? by mutableStateOf(null)
        private set
    
    // Playback
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var playing by mutableStateOf(false)
    var gain0 by mutableStateOf(1f)
    var gain1 by mutableStateOf(1f)
    var mute0 by mutableStateOf(false)
    var mute1 by mutableStateOf(false)
    var solo by mutableStateOf(-1)
    var karaoke by mutableStateOf(false)
    var loopA = -1L
    var loopB = -1L
    var speed by mutableStateOf(1f)
    var pitch by mutableStateOf(0f)
    var latencyMs by mutableStateOf("0")
    
    // Recording
    var record by mutableStateOf(false)
    var recordingFile: File? by mutableStateOf(null)
    var mixedRecording: File? by mutableStateOf(null)
    
    // Separation
    var modelReady by mutableStateOf(false)
    private var demucs: Demucs? = null
    
    // Audio engine
    private val player = ExoPlayerWrapper(context)
    private var recordingJob: Job? = null
    private var separationJob: Job? = null
    private var mixPreviewJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)
    private val progressValue = AtomicLong(0)
    private val totalValue = AtomicLong(1)

    // Studio
    val studio = StudioEngine(context, this)

    init {
        checkModel()
        observePlayback()
    }

    private fun checkModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelDir = File(context.filesDir, "models")
            val model = File(modelDir, "demucs.onnx")
            modelReady = model.exists()
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            while (true) {
                delay(200)
                val pos = player.position
                position = pos
                duration = player.duration
                if (playing && loopB > loopA && pos >= loopB) {
                    player.seek(loopA)
                }
            }
        }
    }

    // Navigation & Song Management
    fun open(song: Song) {
        current = song
        player.prepare(song)
        position = song.offset
        player.seek(song.offset)
    }

    fun favorite(song: Song) {
        viewModelScope.launch {
            library.songs().save(song.copy(favorite = !song.favorite))
        }
    }

    fun importAudio(uri: Uri) {
        busy = true
        status = "Importing..."
        progress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val destDir = File(context.filesDir, "songs")
                destDir.mkdirs()
                val dest = File(destDir, "${System.currentTimeMillis()}.mpc")
                copyUriToFile(uri, dest) { progress = it }
                val rate = AudioFiles.decode(context, uri, dest, { progress = it })
                val title = getTitleFromUri(uri)
                val song = Song(
                    uri = dest.absolutePath,
                    title = title,
                    artist = "",
                )
                library.songs().save(song)
                open(song)
                error = "Imported: $title"
            } catch (e: Exception) {
                error = "Import failed: ${e.message}"
            } finally {
                busy = false
                progress = 0f
            }
        }
    }

    fun importLyrics(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val text = input.readText()
                    saveLyrics(text)
                }
            } catch (e: Exception) {
                error = "Failed to import lyrics: ${e.message}"
            }
        }
    }

    fun saveLyrics(text: String) {
        current?.let { song ->
            val updated = song.copy(lyrics = text)
            library.songs().save(updated)
            current = updated
        }
    }

    fun offset(ms: Long) {
        current?.let { song ->
            val updated = song.copy(offset = ms)
            library.songs().save(updated)
            current = updated
        }
    }

    fun exportLyrics(uri: Uri, srt: Boolean) {
        current?.let { song ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val cues = Lyrics.parse(song.lyrics)
                    val output = Lyrics.export(cues, srt, song.offset)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(output.toByteArray()) }
                    error = "Exported lyrics"
                } catch (e: Exception) {
                    error = "Export failed: ${e.message}"
                }
            }
        }
    }

    // Playback Controls
    fun togglePlay() {
        if (playing) player.pause() else player.play()
        playing = player.playing
    }

    fun seek(ms: Long) {
        position = ms
        player.seek(ms)
    }

    fun tempo(newSpeed: Float, newPitch: Float) {
        speed = newSpeed
        pitch = newPitch
        player.setSpeed(speed)
        player.setPitch(pitch)
    }

    // Mixer Preview
    fun previewMix() {
        current?.let { song ->
            if (song.stemsDir.isEmpty() && studio.project.tracks.isEmpty()) return
            busy = true
            status = "Rendering preview..."
            progress = 0f
            cancelFlag.set(false)
            mixPreviewJob?.cancel()
            mixPreviewJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val mixFile = File(context.cacheDir, "preview_mix.pcm")
                    if (song.stemsDir.isNotEmpty()) {
                        val stemsDir = File(song.stemsDir)
                        val gains = listOf(gain0, gain1)
                        val files = listOf(
                            File(stemsDir, "vocals.pcm"),
                            File(stemsDir, "instrumental.pcm"),
                        ).filter { it.exists() }
                        val validGains = files.mapIndexed { i, _ -> gains[i] }
                        AudioFiles.mix(files, validGains, mixFile, { progress = it })
                    } else {
                        studio.renderMix(mixFile, { progress = it })
                    }
                    player.preparePreview(mixFile, song.stemsDir.isNotEmpty())
                    player.play()
                    playing = true
                    error = "Preview ready"
                } catch (e: Exception) {
                    error = "Preview failed: ${e.message}"
                } finally {
                    busy = false
                    progress = 0f
                }
            }
        }
    }

    // Separation
    fun separate(mode: Int) {
        current?.let { song ->
            busy = true
            status = "Separating stems..."
            progress = 0f
            cancelFlag.set(false)
            separationJob?.cancel()
            
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val modelDir = File(context.filesDir, "models")
                    val model = File(modelDir, "demucs.onnx")
                    if (!model.exists()) {
                        error = "Model not found. Download from Models tab."
                        busy = false
                        return@launch
                    }
                    
                    val inputFile = File(song.uri)
                    val stemsDir = File(context.filesDir, "stems/${song.uri.hashCode()}")
                    stemsDir.mkdirs()
                    
                    demucs?.close()
                    demucs = Demucs(model)
                    
                    val overlap = when (mode) { 0 -> 0.25f; 1 -> 0.35f; else -> 0.45f }
                    
                    demucs?.separate(inputFile, stemsDir, overlap,
                        before = { ensureActive() },
                        progress = { progress = it }
                    )
                    
                    // Create instrumental mix (drums + bass + other + guitar + piano)
                    val instrumental = File(stemsDir, "instrumental.pcm")
                    val instrumentalStems = listOf(
                        File(stemsDir, "drums.pcm"),
                        File(stemsDir, "bass.pcm"),
                        File(stemsDir, "other.pcm"),
                        File(stemsDir, "guitar.pcm"),
                        File(stemsDir, "piano.pcm"),
                    ).filter { it.exists() }
                    if (instrumentalStems.isNotEmpty()) {
                        AudioFiles.mix(instrumentalStems, List(instrumentalStems.size) { 1f }, instrumental, { })
                    }
                    
                    val updated = song.copy(stemsDir = stemsDir.absolutePath)
                    library.songs().save(updated)
                    current = updated
                    modelReady = true
                    error = "Separation complete"
                } catch (e: Exception) {
                    error = "Separation failed: ${e.message}"
                } finally {
                    busy = false
                    progress = 0f
                }
            }
        }
    }

    // Recording
    fun startRecording() {
        if (record) return
        current?.let { song ->
            record = true
            finalizing = false
            val recDir = File(context.filesDir, "recordings")
            recDir.mkdirs()
            recordingFile = File(recDir, "rec_${System.currentTimeMillis()}.pcm")
            mixedRecording = File(recDir, "mix_${System.currentTimeMillis()}.pcm")
            
            // Start microphone recording
            recordingJob = viewModelScope.launch(Dispatchers.IO) {
                recordMicrophone(recordingFile!!)
            }
            
            // Start playback from beginning for karaoke
            player.seek(0)
            player.play()
            playing = true
        }
    }

    fun stopRecording() {
        if (!record) return
        record = false
        finalizing = true
        player.pause()
        playing = false
        
        recordingJob?.cancel()
        recordingJob = null
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Mix recording with backing track if karaoke
                recordingFile?.let { rec ->
                    mixedRecording = File(rec.parentFile, "karaoke_${System.currentTimeMillis()}.pcm")
                    if (karaoke && current?.stemsDir?.isNotEmpty() == true) {
                        val stemsDir = File(current!!.stemsDir)
                        val backingFiles = listOf(
                            File(stemsDir, "drums.pcm"),
                            File(stemsDir, "bass.pcm"),
                            File(stemsDir, "other.pcm"),
                            File(stemsDir, "guitar.pcm"),
                            File(stemsDir, "piano.pcm"),
                        ).filter { it.exists() }
                        if (backingFiles.isNotEmpty()) {
                            val backingMix = File(rec.parentFile, "backing_mix.pcm")
                            AudioFiles.mix(backingFiles, List(backingFiles.size) { 1f }, backingMix, { })
                            
                            // Mix recording with backing
                            mixRecordingWithBacking(rec, backingMix, mixedRecording!!)
                        } else {
                            rec.copyTo(mixedRecording!!)
                        }
                    } else {
                        rec.copyTo(mixedRecording!!)
                    }
                    finalizing = false
                    error = "Recording saved"
                }
            } catch (e: Exception) {
                error = "Failed to save recording: ${e.message}"
                finalizing = false
            }
        }
    }

    private fun recordMicrophone(output: File) {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4
        
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        
        recorder.startRecording()
        output.outputStream().buffered().use { out ->
            val buffer = ShortArray(bufferSize / 2)
            while (record && !cancelFlag.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val bytes = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) bytes.putShort(buffer[i])
                    out.write(bytes.array())
                }
            }
        }
        recorder.stop()
        recorder.release()
    }

    private fun mixRecordingWithBacking(rec: File, backing: File, output: File) {
        val recIn = RandomAccessFile(rec, "r")
        val backIn = RandomAccessFile(backing, "r")
        output.outputStream().buffered().use { out ->
            val recLen = recIn.length() / 4
            val backLen = backIn.length() / 4
            val maxLen = maxOf(recLen, backLen)
            var pos = 0L
            while (pos < maxLen) {
                val n = min(8192L, maxLen - pos).toInt()
                val recData = if (pos < recLen) AudioFiles.floats(recIn, pos, n) else Array(2) { FloatArray(n) }
                val backData = if (pos < backLen) AudioFiles.floats(backIn, pos, n) else Array(2) { FloatArray(n) }
                val mixed = Array(2) { FloatArray(n) }
                for (c in 0..1) for (i in 0 until n) {
                    val r = if (pos < recLen) recData[c][i] else 0f
                    val b = if (pos < backLen) backData[c][i] else 0f
                    mixed[c][i] = (r + b * 0.7f).coerceIn(-1f, 1f)
                }
                AudioFiles.pcm(out, mixed)
                pos += n
            }
        }
        recIn.close()
        backIn.close()
    }

    fun previewRecording() {
        mixedRecording?.let { file ->
            player.preparePreview(file, false)
            player.play()
            playing = true
        }
    }

    // Export
    fun exportAudio(uri: Uri, kind: String) {
        busy = true
        status = "Exporting $kind..."
        progress = 0f
        finalizing = true
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pcmFile: File? = when (kind) {
                    "Mix" -> {
                        if (current?.stemsDir?.isNotEmpty() == true) {
                            val stemsDir = File(current!!.stemsDir)
                            val files = listOf(
                                File(stemsDir, "vocals.pcm"),
                                File(stemsDir, "instrumental.pcm"),
                            ).filter { it.exists() }
                            val gains = listOf(gain0, gain1)
                            val mixFile = File(context.cacheDir, "export_mix.pcm")
                            AudioFiles.mix(files, gains, mixFile, { progress = it })
                            mixFile
                        } else if (studio.project.tracks.isNotEmpty()) {
                            val mixFile = File(context.cacheDir, "export_mix.pcm")
                            studio.renderMix(mixFile, { progress = it })
                            mixFile
                        } else null
                    }
                    "Vocals" -> current?.stemsDir?.let { File(it, "vocals.pcm") }
                    "Instrumental" -> current?.stemsDir?.let { File(it, "instrumental.pcm") }
                    "Recording" -> recordingFile
                    "Karaoke take" -> mixedRecording
                    else -> null
                }
                
                pcmFile?.let { pcm ->
                    val format = if (uri.toString().endsWith(".mp3")) "MP3" else "WAV"
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        Encoders.encode(pcm, out, format, 44100, 2, 192, { progress = it })
                    }
                }
                error = "Exported $kind"
            } catch (e: Exception) {
                error = "Export failed: ${e.message}"
            } finally {
                busy = false
                progress = 0f
                finalizing = false
            }
        }
    }

    fun cancel() {
        cancelFlag.set(true)
        separationJob?.cancel()
        mixPreviewJob?.cancel()
        recordingJob?.cancel()
        busy = false
        progress = 0f
        status = ""
        finalizing = false
    }

    // Helper functions
    fun hasStems(): Boolean = current?.stemsDir?.isNotEmpty() == true

    private fun copyUriToFile(uri: Uri, dest: File, progress: (Float) -> Unit) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().buffered().use { output ->
                val buffer = ByteArray(8192)
                val total = input.available().toLong()
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0) progress(copied.toFloat() / total)
                }
            }
        }
    }

    private fun getTitleFromUri(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else "Imported"
                } else "Imported"
            } ?: "Imported"
        } catch (e: Exception) {
            "Imported"
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
        demucs?.close()
        library.close()
        cancelFlag.set(true)
    }
}

// ExoPlayer wrapper for audio playback
class ExoPlayerWrapper(private val context: Context) {
    private var player: com.google.android.exoplayer2.ExoPlayer? = null
    private var previewMode = false
    private var currentSong: Song? = null
    private var stemsDir: String? = null
    var playing = false
        private set
    
    var position: Long = 0
        get() = player?.currentPosition ?: 0
    
    var duration: Long = 0
        get() = player?.duration ?: 0

    fun prepare(song: Song) {
        release()
        currentSong = song
        stemsDir = if (song.stemsDir.isNotEmpty()) song.stemsDir else null
        previewMode = false
        
        val mediaItem = if (stemsDir != null) {
            // Play mixed stems
            val mixFile = File(context.cacheDir, "playback_mix.pcm")
            // Create a quick mix for playback
            val stemsDirFile = File(stemsDir!!)
            val files = listOf(
                File(stemsDirFile, "vocals.pcm"),
                File(stemsDirFile, "instrumental.pcm"),
            ).filter { it.exists() }
            if (files.isNotEmpty()) {
                // Use a simple mix for now - will be replaced by proper mixing
                com.google.android.exoplayer2.MediaItem.fromUri(files[0].absolutePath)
            } else {
                com.google.android.exoplayer2.MediaItem.fromUri(song.uri)
            }
        } else {
            com.google.android.exoplayer2.MediaItem.fromUri(song.uri)
        }
        
        player = com.google.android.exoplayer2.ExoPlayer.Builder(context).build()
        player?.setMediaItem(mediaItem)
        player?.prepare()
        duration = player?.duration ?: 0
    }

    fun preparePreview(file: File, isStems: Boolean) {
        release()
        previewMode = true
        val mediaItem = com.google.android.exoplayer2.MediaItem.fromUri(file.absolutePath)
        player = com.google.android.exoplayer2.ExoPlayer.Builder(context).build()
        player?.setMediaItem(mediaItem)
        player?.prepare()
        duration = player?.duration ?: 0
    }

    fun play() {
        player?.playWhenReady = true
        playing = true
    }

    fun pause() {
        player?.playWhenReady = false
        playing = false
    }

    fun seek(ms: Long) {
        player?.seekTo(ms)
    }

    fun setSpeed(speed: Float) {
        val params = player?.playbackParameters
        player?.playbackParameters = params?.withSpeed(speed)!!
    }

    fun setPitch(pitch: Float) {
        // ExoPlayer doesn't directly support pitch shifting without a custom audio processor
        // This would require a Sonic audio processor or similar
    }

    fun release() {
        player?.release()
        player = null
        playing = false
    }
}

// Studio Engine for multitrack functionality
class StudioEngine(private val context: Context, private val viewModel: StudioViewModel) {
    data class Track(
        val id: Long = System.currentTimeMillis(),
        val uri: String,
        val name: String,
        var gain: Float = 1f,
        var mute: Boolean = false,
        var solo: Boolean = false,
        var offset: Long = 0,
    )
    
    data class Project(
        val tracks: List<Track> = emptyList(),
        val tempo: Float = 120f,
        val timeSignature: String = "4/4",
    )
    
    var project by mutableStateOf(Project())
    
    val format = "WAV"
    
    fun addTrack(uri: Uri) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val destDir = File(context.filesDir, "studio_tracks")
                destDir.mkdirs()
                val dest = File(destDir, "track_${System.currentTimeMillis()}.pcm")
                // Decode to PCM
                AudioFiles.decode(context, uri, dest, { })
                val track = Track(
                    uri = dest.absolutePath,
                    name = uri.lastPathSegment ?: "Track ${project.tracks.size + 1}",
                )
                project = project.copy(tracks = project.tracks + track)
            } catch (e: Exception) {
                viewModel.error = "Failed to add track: ${e.message}"
            }
        }
    }
    
    fun renderMix(output: File, progress: (Float) -> Unit) {
        if (project.tracks.isEmpty()) return
        
        val files = project.tracks.map { File(it.uri) }.filter { it.exists() }
        val gains = project.tracks.map { it.gain }
        AudioFiles.mix(files, gains, output, progress)
    }
    
    fun export(uri: Uri, kind: String) {
        viewModel.busy = true
        viewModel.status = "Exporting $kind..."
        viewModel.progress = 0f
        
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val mixFile = File(context.cacheDir, "studio_export.pcm")
                renderMix(mixFile, { viewModel.progress = it })
                
                val format = if (uri.toString().endsWith(".mp3")) "MP3" else "WAV"
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    Encoders.encode(mixFile, out, format, 44100, 2, 192, { viewModel.progress = it })
                }
                viewModel.error = "Exported $kind"
            } catch (e: Exception) {
                viewModel.error = "Export failed: ${e.message}"
            } finally {
                viewModel.busy = false
                viewModel.progress = 0f
            }
        }
    }
    
    fun saveProject(uri: Uri) {
        viewModel.busy = true
        viewModel.status = "Saving project..."
        viewModel.progress = 0f
        
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    ZipOutputStream(output).use { zip ->
                        // Save project metadata
                        val metadata = ProjectData(
                            tracks = project.tracks.map { t ->
                                ProjectData.TrackData(
                                    id = t.id,
                                    uri = t.uri,
                                    name = t.name,
                                    gain = t.gain,
                                    mute = t.mute,
                                    solo = t.solo,
                                    offset = t.offset,
                                )
                            },
                            tempo = project.tempo,
                            timeSignature = project.timeSignature,
                        )
                        val json = kotlinx.serialization.json.Json.encodeToString(metadata)
                        zip.putNextEntry(ZipEntry("project.json"))
                        zip.write(json.toByteArray())
                        zip.closeEntry()
                        
                        // Copy audio files
                        project.tracks.forEachIndexed { index, track ->
                            val file = File(track.uri)
                            if (file.exists()) {
                                zip.putNextEntry(ZipEntry("audio/track_${track.id}.pcm"))
                                file.inputStream().use { zip.write(it.readAllBytes()) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
                viewModel.error = "Project saved"
            } catch (e: Exception) {
                viewModel.error = "Save failed: ${e.message}"
            } finally {
                viewModel.busy = false
                viewModel.progress = 0f
            }
        }
    }
    
    fun openProject(uri: Uri) {
        viewModel.busy = true
        viewModel.status = "Opening project..."
        viewModel.progress = 0f
        
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zip ->
                        var metadata: ProjectData? = null
                        val audioFiles = mutableMapOf<Long, File>()
                        val destDir = File(context.filesDir, "studio_tracks")
                        destDir.mkdirs()
                        
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (entry.name == "project.json") {
                                val json = zip.readAllBytes().decodeToString()
                                metadata = kotlinx.serialization.json.Json.decodeFromString(json)
                            } else if (entry.name.startsWith("audio/") && entry.name.endsWith(".pcm")) {
                                val id = entry.name.removePrefix("audio/track_").removeSuffix(".pcm").toLong()
                                val dest = File(destDir, "track_$id.pcm")
                                dest.outputStream().use { zip.copyTo(it) }
                                audioFiles[id] = dest
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                        
                        metadata?.let { meta ->
                            val tracks = meta.tracks.map { td ->
                                Track(
                                    id = td.id,
                                    uri = audioFiles[td.id]?.absolutePath ?: td.uri,
                                    name = td.name,
                                    gain = td.gain,
                                    mute = td.mute,
                                    solo = td.solo,
                                    offset = td.offset,
                                )
                            }
                            project = Project(tracks = tracks, tempo = meta.tempo, timeSignature = meta.timeSignature)
                        }
                    }
                }
                viewModel.error = "Project loaded"
            } catch (e: Exception) {
                viewModel.error = "Open failed: ${e.message}"
            } finally {
                viewModel.busy = false
                viewModel.progress = 0f
            }
        }
    }
    
    fun startTuner() {
        // Tuner implementation would go here
        viewModel.error = "Tuner started (placeholder)"
    }
    
    fun stopTuner() {
        // Stop tuner
    }
    
    @kotlinx.serialization.Serializable
    data class ProjectData(
        val tracks: List<TrackData>,
        val tempo: Float,
        val timeSignature: String,
    ) {
        @kotlinx.serialization.Serializable
        data class TrackData(
            val id: Long,
            val uri: String,
            val name: String,
            val gain: Float,
            val mute: Boolean,
            val solo: Boolean,
            val offset: Long,
        )
    }
}
