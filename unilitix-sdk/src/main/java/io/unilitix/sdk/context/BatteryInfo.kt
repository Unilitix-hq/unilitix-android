package io.unilitix.sdk.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

internal class BatteryInfo(context: Context) {

    @Volatile
    private var cachedLevel: Float = -1f
    private var lastRefreshMs: Long = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                cachedLevel = level.toFloat() / scale.toFloat()
                lastRefreshMs = System.currentTimeMillis()
            }
        }
    }

    init {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
    }

    fun getBatteryLevel(): Float {
        return cachedLevel
    }
}
