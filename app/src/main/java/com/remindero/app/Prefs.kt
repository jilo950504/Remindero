package com.remindero.app

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("remindero", Context.MODE_PRIVATE)

    var hasCenter: Boolean
        get() = sp.getBoolean("hasCenter", false)
        set(v) { sp.edit().putBoolean("hasCenter", v).apply() }

    var centerLat: Double
        get() = Double.fromBits(sp.getLong("lat", 0L))
        set(v) { sp.edit().putLong("lat", v.toRawBits()).apply() }

    var centerLon: Double
        get() = Double.fromBits(sp.getLong("lon", 0L))
        set(v) { sp.edit().putLong("lon", v.toRawBits()).apply() }

    var radius: Float
        get() = sp.getFloat("radius", 200f)
        set(v) { sp.edit().putFloat("radius", v).apply() }

    var message: String
        get() = sp.getString("message", DEFAULT_MESSAGE) ?: DEFAULT_MESSAGE
        set(v) { sp.edit().putString("message", v).apply() }

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) { sp.edit().putBoolean("vibrate", v).apply() }

    companion object {
        const val DEFAULT_MESSAGE = "¡Oye! Saliste de la zona 🚨 ¿Se te olvidó algo?"
    }
}
