package com.kakario.homecctv

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/** 파이어베이스 이메일 로그인 (REST). 플레이 서비스 없이도 동작. 토큰은 로그에 남기지 않는다. */
object Auth {
    private var idToken: String? = null
    private var expiresAt = 0L
    private var androidHeaders: Map<String, String> = emptyMap()

    fun init(ctx: Context) {
        Prefs.init(ctx)
        if (androidHeaders.isEmpty()) {
            androidHeaders = mapOf(
                "X-Android-Package" to ctx.packageName,
                "X-Android-Cert" to certSha1(ctx),
            )
        }
    }

    val loggedIn get() = Prefs.refreshToken != null && Prefs.uid != null
    val uid get() = Prefs.uid!!

    @Throws(Exception::class)
    fun signIn(email: String, password: String, create: Boolean) {
        val api = if (create) "accounts:signUp" else "accounts:signInWithPassword"
        val body = JSONObject()
            .put("email", email).put("password", password).put("returnSecureToken", true)
        val res = JSONObject(
            Http.request(
                "https://identitytoolkit.googleapis.com/v1/$api?key=${Config.API_KEY}",
                "POST", body.toString(), headers = androidHeaders
            )
        )
        Prefs.uid = res.getString("localId")
        Prefs.email = email
        Prefs.refreshToken = res.getString("refreshToken")
        idToken = res.getString("idToken")
        expiresAt = System.currentTimeMillis() + res.optString("expiresIn", "3600").toLong() * 1000 - 120_000
    }

    @Synchronized
    @Throws(Exception::class)
    fun token(): String {
        val t = idToken
        if (t != null && System.currentTimeMillis() < expiresAt) return t
        val rt = Prefs.refreshToken ?: throw IllegalStateException("로그인 필요")
        val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8")
        val res = JSONObject(
            Http.request(
                "https://securetoken.googleapis.com/v1/token?key=${Config.API_KEY}",
                "POST", form, "application/x-www-form-urlencoded", androidHeaders
            )
        )
        Prefs.refreshToken = res.getString("refresh_token")
        val nt = res.getString("id_token")
        idToken = nt
        expiresAt = System.currentTimeMillis() + res.optString("expires_in", "3600").toLong() * 1000 - 120_000
        return nt
    }

    fun signOut() {
        idToken = null
        Prefs.clearLogin()
    }

    fun friendlyError(e: Exception): String {
        val msg = (e as? Http.HttpError)?.body ?: return "네트워크 오류가 났어요. 인터넷 연결을 확인해 주세요."
        return when {
            "EMAIL_EXISTS" in msg -> "이미 있는 계정이에요. 로그인을 눌러 주세요."
            "INVALID_LOGIN_CREDENTIALS" in msg || "INVALID_PASSWORD" in msg || "EMAIL_NOT_FOUND" in msg ->
                "이메일이나 비밀번호가 맞지 않아요."
            "WEAK_PASSWORD" in msg -> "비밀번호는 6자 이상으로 해 주세요."
            "INVALID_EMAIL" in msg -> "이메일 형식을 확인해 주세요."
            "TOO_MANY_ATTEMPTS" in msg -> "시도가 너무 많아요. 잠시 후 다시 해 주세요."
            else -> "로그인에 실패했어요."
        }
    }

    @Suppress("DEPRECATION")
    private fun certSha1(ctx: Context): String = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
        val sig = info.signatures!![0].toByteArray()
        MessageDigest.getInstance("SHA-1").digest(sig).joinToString("") { "%02X".format(it) }
    } catch (e: Exception) {
        ""
    }
}
