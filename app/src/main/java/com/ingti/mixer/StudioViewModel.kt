package com.ingti.mixer

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.*
import java.util.concurrent.Executors
import java.util.zip.*

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cpuScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val player = MediaPlayer()
    private val executor = Executors.newSingleThreadExecutor()

    // State
    var screen by mutableStateOf("Player")
        private set
    var error by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var current by mutableStateOf<Song?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var position by mutableStateOf(0L)
        private set
    var duration by mutableStateOf(0L)
        private set
    var gain0 by mutableStateOf(1f)
        private set
    var gain1 by mutableStateOf(1f)
        private set
    var mute0 by mutableStateOf(false)
        private set
    var mute1 by mutableStateOf(false)
        private set
    var solo by mutableStateOf(-1)
        private set
    var karaoke by mutableStateOf(false)
        private set
    var loopA by mutableStateOf(-1L)
        private set
    var loopB by mutableStateOf(-1L)
        private set
    var speed by mutableStateOf(1f)
        private set
    var pitch by mutableStateOf(0f)
        private set
    var latencyMs by mutableStateOf(0)
        private set
    var record by mutableStateOf(false)
        private set
    var finalizing by mutableStateOf(false)
        private set
    var recordingFile: File? by mutableStateOf(null)
        private set
    var mixedRecording: File? by mutableStateOf(null)
        private set
    var modelReady by mutableStateOf(false)
        private set

    val songs = library.songs().all().distinctUntilChanged().asStateFlow()
    val studio = StudioEngine(this)

    private val library = Room.databaseBuilder(
        getApplication(),
        Library::class.java,
        "ingtimixer.db"
    ).build()

    private var positionJob: Job? = null

    init {
        checkModels()
        player.setOnCompletionListener { playing = false }
    }

    private fun checkModels() {
        ioScope.launch {
            val ctx = getApplication()
            modelReady = Models(ctx, 0).installed()
        }
    }

    fun open(song: Song) {
        current = song
        player.reset()
        try {
            val fd = contentResolver.openFileDescriptor(Uri.parse(song.uri), "r")!!
            player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            player.prepare()
            duration = player.duration.toLong()
            playing = false
            position = 0
            gain0 = song.mix.split(",").getOrNull(0)?.toFloatOrNull() ?: 1f
            gain1 = song.mix.split(",").getOrNull(1)?.toFloatOrNull() ?: 1f
            startPositionTracker()
        } catch (e: Exception) {
            error = "Failed to open: ${e.message}"
        }
    }

    private fun startPositionTracker() {
        positionJob?.cancel()
        positionJob = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            while (playing && current != null) {
                position = player.currentPosition.toLong()
                if (loopB > 0 && position >= loopB) {
                    player.seekTo(loopA.coerceAtLeast(0).toInt())
                }
                delay(100)
            }
        }
    }

    fun togglePlay() {
        if (playing) {
            player.pause()
            playing = false
        } else {
            player.start()
            playing = true
            startPositionTracker()
        }
    }

    fun seek(pos: Long) {
        player.seekTo(pos.coerceAtLeast(0).coerceAtMost(duration).toInt())
        position = player.currentPosition.toLong()
    }

    fun offset(ms: Long) {
        current?.let { song ->
            val updated = song.copy(offset = ms)
            library.songs().save(updated)
            current = updated
        }
    }

    fun favorite(song: Song) {
        val updated = song.copy(favorite = !song.favorite, lastPlayed = System.currentTimeMillis())
        ioScope.launch { library.songs().save(updated) }
    }

    fun saveLyrics(text: String) {
        current?.let { song ->
            val updated = song.copy(lyrics = text)
            ioScope.launch { library.songs().save(updated) }
            current = updated
        }
    }

    fun importAudio(uri: Uri) {
        busy = true
        status = "Importing…"
        progress = 0f
        ioScope.launch {
            try {
                val ctx = getApplication()
                val name = contentResolver.openFileDescriptor(uri, "r")?.use { it.fileDescriptor }?.let {
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        c.moveToFirst()
                        c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                    }
                } ?: "imported_audio"
                val destDir = File(ctx.filesDir, "audio")
                destDir.mkdirs()
                val dest = File(destDir, "${System.currentTimeMillis()}_$name")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val song = Song(
                    uri = dest.toUri().toString(),
                    title = name.removeSurrounding(".mp3").removeSurrounding(".wav").removeSurrounding(".flac").removeSurrounding(".m4a"),
                    artist = "",
                )
                ioScope.launch { library.songs().save(song) }
                open(song)
                busy = false
            } catch (e: Exception) {
                error = "Import failed: ${e.message}"
                busy = false
            }
        }
    }

    fun importLyrics(uri: Uri) {
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val text = reader.readText()
            saveLyrics(text)
        }
    }

    fun exportLyrics(uri: Uri, srt: Boolean) {
        current?.let { song ->
            val cues = Lyrics.parse(song.lyrics)
            val export = Lyrics.export(cues, srt, song.offset)
            ioScope.launch {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(export.toByteArray()) }
                } catch (e: Exception) {
                    error = "Export failed: ${e.message}"
                }
            }
        }
    }

    fun exportAudio(uri: Uri, kind: String) {
        current?.let { song ->
            busy = true
            finalizing = true
            status = "Exporting $kind…"
            progress = 0f
            cpuScope.launch {
                try {
                    val src: File
                    when (kind) {
                        "Mix" -> {
                            val dir = song.stemsDir
                            if (dir.isNotEmpty()) {
                                val vocals = File(dir, "vocals.pcm")
                                val instrumental = File(dir, "instrumental.pcm")
                                val out = File(cacheDir, "mix_${System.currentTimeMillis()}.pcm")
                                AudioFiles.mix(listOf(vocals, instrumental), listOf(gain0, gain1), out, { progress = it })
                                src = out
                            } else {
                                src = File(Uri.parse(song.uri).path!!)
                            }
                        }
                        "Vocals" -> src = File(song.stemsDir, "vocals.pcm")
                        "Instrumental" -> src = File(song.stemsDir, "instrumental.pcm")
                        "Recording" -> src = recordingFile ?: return@launch
                        "Karaoke take" -> src = mixedRecording ?: return@launch
                        else -> throw IllegalArgumentException("Unknown kind $kind")
                    }
                    val ext = if (uri.toString().endsWith(".mp3")) ".mp3" else if (uri.toString().endsWith(".flac")) ".flac" else ".wav"
                    val format = when (ext) {
                        ".mp3" -> "MP3"
                        ".flac" -> "FLAC"
                        else -> "WAV"
                    }
                    Encoders.encode(src, contentResolver.openOutputStream(uri)!!, format, progress = { progress = it })
                    busy = false
                    finalizing = false
                } catch (e: Exception) {
                    error = "Export failed: ${e.message}"
                    busy = false
                    finalizing = false
                }
            }
        }
    }

    fun tempo(newSpeed: Float, newPitch: Float) {
        speed = newSpeed
        pitch = newPitch
        if (playing) {
            player.playbackParams = player.playbackParams
                .setSpeed(newSpeed)
                .setPitch(newPitch)
        }
    }

    fun previewMix() {
        current?.let { song ->
            if (song.stemsDir.isNotEmpty()) {
                busy = true
                status = "Previewing mix…"
                progress = 0f
                cpuScope.launch {
                    try {
                        val vocals = File(song.stemsDir, "vocals.pcm")
                        val instrumental = File(song.stemsDir, "instrumental.pcm")
                        val out = File(cacheDir, "preview_${System.currentTimeMillis()}.pcm")
                        AudioFiles.mix(listOf(vocals, instrumental), listOf(gain0, gain1), out, { progress = it })
                        player.reset()
                        player.setDataSource(out.absolutePath)
                        player.prepare()
                        player.start()
                        playing = true
                        duration = player.duration.toLong()
                        startPositionTracker()
                        busy = false
                    } catch (e: Exception) {
                        error = "Preview failed: ${e.message}"
                        busy = false
                    }
                }
            }
        }
    }

    fun separate(mode: Int) {
        current?.let { song ->
            val model = Models(getApplication(), mode).file
            if (!model.exists()) {
                error = "Model not found. Download it in Models tab."
                return
            }
            busy = true
            status = "Separating…"
            progress = 0f
            val stemsDir = File(getApplication().filesDir, "stems/${song.uri.hashCode()}")
            stemsDir.mkdirs()
            val input = File(Uri.parse(song.uri).path!!)
            Demucs(model).use { demucs ->
                cpuScope.launch {
                    try {
                        demucs.separate(input, stemsDir, 0.25f, { ensureActive() }, { progress = it })
                        val updated = song.copy(stemsDir = stemsDir.absolutePath, mix = "1,1")
                        ioScope.launch { library.songs().save(updated) }
                        current = updated
                        gain0 = 1f
                        gain1 = 1f
                        busy = false
                    } catch (e: Exception) {
                        error = "Separation failed: ${e.message}"
                        busy = false
                    }
                }
            }
        }
    }

    fun karaoke() {
        karaoke = !karaoke
        if (karaoke) {
            gain0 = 0f
            gain1 = 1f
        } else {
            gain0 = 1f
            gain1 = 1f
        }
        current?.let { song ->
            if (song.stemsDir.isNotEmpty()) {
                previewMix()
            }
        }
    }

    fun hasStems(): Boolean = current?.stemsDir?.isNotEmpty() == true

    fun cancel() {
        ioScope.coroutineContext.cancelChildren()
        cpuScope.coroutineContext.cancelChildren()
        busy = false
        finalizing = false
    }

    fun startRecording() {
        record = true
        recordingFile = File(cacheDir, "rec_${System.currentTimeMillis()}.pcm")
        mixedRecording = File(cacheDir, "mixed_${System.currentTimeMillis()}.pcm")
        // Recording implementation would go here
        // For now, just simulate
    }

    fun stopRecording() {
        record = false
        // Stop recording logic
    }

    fun previewRecording() {
        recordingFile?.let { file ->
            player.reset()
            try {
                player.setDataSource(file.absolutePath)
                player.prepare()
                player.start()
                playing = true
                duration = player.duration.toLong()
                startPositionTracker()
            } catch (e: Exception) {
                error = "Preview failed: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
        executor.shutdown()
        ioScope.cancel()
        cpuScope.cancel()
        positionJob?.cancel()
    }

    private val contentResolver: ContentResolver = getApplication().contentResolver
    private val cacheDir: File = getApplication().cacheDir
}

class StudioEngine(private val vm: StudioViewModel) {
    data class Track(
        val name: String,
        val file: File,
        var gain: Float = 1f,
        var mute: Boolean = false,
        var solo: Boolean = false,
    )

    data class Project(
        val tracks: MutableList<Track> = mutableListOf(),
        var tempo: Float = 120f,
        var timeSig: String = "4/4",
    )

    var project = Project()
    var format = "WAV"

    private var tunerJob: Job? = null

    fun addTrack(uri: Uri) {
        val ctx = vm.getApplication()
        val name = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.fileDescriptor }?.let {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                c.moveToFirst()
                c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
            }
        } ?: "Track ${project.tracks.size + 1}"
        val destDir = File(ctx.filesDir, "studio_tracks")
        destDir.mkdirs()
        val dest = File(destDir, "${System.currentTimeMillis()}_$name")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        project.tracks.add(Track(name, dest))
    }

    fun export(uri: Uri, kind: String) {
        vm.busy = true
        vm.status = "Exporting $kind…"
        vm.progress = 0f
        vm.cpuScope.launch {
            try {
                val tracks = project.tracks.filter { !it.mute && (project.tracks.none { it.solo } || it.solo) }
                if (tracks.isEmpty()) throw IllegalStateException("No tracks to export")
                val mixed = File(vm.cacheDir, "studio_mix_${System.currentTimeMillis()}.pcm")
                val inputs = tracks.map { it.file }
                val gains = tracks.map { it.gain }
                AudioFiles.mix(inputs, gains, mixed, { vm.progress = it })
                Encoders.encode(mixed, vm.contentResolver.openOutputStream(uri)!!, format, progress = { vm.progress = it })
                vm.busy = false
            } catch (e: Exception) {
                vm.error = "Export failed: ${e.message}"
                vm.busy = false
            }
        }
    }

    fun saveProject(uri: Uri) {
        vm.ioScope.launch {
            try {
                val zip = vm.contentResolver.openOutputStream(uri) ?: return@launch
                ZipOutputStream(zip).use { zos ->
                    // Save project metadata
                    val meta = ByteArrayOutputStream()
                    ObjectOutputStream(meta).use { oos ->
                        oos.writeObject(project.tracks.map { it.name })
                        oos.writeFloat(project.tempo)
                        oos.writeUTF(project.timeSig)
                    }
                    zos.putNextEntry(ZipEntry("project.dat"))
                    zos.write(meta.toByteArray())
                    zos.closeEntry()
                    // Save audio files
                    project.tracks.forEachIndexed { i, track ->
                        zos.putNextEntry(ZipEntry("track_$i.pcm"))
                        track.file.inputStream().use { zos.write(it.readAllBytes()) }
                        zos.closeEntry()
                    }
                }
            } catch (e: Exception) {
                vm.error = "Save failed: ${e.message}"
            }
        }
    }

    fun openProject(uri: Uri) {
        vm.ioScope.launch {
            try {
                val zip = vm.contentResolver.openInputStream(uri) ?: return@launch
                ZipInputStream(zip).use { zis ->
                    var entry: ZipEntry?
                    while (zis.nextEntry.also { entry = it } != null) {
                        if (entry!!.name == "project.dat") {
                            // TODO: deserialize project
                        } else if (entry!!.name.endsWith(".pcm")) {
                            val dest = File(vm.getApplication().filesDir, "studio_tracks/${entry.name}")
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { zis.copyTo(it) }
                            project.tracks.add(Track(entry.name.removeSuffix(".pcm"), dest))
                        }
                    }
                }
            } catch (e: Exception) {
                vm.error = "Open failed: ${e.message}"
            }
        }
    }

    fun startTuner() {
        tunerJob?.cancel()
        tunerJob = vm.cpuScope.launch {
            // Tuner implementation would go here
        }
    }

    fun stopTuner() {
        tunerJob?.cancel()
        tunerJob = null
    }
}
