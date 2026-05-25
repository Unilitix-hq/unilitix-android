package io.unilitix.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.unilitix.sample.databinding.ActivityProductBinding
import io.unilitix.sdk.Unilitix

class ProductActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductBinding

    private data class Product(val id: String, val name: String, val price: Double)

    private val products = listOf(
        Product("prod_001_starter",    "Starter Plan",    4999.0),
        Product("prod_002_premium",    "Premium Plan",    9999.0),
        Product("prod_003_growth",     "Growth Plan",     49999.0),
        Product("prod_004_business",   "Business Plan",   99999.0),
        Product("prod_005_scale",      "Scale Plan",      199999.0),
        Product("prod_006_enterprise", "Enterprise Plan", 0.0),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Unilitix.trackScreen("ProductListScreen")

        val buttons = listOf(
            binding.btnBuyProduct1,
            binding.btnBuyProduct2,
            binding.btnBuyProduct3,
            binding.btnBuyProduct4,
            binding.btnBuyProduct5,
            binding.btnBuyProduct6,
        )

        buttons.forEachIndexed { index, button ->
            val product = products[index]
            button.setOnClickListener {
                Unilitix.trackEvent("add_to_cart", mapOf(
                    "product_id"   to product.id,
                    "product_name" to product.name,
                    "price"        to product.price,
                    "currency"     to "NGN",
                    "screen"       to "ProductListScreen"
                ))
                Toast.makeText(this, "Added: ${product.name}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCheckout.setOnClickListener {
            Unilitix.trackEvent("checkout_started", mapOf("screen" to "ProductListScreen"))
            startActivity(Intent(this, CheckoutActivity::class.java))
        }
    }
}
