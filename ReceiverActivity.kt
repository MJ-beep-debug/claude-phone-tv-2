package com.wifitv.receiver

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class ReceiverActivity : AppCompatActivity(), ReceiverSession.Listener {
    private lateinit var video: SurfaceViewRenderer
    private lateinit var overlay: View
    private lateinit var controls: LinearLayout
    private var attached: VideoTrack? = null
    private var rendererReady = false
    private var fill = false
    private var muted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // no sleeping while casting
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        video = findViewById(R.id.video)
        overlay = findViewById(R.id.overlay)
        controls = findViewById(R.id.controls)

        // Immersive fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = findViewById<SeekBar>(R.id.volume)
        vol.max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        vol.progress = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        vol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, p, 0)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        findViewById<Button>(R.id.muteBtn).setOnClickListener {
            muted = !muted; ReceiverSession.setMuted(muted); (it as Button).text = if (muted) "Unmute" else "Mute"
        }
        findViewById<Button>(R.id.fitBtn).setOnClickListener {
            fill = !fill
            video.setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            (it as Button).text = if (fill) "Fit" else "Fill"
        }
        findViewById<Button>(R.id.disconnectBtn).setOnClickListener { ReceiverSession.stop(); finish() }
        video.setOnClickListener { controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        findViewById<View>(R.id.overlay).setOnClickListener { controls.visibility = View.VISIBLE }
        findViewById<TextView>(R.id.overlayCode).text = getSharedPreferences("wifitv", MODE_PRIVATE).getString("code", "")
    }

    override fun onStart() {
        super.onStart()
        ensureRenderer()
        ReceiverSession.setListener(this)    // also restores the interface after a temporary connection loss
    }

    override fun onStop() {
        ReceiverSession.setListener(null)
        detach()
        if (rendererReady) { video.release(); rendererReady = false }
        super.onStop()
    }

    private fun ensureRenderer() {
        val egl = ReceiverSession.eglBase
        if (egl != null && !rendererReady) {
            video.init(egl.eglBaseContext, null)
            video.setEnableHardwareScaler(true)
            video.setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            rendererReady = true
        }
    }

    override fun onVideo(track: VideoTrack?) {
        ensureRenderer()
        detach()
        if (track != null && rendererReady) { track.addSink(video); attached = track }
    }

    private fun detach() { attached?.let { try { it.removeSink(video) } catch (_: Exception) {} }; attached = null }

    override fun onState(state: State, detail: String) {
        val streaming = state == State.STREAMING
        overlay.visibility = if (streaming) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.overlayCode).visibility = if (state == State.WAITING_SENDER) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.overlayTitle).text = when (state) {
            State.WAITING_SENDER -> "Ready to receive"
            State.RECONNECTING -> "Reconnecting…"
            State.ERROR -> "Cannot connect"
            State.IDLE -> "Stopped"
            else -> "Connecting…"
        }
        findViewById<TextView>(R.id.overlayDetail).text = detail
        findViewById<TextView>(R.id.infoText).text = ReceiverSession.senderName ?: detail
        if (state == State.IDLE && detail == "Stopped") finish()
    }
}
