package com.ingti.mixer

import android.content.Context
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class StudioEngine(private val context: Context) {
    var demucs: Demucs? = null
    var tunerActive = false
    var tunerFrequency = 0.0
    var tunerNote = ""
    var solo = -1
    val project = Project()

    fun hasStem(kind: String): Boolean = project.tracks.any { it.name == kind } ||
        (demucs != null && Demucs.names.any { it.lowercase() == kind.lowercase() })

    fun addTrack(uri: Uri) = project.addTrack(context, uri)

    fun soloTrack(index: Int) {
        solo = if (solo == index) -1 else index
    }

    fun mixDown() {
        project.mixDown(context)
    }

    fun export(uri: Uri, kind: String) {
        project.export(context, uri, kind)
    }

    fun saveProject(uri: Uri) {
        project.save(context, uri)
    }

    fun openProject(uri: Uri) {
        project.open(context, uri)
    }

    fun startTuner() {
        // Placeholder for tuner implementation
        tunerActive = true
    }

    fun stopTuner() {
        tunerActive = false
    }

    fun release() {
        demucs?.close()
        demucs = null
        stopTuner()
    }
}

class Project {
    data class Track(
        val name: String,
        val dir: String,
        val file: String,
        var gain: Float = 1f,
        var mute: Boolean = false,
    )

    val tracks = mutableListOf<Track>()
    var format = "WAV"

    fun addTrack(context: Context, uri: Uri) {
        // Simplified: just decode to a PCM file in cache
        val name = "Track ${tracks.size + 1}"
        val dir = File(context.cacheDir, "track-${System.currentTimeMillis()}").apply { mkdirs() }
        val out = File(dir, "audio.pcm")
        // TODO: Actually decode the audio file
        tracks.add(Track(name, dir.absolutePath, "audio.pcm"))
    }

    fun hasStem(kind: String): Boolean = tracks.any { it.name.equals(kind, ignoreCase = true) }

    fun mixDown(context: Context) {
        // TODO: Mix all tracks down
    }

    fun export(context: Context, uri: Uri, kind: String) {
        // TODO: Export specific stem or mix
    }

    fun save(context: Context, uri: Uri) {
        // TODO: Save project as zip
    }

    fun open(context: Context, uri: Uri) {
        // TODO: Open project from zip
    }
}
