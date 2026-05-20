package io.unilitix.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.unilitix.sample.databinding.ActivityCrashBinding

class CrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnTriggerCrash.setOnClickListener {
            val nullString: String? = null
            // Intentional NPE to test crash tracking
            nullString!!.length
        }
    }
}
