package com.kakario.homecctv

import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Realtime Database REST. 경로는 항상 users/{uid}/ 아래만 쓴다 (보안 규칙으로 본인만 접근). */
object Rtdb {
    private fun url(path: String) = "${Config.DB_URL}/users/${Auth.uid}/$path.json?auth=${Auth.token()}"

    fun get(path: String): Any? {
        val t = Http.request(url(path))
        return if (t.isBlank() || t == "null") null else JSONTokener(t).nextValue()
    }

    fun put(path: String, value: JSONObject) { Http.request(url(path), "PUT", value.toString()) }
    fun patch(path: String, value: JSONObject) { Http.request(url(path), "PATCH", value.toString()) }
    fun delete(path: String) { Http.request(url(path), "DELETE") }

    /** 서버 이벤트 스트림으로 해당 경로가 바뀔 때마다 onChange 호출. 끊기면 자동 재연결. */
    class Listener(private val path: String, private val onChange: () -> Unit) {
        @Volatile private var stopped = false
        @Volatile private var conn: HttpURLConnection? = null

        fun start(): Listener {
            Thread({ loop() }, "rtdb-$path").start()
            return this
        }

        fun stop() {
            stopped = true
            try { conn?.disconnect() } catch (_: Exception) {}
        }

        private fun loop() {
            var backoff = 500L
            while (!stopped) {
                try {
                    val c = URL(url(path)).openConnection() as HttpURLConnection
                    conn = c
                    c.setRequestProperty("Accept", "text/event-stream")
                    c.connectTimeout = 15000
                    c.readTimeout = 90000
                    if (c.responseCode != 200) throw Exception("sse ${c.responseCode}")
                    backoff = 500L
                    val opened = System.currentTimeMillis()
                    BufferedReader(InputStreamReader(c.inputStream)).use { r ->
                        var event = ""
                        while (!stopped) {
                            val line = r.readLine() ?: break
                            if (line.startsWith("event:")) event = line.substring(6).trim()
                            else if (line.startsWith("data:")) {
                                when (event) {
                                    "put", "patch" -> try { onChange() } catch (_: Exception) {}
                                    "auth_revoked", "cancel" -> break
                                }
                            }
                            // 토큰 만료 전에 새로 연결
                            if (System.currentTimeMillis() - opened > 45 * 60_000L) break
                        }
                    }
                    c.disconnect()
                } catch (_: Exception) {
                    if (stopped) return
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) {}
                    backoff = (backoff * 2).coerceAtMost(3_000L)
                }
            }
        }
    }
}
