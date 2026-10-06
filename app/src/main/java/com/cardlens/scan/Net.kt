package com.cardlens.scan

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    // tcgcsv asks every client to send its own User-Agent.
    const val USER_AGENT = "CardLensScan/1.0 (personal shop scanner)"

    fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            return body
        } finally {
            conn.disconnect()
        }
    }

    fun postJson(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}

/** Simple file cache so a busy afternoon doesn't re-download the same set over and over. */
class DiskCache(private val dir: File) {
    init {
        dir.mkdirs()
    }

    private fun fileFor(url: String): File {
        val safe = url.substringAfter("://").replace(Regex("[^A-Za-z0-9]"), "_").takeLast(120)
        return File(dir, safe + "_" + Integer.toHexString(url.hashCode()) + ".json")
    }

    fun get(url: String, maxAgeMs: Long): String {
        val f = fileFor(url)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) {
            return f.readText()
        }
        return try {
            val text = Http.get(url)
            f.writeText(text)
            Thread.sleep(100) // tcgcsv asks for ~100ms between requests
            text
        } catch (e: Exception) {
            // Offline or rate limited: fall back to an older copy if we have one.
            if (f.exists()) f.readText() else throw e
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
