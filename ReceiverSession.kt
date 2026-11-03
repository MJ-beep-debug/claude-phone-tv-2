package com.wifitv.receiver

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.TimeUnit

enum class State { IDLE, CONNECTING_SERVER, WAITING_SENDER, NEGOTIATING, STREAMING, RECONNECTING, ERROR }

/**
 * Owns the signaling WebSocket and the WebRTC peer connection. One instance per process.
 * Designed around a "sender slot": today there is exactly one active sender; the signaling
 * protocol already carries sender identity, so several slots could be added later.
 */
object ReceiverSession {
    interface Listener {
        fun onState(state: State, detail: String)
        fun onVideo(track: VideoTrack?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)       // detects dead Wi-Fi links
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    var state = State.IDLE; private set
    var detail = ""; private set
    var senderName: String? = null; private set
    var videoTrack: VideoTrack? = null; private set
    var eglBase: EglBase? = null; private set

    private var listener: Listener? = null
    private var appCtx: Context? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var ws: WebSocket? = null
    private var active = false
    private var attempt = 0
    private var hostPort = ""
    private var code = ""
    private var height = 720
    private var fps = 30
    private var audioTrack: AudioTrack? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var generation = 0                 // invalidates callbacks of old sockets / peers
    private var pendingCandidates = mutableListOf<IceCandidate>()
    private val disconnectTimer = Runnable { requestNewOffer("Stream interrupted") }

    fun setListener(l: Listener?) { listener = l; if (l != null) { l.onState(state, detail); l.onVideo(videoTrack) } }

    fun start(ctx: Context, hostPort: String, code: String, height: Int, fps: Int) {
        main.post {
            if (active) stopInternal(State.IDLE, "")
            appCtx = ctx.applicationContext
            this.hostPort = hostPort; this.code = code; this.height = height; this.fps = fps
            active = true; attempt = 0
            initFactory()
            routeAudio(true)
            connectWs()
        }
    }

    fun stop() { main.post { stopInternal(State.IDLE, "Stopped") } }

    fun setQuality(h: Int, f: Int) {
        main.post { height = h; fps = f; sendConfig() }
    }

    fun setMuted(muted: Boolean) { main.post { audioTrack?.setEnabled(!muted) } }

    // ------------------------------------------------------------------ signaling
    private fun connectWs() {
        if (!active) return
        val gen = ++generation
        setState(if (attempt == 0) State.CONNECTING_SERVER else State.RECONNECTING,
            if (attempt == 0) "Connecting to $hostPort…" else "Reconnecting to $hostPort…")
        val name = (Build.MANUFACTURER + " " + Build.MODEL).take(60)
        val url = "ws://$hostPort/ws?role=receiver&code=$code&name=" + java.net.URLEncoder.encode(name, "UTF-8")
        ws?.cancel()
        val req = try { Request.Builder().url(url).build() } catch (e: IllegalArgumentException) {
            fatal("The laptop address \"$hostPort\" is not valid. Use the form 192.168.1.20:8765"); return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (gen != generation) return@post
                    attempt = 0
                    setState(State.WAITING_SENDER, "Waiting for a sender. Enter code $code on the laptop.")
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { main.post { if (gen == generation) onSignal(text) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.post { if (gen == generation) lost(t.message ?: "network error") } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post { if (gen == generation) lost("closed ($code)") } }
        })
    }

    private fun lost(why: String) {
        if (!active) return
        closePeer()
        attempt++
        val delay = minOf(8000L, 1000L * attempt)
        setState(State.RECONNECTING, "Connection lost ($why). Retrying in ${delay / 1000}s…")
        val gen = generation
        main.postDelayed({ if (active && gen == generation) connectWs() }, delay)
    }

    private fun fatal(msg: String) {
        stopInternal(State.ERROR, msg)
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun sendConfig() {
        if (ws != null) send(JSONObject().put("type", "config").put("height", height).put("fps", fps))
    }

    private fun onSignal(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        when (m.optString("type")) {
            "sender-joined" -> {
                senderName = m.optString("name").ifEmpty { "Unknown device" } + " (" + m.optString("ip") + ")"
                setState(State.NEGOTIATING, "Sender connected: $senderName")
                sendConfig()
            }
            "sender-left" -> {
                closePeer(); senderName = null
                setState(State.WAITING_SENDER, "Sender disconnected. Waiting for a sender. Code: $code")
            }
            "offer" -> handleOffer(m.getString("sdp"))
            "candidate" -> {
                val c = IceCandidate(m.optString("sdpMid"), m.optInt("sdpMLineIndex"), m.getString("candidate"))
                val p = pc
                if (p != null && p.remoteDescription != null) p.addIceCandidate(c) else pendingCandidates.add(c)
            }
            "bye" -> { closePeer(); setState(State.WAITING_SENDER, "Sender stopped casting. Waiting… Code: $code") }
            "error" -> if (m.optBoolean("fatal")) fatal(m.optString("message", "Server error"))
        }
    }

    // ------------------------------------------------------------------ WebRTC
    private fun initFactory() {
        if (factory != null) return
        val ctx = appCtx!!
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        val egl = EglBase.create()
        eglBase = egl
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))   // hardware decoding when available
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    private fun handleOffer(sdp: String) {
        closePeer()
        val f = factory ?: return
        val gen = generation
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {   // no STUN/TURN: host candidates only
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val conn = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                main.post {
                    if (gen == generation) send(JSONObject().put("type", "candidate").put("candidate", c.sdp)
                        .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))
                }
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                main.post {
                    if (gen != generation) return@post
                    main.removeCallbacks(disconnectTimer)
                    when (s) {
                        PeerConnection.PeerConnectionState.CONNECTED -> setState(State.STREAMING, "Receiving from $senderName")
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            setState(State.RECONNECTING, "Stream interrupted. Recovering…")
                            main.postDelayed(disconnectTimer, 6000)
                        }
                        PeerConnection.PeerConnectionState.FAILED -> requestNewOffer("Stream failed")
                        else -> {}
                    }
                }
            }
            override fun onTrack(t: RtpTransceiver) {
                main.post {
                    if (gen != generation) return@post
                    when (val track = t.receiver.track()) {
                        is VideoTrack -> { videoTrack = track; listener?.onVideo(track) }
                        is AudioTrack -> { audioTrack = track; track.setEnabled(true); track.setVolume(10.0) }
                    }
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: run { fatal("Could not create the WebRTC connection"); return }
        pc = conn
        conn.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                main.post {
                    if (gen != generation || pc !== conn) return@post
                    pendingCandidates.forEach { conn.addIceCandidate(it) }; pendingCandidates.clear()
                    conn.createAnswer(object : SdpObserverAdapter() {
                        override fun onCreateSuccess(d: SessionDescription) {
                            conn.setLocalDescription(object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    main.post { if (gen == generation) send(JSONObject().put("type", "answer").put("sdp", d.description)) }
                                }
                                override fun onSetFailure(e: String?) { main.post { requestNewOffer("Local description failed: $e") } }
                            }, d)
                        }
                        override fun onCreateFailure(e: String?) { main.post { requestNewOffer("Answer failed: $e") } }
                    }, MediaConstraints())
                }
            }
            override fun onSetFailure(e: String?) { main.post { requestNewOffer("Remote description failed: $e") } }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
        setState(State.NEGOTIATING, "Negotiating with $senderName…")
    }

    /** Ask the sender to restart the WebRTC handshake (used after Wi-Fi drops / failures). */
    private fun requestNewOffer(why: String) {
        if (!active) return
        closePeer()
        setState(State.RECONNECTING, "$why. Re-establishing…")
        send(JSONObject().put("type", "need-offer"))
    }

    private fun closePeer() {
        main.removeCallbacks(disconnectTimer)
        pendingCandidates.clear()
        pc?.let { val p = it; pc = null; try { p.close(); p.dispose() } catch (_: Exception) {} }
        videoTrack = null; audioTrack = null
        listener?.onVideo(null)
    }

    private fun stopInternal(newState: State, msg: String) {
        active = false
        generation++
        send(JSONObject().put("type", "bye"))
        ws?.close(1000, "bye"); ws = null
        closePeer()
        senderName = null
        routeAudio(false)
        setState(newState, msg)
    }

    private fun setState(s: State, d: String) {
        state = s; detail = d
        listener?.onState(s, d)
    }

    private fun routeAudio(on: Boolean) {
        val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (on) {
            savedAudioMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = true }
        } else {
            am.mode = savedAudioMode
            @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
        }
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
