package dev.shalaga44.you_are_okay

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


object RequestSender {
    private val exec = Executors.newSingleThreadExecutor()

    fun postObject(url: String, obj: JSONObject, tag: String = "POST") {
        postJson(url, obj.toString(), tag)
    }

    fun postArray(url: String, arr: JSONArray, tag: String = "POST") {
        postJson(url, arr.toString(), tag)
    }

    private fun postJson(urlStr: String, payload: String, tag: String) {
        exec.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                BufferedOutputStream(conn.outputStream).use { out ->
                    val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                    out.write(bytes)
                    out.flush()
                }

                val code = conn.responseCode
                val body = try {
                    BufferedReader(InputStreamReader(
                        if (code in 200..299) conn.inputStream else conn.errorStream,
                        StandardCharsets.UTF_8
                    )).use { it.readText() }
                } catch (_: Throwable) { "" }

                Log.d("RequestSender", "[$tag] $urlStr -> $code ${body.take(200)}")
            } catch (t: Throwable) {
                Log.e("RequestSender", "[$tag] POST $urlStr failed: ${t.message}", t)
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun shutdown() {
        exec.shutdown()
        try { exec.awaitTermination(2, TimeUnit.SECONDS) } catch (_: Throwable) {}
    }
}