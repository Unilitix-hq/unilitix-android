package io.unilitix.sdk.benchmark

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A representative screen for capture benchmarks: ~200 views, text, inputs, buttons. */
class BenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(60) { i ->
            list.addView(TextView(this).apply { text = "Order #$i — 3 items, ₦12,500"; textSize = 16f })
            list.addView(EditText(this).apply { hint = "Note $i" })
            list.addView(Button(this).apply { text = "Pay $i"; setBackgroundColor(Color.rgb(20, 120, 80)) })
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }
}
