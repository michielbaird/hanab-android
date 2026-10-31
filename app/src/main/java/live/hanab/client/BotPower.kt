package live.hanab.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Keeps the bot's endgame search to what the battery can afford: all the cores while charging or well charged,
 * fewer as it runs down, and one in power-saving mode. The bot plays the same moves either way.
 */
class BotPower(private val context: Context) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = apply(intent)
    }

    fun start() {
        if (!NativeOffline.available) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        // The battery broadcast is sticky: registering returns the latest one, so the threads are right from the start.
        context.registerReceiver(receiver, filter)?.let(::apply)
    }

    fun stop() {
        if (NativeOffline.available) runCatching { context.unregisterReceiver(receiver) }
    }

    private fun apply(battery: Intent) {
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val saving = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        NativeOffline.setThreads(threadsFor(level, charging, saving))
    }

    companion object {
        /** [level] is the battery percentage, or negative if unknown. */
        fun threadsFor(level: Int, charging: Boolean, powerSaving: Boolean): Int = when {
            powerSaving -> 1
            charging || level < 0 -> 4
            level <= 15 -> 1
            level <= 40 -> 2
            level <= 70 -> 3
            else -> 4
        }
    }
}
