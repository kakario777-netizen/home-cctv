package com.kakario.homecctv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors

/** 카메라 폰: 화면이 꺼져도 대기하다가 뷰어가 접속하면 영상·소리를 보낸다. */
class CameraService : Service() {

    companion object {
        const val CHANNEL = "camera"
        @Volatile var status: String = "꺼짐"
        @Volatile var running = false

        fun start(ctx: Context) {
            val i = Intent(ctx, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CameraService::class.java))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var listener: Rtdb.Listener? = null
    private var heartbeat: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var pc: PeerConnection? = null
    private var currentSid: String? = null
    private var capturer: CameraVideoCapturer? = null
    private var texHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private val camPath get() = "cameras/${Prefs.cameraId}"
    private val callPath get() = "calls/${Prefs.cameraId}"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Auth.init(this)
        Rtc.init(this)
        val type = if (Build.VERSION.SDK_INT >= 30)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, 1, notification("대기 중"), type)
        running = true
        Prefs.cameraRunning = true
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homecctv:camera").apply { acquire() }
        setStatus("대기 중")

        heartbeat = Thread({
            while (running) {
                try {
                    Rtdb.put(
                        camPath, JSONObject()
                            .put("name", Prefs.cameraName ?: Build.MODEL)
                            .put("lastSeen", JSONObject().put(".sv", "timestamp"))
                    )
                } catch (_: Exception) {}
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
            }
        }, "heartbeat").apply { start() }

        listener = Rtdb.Listener(callPath) { worker.execute { checkCall() } }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        Prefs.cameraRunning = false
        listener?.stop()
        heartbeat?.interrupt()
        worker.execute { closeCall() }
        worker.shutdown()
        Thread {
            try { Rtdb.patch(camPath, JSONObject().put("lastSeen", 0)) } catch (_: Exception) {}
        }.start()
        try { wakeLock?.release() } catch (_: Exception) {}
        setStatus("꺼짐")
        super.onDestroy()
    }

    private fun checkCall() {
        val node = try { Rtdb.get(callPath) as? JSONObject } catch (_: Exception) { return }
        if (node == null) {
            if (pc != null) { closeCall(); setStatus("대기 중") }
            return
        }
        val sid = node.optString("sid")
        val offer = node.optJSONObject("offer") ?: return
        if (sid.isEmpty() || sid == currentSid || node.has("answer")) return
        try {
            answer(sid, offer)
        } catch (e: Exception) {
            closeCall()
            setStatus("대기 중")
        }
    }

    private fun answer(sid: String, offer: JSONObject) {
        closeCall()
        currentSid = sid
        setStatus("연결하는 중")
        val conn = Rtc.factory.createPeerConnection(Rtc.config(), object : Rtc.Observer() {
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED -> setStatus("보는 중")
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> worker.execute {
                        if (currentSid == sid) { closeCall(); setStatus("대기 중") }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> worker.execute {
                        Thread.sleep(8000)
                        val p = pc
                        if (currentSid == sid && p != null &&
                            p.iceConnectionState() != PeerConnection.IceConnectionState.CONNECTED
                        ) { closeCall(); setStatus("대기 중") }
                    }
                    else -> {}
                }
            }
        }) ?: throw Exception("pc")
        pc = conn
        Rtc.setRemote(conn, Rtc.fromJson(offer))
        startMedia()
        for (t in conn.transceivers) {
            when (t.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    t.sender.setTrack(videoTrack, false)
                    t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                }
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                    t.sender.setTrack(audioTrack, false)
                    t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                }
                else -> {}
            }
        }
        speaker(true)
        Rtc.setLocal(conn, Rtc.createAnswer(conn))
        val local = Rtc.gatheredLocal(conn)
        Rtdb.patch(callPath, JSONObject().put("answer", Rtc.toJson(local)))
    }

    private fun startMedia() {
        if (capturer == null) {
            val enumerator: CameraEnumerator =
                if (Camera2Enumerator.isSupported(this)) Camera2Enumerator(this) else Camera1Enumerator(true)
            val names = enumerator.deviceNames
            val name = names.firstOrNull { enumerator.isBackFacing(it) } ?: names.firstOrNull()
                ?: throw Exception("no camera")
            val cap = enumerator.createCapturer(name, null)
            texHelper = SurfaceTextureHelper.create("cam", Rtc.egl.eglBaseContext)
            videoSource = Rtc.factory.createVideoSource(false)
            cap.initialize(texHelper, this, videoSource!!.capturerObserver)
            cap.startCapture(1280, 720, 20)
            capturer = cap
            videoTrack = Rtc.factory.createVideoTrack("cam_v", videoSource)
        }
        if (audioTrack == null) {
            audioSource = Rtc.factory.createAudioSource(MediaConstraints())
            audioTrack = Rtc.factory.createAudioTrack("cam_a", audioSource)
        }
    }

    private fun closeCall() {
        currentSid = null
        try { pc?.dispose() } catch (_: Exception) {}
        pc = null
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { texHelper?.dispose() } catch (_: Exception) {}
        texHelper = null
        try { audioTrack?.dispose() } catch (_: Exception) {}
        audioTrack = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        speaker(false)
    }

    @Suppress("DEPRECATION")
    private fun speaker(on: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = if (on) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        am.isSpeakerphoneOn = on
    }

    private fun setStatus(s: String) {
        status = s
        if (running) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification(s))
        }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "카메라", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 0, Intent(this, CameraActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("홈 CCTV 카메라")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
