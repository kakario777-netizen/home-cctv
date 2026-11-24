package com.kakario.homecctv

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** WebRTC 공용 도구. 영상은 폰끼리 직접(P2P)만 오가고 중계 서버는 쓰지 않는다. */
object Rtc {
    lateinit var egl: EglBase
    lateinit var factory: PeerConnectionFactory
    private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        val app = ctx.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        egl = EglBase.create()
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        ready = true
    }

    fun config(): PeerConnection.RTCConfiguration {
        // STUN은 "내 주소 확인"용일 뿐, 영상이 지나가지 않는다. TURN(중계)은 넣지 않음.
        val servers = Config.STUN.map { PeerConnection.IceServer.builder(it).createIceServer() }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
    }

    open class Observer : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private class Sdp : SdpObserver {
        val latch = CountDownLatch(1)
        var result: SessionDescription? = null
        var error: String? = null
        override fun onCreateSuccess(s: SessionDescription?) { result = s; latch.countDown() }
        override fun onSetSuccess() { latch.countDown() }
        override fun onCreateFailure(e: String?) { error = e; latch.countDown() }
        override fun onSetFailure(e: String?) { error = e; latch.countDown() }
        fun await() {
            if (!latch.await(10, TimeUnit.SECONDS)) throw Exception("sdp timeout")
            error?.let { throw Exception(it) }
        }
    }

    fun createOffer(pc: PeerConnection): SessionDescription {
        val o = Sdp(); pc.createOffer(o, MediaConstraints()); o.await(); return o.result!!
    }

    fun createAnswer(pc: PeerConnection): SessionDescription {
        val o = Sdp(); pc.createAnswer(o, MediaConstraints()); o.await(); return o.result!!
    }

    fun setLocal(pc: PeerConnection, sdp: SessionDescription) {
        val o = Sdp(); pc.setLocalDescription(o, sdp); o.await()
    }

    fun setRemote(pc: PeerConnection, sdp: SessionDescription) {
        val o = Sdp(); pc.setRemoteDescription(o, sdp); o.await()
    }

    /** 주소 후보를 다 모은 뒤 SDP를 한 번에 주고받는다 (신호 횟수 최소화). */
    fun gatheredLocal(pc: PeerConnection, timeoutMs: Long = 6000): SessionDescription {
        val end = System.currentTimeMillis() + timeoutMs
        while (pc.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE &&
            System.currentTimeMillis() < end
        ) Thread.sleep(100)
        return pc.localDescription
    }

    fun toJson(s: SessionDescription): JSONObject =
        JSONObject().put("type", s.type.canonicalForm()).put("sdp", s.description)

    fun fromJson(j: JSONObject): SessionDescription =
        SessionDescription(SessionDescription.Type.fromCanonicalForm(j.getString("type")), j.getString("sdp"))
}
