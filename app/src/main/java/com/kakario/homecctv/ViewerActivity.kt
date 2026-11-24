package com.kakario.homecctv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID

class ViewerActivity : Activity() {

    private var pc: PeerConnection? = null
    private var renderer: SurfaceViewRenderer? = null
    private var micTrack: AudioTrack? = null
    private var micSource: AudioSource? = null
    private var answerListener: Rtdb.Listener? = null
    private var camId: String? = null
    private var statusView: TextView? = null
    @Volatile private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Auth.init(this)
        Rtc.init(this)
        showList()
    }

    private fun showList() {
        val col = column()
        title(col, "카메라 보기")
        val info = label(col, "카메라 목록을 불러오는 중...")
        setScrollContent(col)
        Thread {
            val cams = try { Rtdb.get("cameras") as? JSONObject } catch (e: Exception) { null }
            runOnUiThread {
                if (cams == null || cams.length() == 0) {
                    info.text = "등록된 카메라가 없어요.\n카메라로 쓸 폰에서 '이 폰을 카메라로 쓰기'를 켜 주세요."
                    bigButton(col, "새로고침") { showList() }
                    return@runOnUiThread
                }
                info.text = "볼 카메라를 누르세요."
                val now = System.currentTimeMillis()
                for (key in cams.keys()) {
                    val c = cams.getJSONObject(key)
                    val online = now - c.optLong("lastSeen", 0) < 3 * 60_000
                    val name = c.optString("name", "카메라")
                    bigButton(col, if (online) "● $name" else "○ $name (꺼짐)") {
                        if (online) startView(key, name) else info.text = "$name 카메라가 꺼져 있어요."
                    }
                }
                bigButton(col, "새로고침") { showList() }
            }
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startView(id: String, name: String) {
        camId = id
        closed = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val r = SurfaceViewRenderer(this)
        r.init(Rtc.egl.eglBaseContext, null)
        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        r.setEnableHardwareScaler(true)
        renderer = r
        root.addView(r, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val status = TextView(this).apply {
            text = "$name 연결하는 중..."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        statusView = status
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(20))
        }
        val talk = Button(this).apply { text = "누르고 말하기" }
        talk.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (hasMic()) { micTrack?.setEnabled(true); talk.text = "말하는 중..." }
                    else if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { micTrack?.setEnabled(false); talk.text = "누르고 말하기" }
            }
            false
        }
        val hang = Button(this).apply { text = "끊기"; setOnClickListener { hangup(); showList() } }
        bar.addView(talk, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        bar.addView(hang, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        Thread { connect(id, name, r) }.start()
    }

    private fun hasMic() = Build.VERSION.SDK_INT < 23 ||
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(s: String) = runOnUiThread { statusView?.text = s }

    private fun connect(id: String, name: String, r: SurfaceViewRenderer) {
        try {
            val conn = Rtc.factory.createPeerConnection(Rtc.config(), object : Rtc.Observer() {
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    when (s) {
                        PeerConnection.IceConnectionState.CONNECTED -> setStatus(name)
                        PeerConnection.IceConnectionState.DISCONNECTED -> setStatus("$name 연결이 불안정해요...")
                        PeerConnection.IceConnectionState.FAILED -> setStatus(
                            "$name 연결 실패\n두 폰의 인터넷 환경상 직접 연결이 안 될 수 있어요."
                        )
                        else -> {}
                    }
                }
            }) ?: throw Exception("pc")
            pc = conn
            val video = conn.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            (video.receiver.track() as? VideoTrack)?.addSink(r)
            micSource = Rtc.factory.createAudioSource(MediaConstraints())
            val mic = Rtc.factory.createAudioTrack("view_mic", micSource)
            mic.setEnabled(false)
            micTrack = mic
            conn.addTransceiver(mic, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
            speaker(true)

            Rtc.setLocal(conn, Rtc.createOffer(conn))
            val offer = Rtc.gatheredLocal(conn)
            if (closed) return
            val sid = UUID.randomUUID().toString()
            Rtdb.put("calls/$id", JSONObject().put("sid", sid).put("offer", Rtc.toJson(offer)))

            var applied = false
            answerListener = Rtdb.Listener("calls/$id") {
                if (applied || closed) return@Listener
                val node = Rtdb.get("calls/$id") as? JSONObject ?: return@Listener
                if (node.optString("sid") != sid) return@Listener
                val ans = node.optJSONObject("answer") ?: return@Listener
                applied = true
                Rtc.setRemote(conn, Rtc.fromJson(ans))
            }.start()

            Thread.sleep(20_000)
            if (!applied && !closed) setStatus("$name 응답이 없어요. 카메라 폰이 켜져 있는지 확인해 주세요.")
        } catch (e: Exception) {
            if (!closed) setStatus("$name 연결 실패")
        }
    }

    @Suppress("DEPRECATION")
    private fun speaker(on: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = if (on) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        am.isSpeakerphoneOn = on
    }

    private fun hangup() {
        if (closed) return
        closed = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        answerListener?.stop()
        answerListener = null
        val p = pc
        val r = renderer
        val mt = micTrack
        val ms = micSource
        pc = null; renderer = null; micTrack = null; micSource = null
        val id = camId
        Thread {
            try { p?.dispose() } catch (_: Exception) {}
            try { mt?.dispose() } catch (_: Exception) {}
            try { ms?.dispose() } catch (_: Exception) {}
            if (id != null) try { Rtdb.delete("calls/$id") } catch (_: Exception) {}
        }.start()
        try { r?.release() } catch (_: Exception) {}
        speaker(false)
    }

    override fun onBackPressed() {
        if (pc != null || renderer != null) { hangup(); showList() } else super.onBackPressed()
    }

    override fun onDestroy() {
        hangup()
        super.onDestroy()
    }
}
