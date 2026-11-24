package com.kakario.homecctv

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 자동 업데이트: 이 앱 저장소의 최신 릴리스에서만 HTTPS로 받는다. 주소를 바꾸는 설정은 없다. */
object Updater {
    private var checked = false

    fun check(act: Activity) {
        if (checked) return
        checked = true
        Thread {
            try {
                val rel = JSONObject(Http.request(Config.RELEASES_API, headers = mapOf("Accept" to "application/vnd.github+json")))
                val tag = rel.optString("tag_name").removePrefix("v")
                if (!newer(tag, BuildConfig.VERSION_NAME)) return@Thread
                val assets = rel.optJSONArray("assets") ?: return@Thread
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                val u = url ?: return@Thread
                if (!u.startsWith("https://github.com/kakario777-netizen/home-cctv/releases/download/")) return@Thread
                val file = File(act.cacheDir, "update.apk")
                download(u, file)
                if (!verify(act, file)) { file.delete(); return@Thread }
                act.runOnUiThread { install(act, file, tag) }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun newer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun download(url: String, out: File) {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 30000
            val code = c.responseCode
            if (code in 300..399) {
                u = c.getHeaderField("Location") ?: throw Exception("redirect")
                if (!u.startsWith("https://")) throw Exception("not https")
                c.disconnect(); return@repeat
            }
            if (code != 200) throw Exception("download $code")
            c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            c.disconnect()
            return
        }
        throw Exception("too many redirects")
    }

    /** 같은 서명 키로 서명됐고 버전이 더 높은 같은 앱인지 확인 */
    @Suppress("DEPRECATION")
    private fun verify(act: Activity, file: File): Boolean {
        val pm = act.packageManager
        val pkg = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES) ?: return false
        if (pkg.packageName != act.packageName) return false
        val mine = pm.getPackageInfo(act.packageName, PackageManager.GET_SIGNATURES)
        if (pkg.versionCode <= mine.versionCode) return false
        val a = pkg.signatures?.map { it.toCharsString() }?.toSet() ?: return false
        val b = mine.signatures?.map { it.toCharsString() }?.toSet() ?: return false
        return a.isNotEmpty() && a == b
    }

    private fun install(act: Activity, file: File, tag: String) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("새 버전 $tag")
                .setMessage("자동 업데이트를 하려면 '이 출처 허용'을 한 번 켜 주세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    checked = false
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                }
                .setNegativeButton("나중에", null)
                .show()
            return
        }
        Toast.makeText(act, "새 버전 $tag 설치 중... 잠시 후 앱이 다시 시작돼요.", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val installer = act.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("app", 0, file.length()).use { o ->
                        file.inputStream().use { it.copyTo(o) }
                        s.fsync(o)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(act, 7, Intent(act, InstallReceiver::class.java), flags)
                    s.commit(pi.intentSender)
                }
            } catch (_: Exception) {
            }
        }.start()
    }
}
