package com.ingti.mixer

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bihe0832.android.lib.sherpa.OnnxModel
import com.bihe0832.android.lib.sherpa.SherpaOnnx
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    // Player
    val player = PlayerEngine()
    var current: Song? = null
        private set
    var playing = false
        private set
    var position = 0L
        private set
    var duration = 0L
        private set
    var gain0 = 1f
        private set
    var gain1 = 1f
        private set
    var mute0 = false
        private set
    var mute1 = false
        private set
    var solo = -1
        private set

    // Karaoke / recording
    var karaoke = false
        private set
    var record = false
        private set
    var finalizing = false
        private set
    var recordingFile: File? = null
        private set
    var mixedRecording: File? = null
        private set
    var latencyMs = 0
        private set

    // Loop
    var loopA = -1L
        private set
    var loopB = -1L
        private set

    // Tempo / pitch
    var speed = 1f
        private set
    var pitch = 0f
        private set

    // Separation
    var modelReady = false
        private set
    var busy = false
        private set
    var progress = 0f
        private set
    var status = ""
        private set
    var error = ""
        private set

    // Library
    private val db = Room.databaseBuilder(
        getApplication(), Library::class.java, "library.db"
    ).build()
    val songs = db.songs().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    // Studio
    val studio = StudioEngine(getApplication())

    // Model download
    private val sherpa = SherpaOnnx()
    private val downloadJob = SupervisorJob()
    private val downloadScope = CoroutineScope(Dispatchers.IO + downloadJob)

    init {
        player.onPosition = { pos, dur ->
            position = pos
            duration = dur
        }
        player.onCompletion = { playing = false }
        loadModelIfExists()
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
        studio.release()
        downloadJob.cancel()
    }

    private fun loadModelIfExists() {
        val dir = File(getApplication().filesDir, "models")
        val model = File(dir, "demucs.onnx")
        if (model.exists()) {
            try {
                studio.demucs = Demucs(model)
                modelReady = true
            } catch (e: Exception) {
                error = "Failed to load model: ${e.message}"
            }
        }
    }

    fun importAudio(uri: Uri) = viewModelScope.launch {
        try {
            busy = true
            status = "Importing…"
            val file = File(cacheDir, "import-${System.currentTimeMillis()}.pcm")
            val rate = AudioFiles.decode(getApplication<Context>(), uri, file) { progress = it }
            val song = Song(
                uri = uri.toString(),
                title = getTitle(uri) ?: "Unknown",
            )
            db.songs().save(song)
            current = song
            player.open(file, rate)
            duration = file.length() / 4
            busy = false
        } catch (e: Exception) {
            busy = false
            error = "Import failed: ${e.message}"
        }
    }

    fun importLyrics(uri: Uri) = viewModelScope.launch {
        try {
            getApplication<ContentResolver>().openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                current?.let {
                    it.lyrics = text
                    db.songs().save(it)
                    current = it
                }
            }
        } catch (e: Exception) {
            error = "Lyrics import failed: ${e.message}"
        }
    }

    fun exportLyrics(uri: Uri, srt: Boolean) = viewModelScope.launch {
        current?.let {
            try {
                getApplication<ContentResolver>().openOutputStream(uri)?.use { out ->
                    out.write(Lyrics.export(Lyrics.parse(it.lyrics), srt).toByteArray())
                }
            } catch (e: Exception) {
                error = "Export failed: ${e.message}"
            }
        }
    }

    fun exportAudio(uri: Uri, kind: String) = viewModelScope.launch {
        try {
            busy = true
            status = "Exporting $kind…"
            val source = when (kind) {
                "Mix" -> mixedRecording ?: recordingFile ?: current?.stemsDir?.let { mixStems(File(it)) }
                "Vocals" -> current?.stemsDir?.let { File(it, "vocals.pcm") }
                "Instrumental" -> current?.stemsDir?.let { File(it, "instrumental.pcm") }
                "Recording" -> recordingFile
                "Karaoke take" -> mixedRecording
                else -> null
            }
            source?.let { src ->
                getApplication<ContentResolver>().openOutputStream(uri)?.use { out ->
                    Encoders.encode(src, out, "WAV", progress = { progress = it })
                }
            }
            busy = false
        } catch (e: Exception) {
            busy = false
            error = "Export failed: ${e.message}"
        }
    }

    private fun mixStems(dir: File): File {
        val out = File(cacheDir, "mix-${System.currentTimeMillis()}.pcm")
        AudioFiles.mix(
            listOf(File(dir, "vocals.pcm"), File(dir, "instrumental.pcm")),
            listOf(gain0, gain1),
            out,
            { progress = it }
        )
        return out
    }

    fun open(song: Song) = viewModelScope.launch {
        try {
            busy = true
            status = "Loading…"
            val file = if (song.stemsDir.isNotEmpty()) {
                mixStems(File(song.stemsDir))
            } else {
                File(cacheDir, "open-${System.currentTimeMillis()}.pcm").also { out ->
                    val uri = Uri.parse(song.uri)
                    AudioFiles.decode(getApplication<Context>(), uri, out) { progress = it }
                }
            }
            current = song
            player.open(file, 44100)
            duration = file.length() / 4
            gain0 = 1f
            gain1 = 1f
            mute0 = false
            mute1 = false
            solo = -1
            busy = false
        } catch (e: Exception) {
            busy = false
            error = "Open failed: ${e.message}"
        }
    }

    fun favorite(song: Song) = viewModelScope.launch {
        song.favorite = !song.favorite
        song.lastPlayed = System.currentTimeMillis()
        db.songs().save(song)
    }

    fun saveLyrics(text: String) = viewModelScope.launch {
        current?.let {
            it.lyrics = text
            db.songs().save(it)
            current = it
        }
    }

    fun offset(ms: Long) = viewModelScope.launch {
        current?.let {
            it.offset = ms
            db.songs().save(it)
            current = it
        }
    }

    fun togglePlay() {
        if (playing) player.pause() else player.start()
        playing = !playing
    }

    fun seek(pos: Long) {
        player.seek(pos)
        position = pos
    }

    fun tempo(newSpeed: Float, newPitch: Float) {
        speed = newSpeed.coerceIn(0.5f, 1.5f)
        pitch = newPitch.coerceIn(-12f, 12f)
        player.setTempo(speed, pitch)
    }

    fun previewMix() = viewModelScope.launch {
        try {
            busy = true
            status = "Mixing…"
            val mixed = mixStems(File(current?.stemsDir ?: ""))
            current?.let { it.stemsDir = mixed.parent }
            player.open(mixed, 44100)
            duration = mixed.length() / 4
            busy = false
        } catch (e: Exception) {
            busy = false
            error = "Preview failed: ${e.message}"
        }
    }

    fun karaoke() {
        karaoke = !karaoke
        if (karaoke) {
            gain0 = 0f
            gain1 = 1f
            mute0 = true
        } else {
            gain0 = 1f
            mute0 = false
        }
    }

    fun separate(mode: Int) = viewModelScope.launch {
        current?.let { song ->
            try {
                busy = true
                status = "Separating…"
                val dir = File(getApplication().filesDir, "stems-${song.uri.hashCode()}")
                val window = when (mode) {
                    0 -> 343980 / 4  // Fast: ~6s
                    1 -> 343980 / 2  // Balanced: ~12s
                    else -> 343980   // Studio: ~24s
                }
                val overlap = when (mode) {
                    0 -> 0.25f
                    1 -> 0.25f
                    else -> 0.125f
                }
                studio.demucs?.separate(
                    input = if (song.stemsDir.isNotEmpty()) File(song.stemsDir, "mix.pcm")
                    else File(cacheDir, "import-${song.uri.hashCode()}.pcm"),
                    directory = dir,
                    overlap = overlap,
                    before = { ensureActive() },
                    progress = { progress = it }
                )
                song.stemsDir = dir.absolutePath
                db.songs().save(song)
                current = song
                modelReady = true
                busy = false
                // Re-open with stems
                open(song)
            } catch (e: Exception) {
                busy = false
                error = "Separation failed: ${e.message}"
            }
        }
    }

    fun downloadModel() = downloadScope.launch {
        try {
            busy = true
            status = "Downloading model…"
            val dir = File(getApplication().filesDir, "models")
            dir.mkdirs()
            val model = File(dir, "demucs.onnx")
            sherpa.downloadModel(OnnxModel.DEMUCS_V4, dir.absolutePath) { p ->
                progress = p
            }
            studio.demucs = Demucs(model)
            modelReady = true
            busy = false
        } catch (e: Exception) {
            busy = false
            error = "Download failed: ${e.message}"
        }
    }

    fun cancel() {
        player.pause()
        playing = false
        // Note: cooperative cancellation via ensureActive() in coroutines
    }

    fun startRecording() = viewModelScope.launch {
        try {
            record = true
            finalizing = false
            recordingFile = File(cacheDir, "rec-${System.currentTimeMillis()}.pcm")
            player.startRecording(recordingFile!!, latencyMs)
        } catch (e: Exception) {
            record = false
            error = "Recording failed: ${e.message}"
        }
    }

    fun stopRecording() = viewModelScope.launch {
        try {
            finalizing = true
            player.stopRecording()
            record = false
            // Mix recording with backing track
            val backing = when {
                karaoke -> current?.stemsDir?.let { File(it, "instrumental.pcm") }
                solo != -1 -> current?.stemsDir?.let {
                    val stems = Demucs.names.map { File(it, it.lowercase() + ".pcm") }
                    if (stems[solo].exists()) stems[solo] else null
                }
                else -> mixStems(File(current?.stemsDir ?: ""))
            }
            backing?.let { back ->
                mixedRecording = File(cacheDir, "mixed-${System.currentTimeMillis()}.pcm")
                AudioFiles.mix(
                    listOf(recordingFile!!, back),
                    listOf(1f, 1f),
                    mixedRecording!!,
                    { progress = it }
                )
            }
            finalizing = false
        } catch (e: Exception) {
            finalizing = false
            error = "Save recording failed: ${e.message}"
        }
    }

    fun previewRecording() {
        mixedRecording?.let { file ->
            player.open(file, 44100)
            duration = file.length() / 4
        }
    }

    private val cacheDir: File
        get() = getApplication().cacheDir

    private fun getTitle(uri: Uri): String? {
        return getApplication<ContentResolver>().query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameCol = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameCol >= 0) cursor.getString(nameCol)
                else null
            } else null
        }
    }
}
