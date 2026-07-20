package com.opencall.relay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.opencall.relay.databinding.ActivityDialerBinding

class DialerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDialerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDialerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvDialDisplay.text = "+"
        updateDialHint("+")

        setupKeypad()
        setupActionRow()
        setupNavigation()
    }

    // ── Keypad wiring ─────────────────────────────────────────────────────────

    private fun setupKeypad() {
        mapOf(
            binding.btnKey1    to "1",
            binding.btnKey2    to "2",
            binding.btnKey3    to "3",
            binding.btnKey4    to "4",
            binding.btnKey5    to "5",
            binding.btnKey6    to "6",
            binding.btnKey7    to "7",
            binding.btnKey8    to "8",
            binding.btnKey9    to "9",
            binding.btnKey0    to "0",
            binding.btnKeyStar to "*",
            binding.btnKeyHash to "#"
        ).forEach { (view, digit) ->
            view.setOnClickListener { appendDigit(digit) }
        }
    }

    private fun setupActionRow() {
        binding.btnBackspace.setOnClickListener { appendDigit("backspace") }
        binding.btnCall.setOnClickListener {
            val number = binding.tvDialDisplay.text.toString()
            if (validateNumber(number)) initiateCall(number)
        }
    }

    private fun setupNavigation() {
        binding.navRelay.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    // ── Digit input ───────────────────────────────────────────────────────────

    fun appendDigit(digit: String) {
        var current = binding.tvDialDisplay.text.toString()
        if (digit == "backspace") {
            if (current.length > 1) current = current.dropLast(1)
        } else {
            current += digit
        }
        binding.tvDialDisplay.text = current
        updateDialHint(current)
    }

    fun updateDialHint(number: String) {
        val hint = when {
            number == "+"                                 -> "Enter number with country code"
            // 3-digit prefixes checked before 2- and 1-digit to avoid prefix collisions
            number.startsWith("+254")                     -> "Kenya"
            number.startsWith("+234")                     -> "Nigeria"
            number.startsWith("+233")                     -> "Ghana"
            number.startsWith("+380")                     -> "Ukraine"
            number.startsWith("+1") && number.length > 3 -> "USA / Canada"
            number.startsWith("+44")                      -> "United Kingdom"
            number.startsWith("+91")                      -> "India"
            number.startsWith("+61")                      -> "Australia"
            number.startsWith("+49")                      -> "Germany"
            number.startsWith("+33")                      -> "France"
            number.startsWith("+55")                      -> "Brazil"
            number.startsWith("+81")                      -> "Japan"
            number.startsWith("+82")                      -> "South Korea"
            number.startsWith("+86")                      -> "China"
            number.startsWith("+27")                      -> "South Africa"
            number.startsWith("+52")                      -> "Mexico"
            number.startsWith("+7")                       -> "Russia"
            else                                          -> "Searching OCP network..."
        }
        binding.tvDialHint.text = hint
    }

    // ── Validation ────────────────────────────────────────────────────────────

    fun validateNumber(number: String): Boolean {
        return when {
            number.isBlank() || number == "+" -> {
                Toast.makeText(this, "Enter a number", Toast.LENGTH_SHORT).show()
                false
            }
            !number.startsWith("+") -> {
                Toast.makeText(this, "Add country code: +91, +1, +44...", Toast.LENGTH_LONG).show()
                false
            }
            number.length < 8 -> {
                Toast.makeText(this, "Number too short", Toast.LENGTH_SHORT).show()
                false
            }
            else -> true
        }
    }

    // ── Call initiation ───────────────────────────────────────────────────────

    private fun initiateCall(number: String) {
        // TODO: route through OCP relay network instead of direct PSTN call
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not place call: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CALL_PHONE), 201
            )
        }
    }
}
