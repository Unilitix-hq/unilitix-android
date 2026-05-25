package io.unilitix.sdk.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

internal class BatteryInfo(private val context: Context) {

    @Volatile private var cachedLevel: Float = -1f
    @Volatile private var cachedIsCharging: Boolean = false
    private var receiver: BroadcastReceiver? = null

    init {
        startObserving()
    }

    fun startObserving() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    cachedLevel = level.toFloat() / scale.toFloat()
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                cachedIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        context.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        receiver = r
    }

    fun stopObserving() {
        val r = receiver ?: return
        receiver = null
        try {
            context.unregisterReceiver(r)
        } catch (_: IllegalArgumentException) {}
    }

    fun getBatteryLevel(): Float = cachedLevel
    fun isCharging(): Boolean = cachedIsCharging
}
