package com.ingti.mixer

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONObject

class Models(private val context: Context, index: Int = 0) {
    val entry =
        JSONObject(
                context.assets.open("model_manifest.json").bufferedReader().use { it.readText() }
            )
            .getJSONArray("models")
            .getJSONObject(index)
    val file = File(context.filesDir, "models/${entry.getString("filename")}")

    fun installed() = file.exists() && file.length() == entry.getLong("bytes")

    suspend fun verify(): Boolean =
        withContext(Dispatchers.IO) { file.exists() && hash(file) == entry.getString("sha256") }

    private suspend fun hash(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val b = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(b)
                if (n < 0) break
                digest.update(b, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun download(progress: (Float) -> Unit): File {
        file.parentFile?.mkdirs()
        val url = URL(entry.getString("url"))
        val connection = url.openConnection() as HttpURLConnection
        connection.requestProperty("User-Agent", "IngtiMixer/0.3")
        connection.connect()
        require(connection.responseCode == 200) { "Download failed: ${connection.responseMessage}" }
        val length = connection.contentLengthLong()
        require(length == entry.getLong("bytes")) { "Content length mismatch" }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        connection.inputStream.buffered().use { input ->
            tmp.outputStream().buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    downloaded += n
                    progress(downloaded.toFloat() / length)
                }
            }
        }
        require(tmp.renameTo(file)) { "Failed to finalize model file" }
        return file
    }

    companion object {
        fun getDefault(context: Context) = Models(context, 0)
    }
}
