package io.unilitix.sdk.benchmark

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A representative screen for capture benchmarks: ~200 views, text, inputs, buttons. */
class BenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Emulators can boot with the screen locked or off; an activity behind
        // the keyguard never gets focus and the capture benchmark would wait.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(60) { i ->
            list.addView(TextView(this).apply { text = "Order #$i — 3 items, ₦12,500"; textSize = 16f })
            list.addView(EditText(this).apply { hint = "Note $i" })
            list.addView(Button(this).apply { text = "Pay $i"; setBackgroundColor(Color.rgb(20, 120, 80)) })
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }
}
