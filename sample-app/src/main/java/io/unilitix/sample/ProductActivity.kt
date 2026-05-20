package io.unilitix.sample

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.unilitix.sample.databinding.ActivityProductBinding
import io.unilitix.sdk.Unilitix

class ProductActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAddToCart.setOnClickListener {
            Unilitix.trackEvent("add_to_cart", mapOf(
                "product_id" to "prod_001",
                "product_name" to "Ankara Print Tote Bag",
                "price" to 5000,
                "currency" to "NGN"
            ))
        }

        binding.btnCheckout.setOnClickListener {
            startActivity(Intent(this, CheckoutActivity::class.java))
        }
    }
}
