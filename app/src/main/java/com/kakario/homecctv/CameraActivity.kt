package com.kakario.homecctv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class CameraActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView

    private val tick = object : Runnable {
        override fun run() {
            statusView.text = "상태: ${CameraService.status}"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Auth.init(this)
        val col = column()
        title(col, "카메라 모드")
        label(col, "이 폰 이름 (보는 폰 목록에 나와요)")
        val name = EditText(this).apply { setText(Prefs.cameraName ?: Build.MODEL) }
        col.addView(name)
        statusView = label(col, "상태: ${CameraService.status}", 18f)
        bigButton(col, "카메라 켜기") {
            Prefs.cameraName = name.text.toString().trim().ifEmpty { Build.MODEL }
            if (askPermissions()) startCamera()
        }
        bigButton(col, "카메라 끄기") { CameraService.stop(this) }
        label(col, "켜 두면 화면을 꺼도 계속 대기해요. 충전기를 꽂아 두는 걸 권해요.\n보는 폰이 접속할 때만 카메라가 켜져서 배터리를 아껴요.", 13f)
        setScrollContent(col)
        if (Prefs.cameraRunning && !CameraService.running && hasPermissions()) startCamera()
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun needed(): Array<String> {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun hasPermissions(): Boolean = Build.VERSION.SDK_INT < 23 ||
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun askPermissions(): Boolean {
        if (hasPermissions()) return true
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(needed(), 1)
        return false
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (hasPermissions()) startCamera()
        else Toast.makeText(this, "카메라와 마이크 권한이 있어야 해요.", Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        CameraService.start(this)
    }
}
