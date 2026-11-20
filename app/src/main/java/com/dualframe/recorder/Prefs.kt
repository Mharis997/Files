package com.dualframe.recorder

import android.content.Context

/** User settings, saved on the phone. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var uhd: Boolean
        get() = p.getBoolean("uhd", false)
        set(v) = p.edit().putBoolean("uhd", v).apply()
    var fps: Int
        get() = p.getInt("fps", 30)
        set(v) = p.edit().putInt("fps", v).apply()
    var grid: Boolean
        get() = p.getBoolean("grid", false)
        set(v) = p.edit().putBoolean("grid", v).apply()
}
