package com.candela.platform

import android.content.Context
import android.os.Build
import android.os.PowerManager

enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

class ThermalGovernor(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var onLevel: (ThermalLevel) -> Unit = {}
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT < 29) return
        listener = PowerManager.OnThermalStatusChangedListener { status ->
            onLevel(map(status))
        }
        pm.addThermalStatusListener(listener!!)
        onLevel(map(pm.currentThermalStatus))
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < 29) return
        listener?.let { pm.removeThermalStatusListener(it) }
        listener = null
    }

    private fun map(status: Int): ThermalLevel = when {
        Build.VERSION.SDK_INT < 29 -> ThermalLevel.NONE
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
        status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
        status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
        status >= PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
        else -> ThermalLevel.NONE
    }
}
