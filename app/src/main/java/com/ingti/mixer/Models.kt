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

    private suspend fun hash(f: File): String =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    digest.update(buffer, 0, len)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    suspend fun download(progress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val url = URL(entry.getString("url"))
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()
            require(connection.responseCode == 200) { "Download failed: ${connection.responseMessage}" }
            val total = connection.contentLengthLong()
            var downloaded = 0L
            file.outputStream().buffered().use { output ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        output.write(buffer, 0, len)
                        downloaded += len
                        if (total > 0) progress(downloaded.toFloat() / total)
                    }
                }
            }
            require(verify()) { "Checksum mismatch after download" }
            file
        }

    companion object {
        fun getModelDir(context: Context) = File(context.filesDir, "models")
    }
}
