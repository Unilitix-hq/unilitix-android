package io.unilitix.sample

import android.os.Bundle
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
            val amount = binding.etAmount.text.toString().toDoubleOrNull() ?: 0.0
            Unilitix.trackEvent("purchase_completed", mapOf(
                "amount" to amount,
                "currency" to "NGN",
                "payment_method" to "card"
            ))
            Toast.makeText(this, "Purchase tracked!", Toast.LENGTH_SHORT).show()
        }
    }
}
