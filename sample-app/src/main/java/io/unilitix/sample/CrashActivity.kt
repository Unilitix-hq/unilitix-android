package io.unilitix.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.unilitix.sample.databinding.ActivityCrashBinding
import io.unilitix.sdk.Unilitix

class CrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Unilitix.trackScreen("CrashDemoScreen")

        binding.btnTriggerCrash.setOnClickListener {
            Unilitix.trackEvent("crash_demo_triggered", mapOf(
                "screen" to "CrashDemoScreen",
                "intentional" to true
            ))
            Unilitix.flush()
            Toast.makeText(this, "Crash incoming — SDK will capture it", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                val nullString: String? = null
                nullString!!.length
            }, 500)
        }
    }
}
