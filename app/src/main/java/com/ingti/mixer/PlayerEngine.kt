package com.ingti.mixer

import android.media.*
import java.io.*
import java.nio.*
import kotlinx.coroutines.*

class PlayerEngine {
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var recordingThread: Thread? = null
    private var file: RandomAccessFile? = null
    private var rate = 44100
    private var playing = false
    private var recording = false
    private var tempo = 1f
    private var pitch = 0f
    private var loopA = -1L
    private var loopB = -1L
    var onPosition: ((Long, Long) -> Unit)? = null
    var onCompletion: (() -> Unit)? = null

    fun open(pcmFile: File, sampleRate: Int) {
        release()
        file = RandomAccessFile(pcmFile, "r")
        rate = sampleRate
        val bufferSize = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun start() {
        playing = true
        audioTrack?.play()
        playbackThread = Thread {
            val buffer = ShortArray(4096)
            val bytes = ByteArray(buffer.size * 2)
            var pos = 0L
            while (playing && file != null) {
                try {
                    val frames = readFrames(pos, buffer)
                    if (frames <= 0) {
                        playing = false
                        onCompletion?.invoke()
                        break
                    }
                    val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until frames * 2) {
                        byteBuffer.putShort(buffer[i])
                    }
                    audioTrack?.write(bytes, 0, frames * 4, AudioTrack.WRITE_BLOCKING)
                    pos += frames
                    val duration = file!!.length() / 4
                    onPosition?.invoke(pos * 1000000 / rate, duration * 1000000 / rate)
                    if (loopB > loopA && pos * 1000000 / rate >= loopB) {
                        pos = loopA * rate / 1000000
                    }
                } catch (e: Exception) {
                    playing = false
                    break
                }
            }
            audioTrack?.stop()
        }.apply { start() }
    }

    fun pause() {
        playing = false
        audioTrack?.pause()
        playbackThread?.join(1000)
    }

    fun seek(posMs: Long) {
        if (file != null) {
            val frames = posMs * rate / 1000000
            // Position will be picked up by playback thread
        }
    }

    fun setTempo(newTempo: Float, newPitch: Float) {
        tempo = newTempo
        pitch = newPitch
        // TODO: Implement tempo/pitch shifting via rubberband or similar
    }

    fun startRecording(output: File, latencyMs: Int) {
        recording = true
        val bufferSize = AudioRecord.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
        recorder.startRecording()
        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            output.outputStream().buffered().use { out ->
                while (recording) {
                    val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) out.write(buffer, 0, read)
                }
            }
            recorder.stop()
            recorder.release()
        }.apply { start() }
    }

    fun stopRecording() {
        recording = false
        recordingThread?.join(1000)
    }

    private fun readFrames(pos: Long, buffer: ShortArray): Int {
        return if (file != null) {
            file!!.seek(pos * 4)
            val bytes = ByteArray(buffer.size * 2)
            val read = file!!.read(bytes)
            if (read <= 0) return 0
            val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until read / 2) {
                buffer[i] = byteBuffer.getShort()
            }
            read / 2
        } else 0
    }

    fun release() {
        pause()
        stopRecording()
        file?.close()
        file = null
        audioTrack?.release()
        audioTrack = null
    }
}
