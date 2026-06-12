package moe.chenxy.oppopods.pods

import android.content.Context
import android.content.Intent
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RfcommLog {
    private const val MAX_RECENT_LOGS = 200
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val recentLogs = ArrayDeque<Entry>()

    @Volatile
    private var enabled = false

    data class Entry(
        val level: String,
        val tag: String,
        val message: String,
        val time: String,
    )

    fun setEnabled(value: Boolean, context: Context? = null) {
        enabled = value
        if (value) {
            context?.let { replayRecent(it) }
        }
    }

    fun clear() {
        synchronized(recentLogs) { recentLogs.clear() }
    }

    fun isEnabled(): Boolean = enabled

    fun d(context: Context?, tag: String, message: String) = log(context, "D", tag, message)

    fun i(context: Context?, tag: String, message: String) = log(context, "I", tag, message)

    fun w(context: Context?, tag: String, message: String) = log(context, "W", tag, message)

    fun e(context: Context?, tag: String, message: String) = log(context, "E", tag, message)

    private fun log(context: Context?, level: String, tag: String, message: String) {
        if (!isEnabled() || context == null) return
        val entry = Entry(level, tag, message, timeFormat.format(Date()))
        synchronized(recentLogs) {
            recentLogs.addLast(entry)
            while (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeFirst()
            }
        }
        context.sendRfcommLog(entry)
    }

    private fun replayRecent(context: Context) {
        synchronized(recentLogs) { recentLogs.toList() }.forEach { context.sendRfcommLog(it) }
    }

    private fun Context.sendRfcommLog(entry: Entry) {
        Intent(OppoPodsAction.ACTION_RFCOMM_LOG).apply {
            setPackage(BuildConfig.APPLICATION_ID)
            putExtra("level", entry.level)
            putExtra("tag", entry.tag)
            putExtra("message", entry.message)
            putExtra("time", entry.time)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            sendBroadcast(this)
        }
    }
}
