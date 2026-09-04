package com.candela.platform

import android.content.Context
import android.os.PowerManager

class SessionWakeLock(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var lock: PowerManager.WakeLock? = null

    fun acquire() {
        release()
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "candela:session").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }

    fun release() {
        try { lock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        lock = null
    }
}
