package com.opencall.relay

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class IncomingCallActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        setContentView(R.layout.activity_incoming_call)
        val callId = intent.getStringExtra("callId") ?: ""
        val dialNumber = intent.getStringExtra("dialNumber") ?: ""
        findViewById<TextView>(R.id.tv_dial_number).text = "Relaying call to\n$dialNumber"
        findViewById<Button>(R.id.btn_cancel_relay).setOnClickListener {
            startService(Intent(this, RelayService::class.java).apply {
                action = RelayService.ACTION_HANGUP
                putExtra(RelayService.EXTRA_CALL_ID, callId)
            })
            finish()
        }
        window.decorView.postDelayed({ finish() }, 3000)
    }
}