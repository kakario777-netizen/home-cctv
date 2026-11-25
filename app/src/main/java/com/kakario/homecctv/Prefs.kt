package com.kakario.homecctv

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** 로그인 정보는 Keystore 기반 암호화 저장소에 둔다. 백업 제외(allowBackup=false). */
object Prefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val app = ctx.applicationContext
        sp = try {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app, "secure_prefs", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 암호화 저장소를 못 여는 아주 오래된 기기: 앱 전용 저장소(외부 접근 불가)로 대체
            app.getSharedPreferences("prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun p() = sp!!

    var uid: String?
        get() = p().getString("uid", null)
        set(v) = p().edit().putString("uid", v).apply()
    var email: String?
        get() = p().getString("email", null)
        set(v) = p().edit().putString("email", v).apply()
    var refreshToken: String?
        get() = p().getString("rt", null)
        set(v) = p().edit().putString("rt", v).apply()
    var cameraName: String?
        get() = p().getString("cam_name", null)
        set(v) = p().edit().putString("cam_name", v).apply()
    var viewerMuted: Boolean
        get() = p().getBoolean("viewer_muted", false)
        set(v) = p().edit().putBoolean("viewer_muted", v).apply()
    var cameraRunning: Boolean
        get() = p().getBoolean("cam_running", false)
        set(v) = p().edit().putBoolean("cam_running", v).apply()

    val cameraId: String
        get() {
            var id = p().getString("cam_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString().replace("-", "").take(16)
                p().edit().putString("cam_id", id).apply()
            }
            return id
        }

    fun clearLogin() {
        p().edit().remove("uid").remove("rt").apply()
    }
}
