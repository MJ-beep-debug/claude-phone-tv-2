package com.wifitv.receiver

import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

class MainActivity : AppCompatActivity(), ReceiverSession.Listener {
    private lateinit var statusDot: ImageView
    private lateinit var statusText: TextView
    private lateinit var senderText: TextView
    private lateinit var hostInput: EditText
    private lateinit var spinner: Spinner

    private val qualities = listOf(
        Quality("720p • 30 fps (recommended)", 720, 30),
        Quality("480p • 30 fps (older phones)", 480, 30),
        Quality("480p • 15 fps (weak Wi-Fi)", 480, 15),
        Quality("360p • 15 fps (very weak)", 360, 15),
        Quality("1080p • 30 fps (fast phones)", 1080, 30),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        senderText = findViewById(R.id.senderText)
        hostInput = findViewById(R.id.hostInput)
        spinner = findViewById(R.id.qualitySpinner)

        val prefs = getSharedPreferences("wifitv", MODE_PRIVATE)
        var code = prefs.getString("code", null)
        if (code == null) {
            code = "%06d".format(SecureRandom().nextInt(1_000_000))
            prefs.edit().putString("code", code).apply()
        }
        findViewById<TextView>(R.id.codeText).text = code
        hostInput.setText(prefs.getString("host", ""))
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualities.map { it.label })
        spinner.setSelection(prefs.getInt("quality", 0).coerceIn(0, qualities.size - 1))

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            val host = hostInput.text.toString().trim().removePrefix("http://").removePrefix("ws://").trimEnd('/')
            if (host.isEmpty()) {
                hostInput.error = "Enter the laptop address"; return@setOnClickListener
            }
            val full = if (host.contains(":")) host else "$host:8765"
            val q = qualities[spinner.selectedItemPosition]
            prefs.edit().putString("host", host).putInt("quality", spinner.selectedItemPosition).apply()
            ReceiverSession.start(this, full, code!!, q.height, q.fps)
            startActivity(Intent(this, ReceiverActivity::class.java))
        }
        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            ReceiverSession.stop()
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() { super.onStart(); findViewById<TextView>(R.id.ipText).text = localIp(); ReceiverSession.setListener(this) }
    override fun onStop() { ReceiverSession.setListener(null); super.onStop() }

    override fun onState(state: State, detail: String) {
        statusText.text = when (state) {
            State.IDLE -> "Idle"
            State.CONNECTING_SERVER -> "Connecting…"
            State.WAITING_SENDER -> "Waiting for a sender"
            State.NEGOTIATING -> "Connecting to sender…"
            State.STREAMING -> "Receiving"
            State.RECONNECTING -> "Reconnecting…"
            State.ERROR -> "Error: $detail"
        }
        val color = when (state) {
            State.STREAMING -> getColor(R.color.accent)
            State.ERROR -> getColor(R.color.danger)
            State.IDLE -> getColor(R.color.muted)
            else -> getColor(R.color.amber)
        }
        statusDot.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        senderText.text = "Connected sender: " + (ReceiverSession.senderName ?: "none")
    }

    override fun onVideo(track: org.webrtc.VideoTrack?) {}

    private fun localIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
                ?: "Not connected to Wi-Fi"
        } catch (e: Exception) { "Unknown" }
    }

    data class Quality(val label: String, val height: Int, val fps: Int)
}
