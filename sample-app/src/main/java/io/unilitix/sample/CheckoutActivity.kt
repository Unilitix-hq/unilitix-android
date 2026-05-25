package io.unilitix.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.unilitix.sample.databinding.ActivityCheckoutBinding
import io.unilitix.sdk.Unilitix

class CheckoutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckoutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPay.setOnClickListener {
            val amountText = binding.etAmount.text.toString().trim()
            val amount = amountText.toDoubleOrNull()

            if (amountText.isEmpty()) {
                binding.etAmount.error = "Enter an amount"
                return@setOnClickListener
            }
            if (amount == null || amount <= 0) {
                binding.etAmount.error = "Enter a valid amount greater than 0"
                return@setOnClickListener
            }

            Unilitix.trackEvent("purchase_completed", mapOf(
                "amount" to amount,
                "currency" to "NGN",
                "payment_method" to "card",
                "screen" to "CheckoutScreen"
            ))

            binding.btnPay.isEnabled = false
            binding.btnPay.text = "Payment Successful ✓"
            binding.btnPay.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#4CAF50")
            )
            Toast.makeText(this, "Purchase tracked! ₦${String.format("%.2f", amount)}", Toast.LENGTH_SHORT).show()

            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        Unilitix.trackScreen("CheckoutScreen")
    }
}
