package com.hcode91.pocketcarioca

import android.content.Context
import android.os.Environment
import androidx.preference.PreferenceManager
import java.io.File

/** Typed view over the app's SharedPreferences; keys match res/xml/preferences.xml. */
class Settings(ctx: Context) {
    private val p = PreferenceManager.getDefaultSharedPreferences(ctx)
    private val privateRoms = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "roms").apply { mkdirs() }

    val gpu get() = p.getBoolean("gpu", true)
    val internalResolution get() = p.getString("internal_resolution", "1")!!.toIntOrNull() ?: 1
    val smooth get() = p.getBoolean("smooth", true)
    val aspect get() = p.getString("aspect", "0")!!.toIntOrNull() ?: 0
    val audio get() = p.getBoolean("audio", true)
    val volume get() = p.getInt("volume", 100)
    val soundfont: String? get() = p.getString("soundfont", null)
    val boomerang get() = p.getBoolean("boomerang", false)
    val motionSensitivity get() = p.getInt("motion_sensitivity", 100) / 100f
    val showOverlay get() = p.getBoolean("overlay", true)
    val overlayOpacity get() = p.getInt("overlay_opacity", 45)
    val haptics get() = p.getBoolean("haptics", true)
    val analogStick get() = p.getBoolean("analog_stick", false)
    val fastForwardToggle get() = p.getBoolean("ff_toggle", true)

    /** The folder the library scans: the chosen one, or the app's private folder. */
    var romsDir: String
        get() = p.getString("roms_dir", null) ?: privateRoms.absolutePath
        set(v) = p.edit().putString("roms_dir", v).apply()

    val privateRomsDir: File get() = privateRoms

    companion object {
        /** Turns a SAF tree URI on primary storage into a filesystem path the core can open. */
        fun treeToPath(treeDocId: String): String? {
            val parts = treeDocId.split(":", limit = 2)
            if (parts.size != 2) return null
            val base = when (parts[0]) {
                "primary" -> Environment.getExternalStorageDirectory().absolutePath
                else -> "/storage/${parts[0]}"
            }
            return if (parts[1].isEmpty()) base else "$base/${parts[1]}"
        }
    }
}
