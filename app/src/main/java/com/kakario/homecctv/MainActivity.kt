package com.kakario.homecctv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Auth.init(this)
        Updater.check(this)
    }

    override fun onResume() {
        super.onResume()
        if (Auth.loggedIn) showHome() else showLogin()
    }

    private fun showLogin() {
        val col = column()
        title(col, "홈 CCTV")
        label(col, "카메라 폰과 보는 폰에 같은 계정으로 로그인해 주세요.\n처음이면 이메일과 비밀번호(6자 이상)를 정하고 '계정 만들기'를 누르면 돼요.")
        val email = EditText(this).apply {
            hint = "이메일"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(Prefs.email ?: "")
        }
        col.addView(email)
        val pw = EditText(this).apply {
            hint = "비밀번호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(pw)
        fun go(create: Boolean) {
            val e = email.text.toString().trim()
            val p = pw.text.toString()
            if (e.isEmpty() || p.isEmpty()) { toast("이메일과 비밀번호를 넣어 주세요."); return }
            Thread {
                try {
                    Auth.signIn(e, p, create)
                    runOnUiThread { showHome() }
                } catch (ex: Exception) {
                    runOnUiThread { toast(Auth.friendlyError(ex)) }
                }
            }.start()
        }
        bigButton(col, "로그인") { go(false) }
        bigButton(col, "계정 만들기") { go(true) }
        label(col, "버전 ${BuildConfig.VERSION_NAME}", 12f)
        setScrollContent(col)
    }

    private fun showHome() {
        if (Prefs.cameraRunning && !CameraService.running) {
            // 업데이트 등으로 꺼졌던 카메라 모드는 앱을 열면 다시 켠다
            startActivity(Intent(this, CameraActivity::class.java))
        }
        val col = column()
        title(col, "홈 CCTV")
        label(col, "${Prefs.email ?: ""} 로그인됨", 13f)
        bigButton(col, "이 폰을 카메라로 쓰기") { startActivity(Intent(this, CameraActivity::class.java)) }
        bigButton(col, "카메라 보기") { startActivity(Intent(this, ViewerActivity::class.java)) }
        bigButton(col, "로그아웃") {
            CameraService.stop(this)
            Auth.signOut()
            showLogin()
        }
        label(col, "버전 ${BuildConfig.VERSION_NAME}", 12f)
        setScrollContent(col)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
