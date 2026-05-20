package io.unilitix.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.unilitix.sample.databinding.ActivityMainBinding
import io.unilitix.sdk.Unilitix

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnProduct.setOnClickListener {
            startActivity(Intent(this, ProductActivity::class.java))
        }

        binding.btnLogin.setOnClickListener {
            Unilitix.identify("user_123", mapOf("email" to "user@example.com", "plan" to "pro"))
            Toast.makeText(this, "Identified as user_123", Toast.LENGTH_SHORT).show()
        }

        binding.btnTrackEvent.setOnClickListener {
            Unilitix.trackEvent("custom_event_fired", mapOf("source" to "main_screen", "value" to 42))
            Toast.makeText(this, "Custom event tracked", Toast.LENGTH_SHORT).show()
        }

        binding.btnFlush.setOnClickListener {
            Unilitix.flush()
            Toast.makeText(this, "Flush triggered", Toast.LENGTH_SHORT).show()
        }

        binding.btnCrash.setOnClickListener {
            startActivity(Intent(this, CrashActivity::class.java))
        }
    }
}
