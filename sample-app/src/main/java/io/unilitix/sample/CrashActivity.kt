package io.unilitix.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
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

        // Native crash: SIGSEGV to this process. Reported as
        // "SIGNAL: Fatal signal 11 (SIGSEGV)" on the next launch (API 21-29
        // by the SDK's signal handler, API 30+ from ApplicationExitInfo).
        binding.btnTriggerNativeCrash.setOnClickListener {
            Unilitix.flush()
            Toast.makeText(this, "Native crash incoming — reported on next launch", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                Process.sendSignal(Process.myPid(), 11)
            }, 500)
        }

        // ANR (1.9.0, Android 11+): block the main thread, then tap the
        // screen again; after ~5 s the system shows "isn't responding".
        // Choose "Close app": the ANR is reported on the next launch.
        binding.btnTriggerAnr.setOnClickListener {
            Unilitix.flush()
            Toast.makeText(this, "Freezing the main thread: tap again, then Close app", Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed({
                Thread.sleep(30_000)
            }, 500)
        }
    }
}
