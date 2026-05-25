package io.unilitix.sample

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.unilitix.sample.databinding.ActivityFeaturesBinding
import io.unilitix.sdk.Unilitix

class FeaturesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFeaturesBinding

    private val users = listOf("user_lagos_001", "user_abuja_002", "user_nairobi_003")
    private var userIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeaturesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Unilitix.trackScreen("FeaturesShowcase")

        binding.btnOptOut.setOnClickListener {
            Unilitix.optOut()
            Toast.makeText(this, "SDK opted out — no more events sent", Toast.LENGTH_SHORT).show()
            binding.btnOptOut.isEnabled = false
            binding.btnOptIn.isEnabled = true
        }

        binding.btnOptIn.setOnClickListener {
            Unilitix.optIn()
            Toast.makeText(this, "SDK opted in — recording resumed", Toast.LENGTH_SHORT).show()
            binding.btnOptOut.isEnabled = true
            binding.btnOptIn.isEnabled = false
        }

        binding.btnReset.setOnClickListener {
            Unilitix.reset()
            Toast.makeText(this, "User reset — new anonymous ID generated", Toast.LENGTH_SHORT).show()
        }

        binding.btnNewSession.setOnClickListener {
            Unilitix.startSession()
            Toast.makeText(this, "New session started", Toast.LENGTH_SHORT).show()
        }

        binding.btnTrackScreen.setOnClickListener {
            Unilitix.trackScreen("My Custom Screen Name")
            Toast.makeText(this, "Screen tracked as 'My Custom Screen Name'", Toast.LENGTH_SHORT).show()
        }

        binding.btnSwitchUser.setOnClickListener {
            val userId = users[userIndex % users.size]
            Unilitix.identify(userId, mapOf("city" to userId.split("_")[1]))
            Toast.makeText(this, "Identified as: $userId", Toast.LENGTH_SHORT).show()
            userIndex++
        }
    }
}
