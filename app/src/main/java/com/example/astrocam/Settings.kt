package com.example.astrocam

import android.content.Context

enum class FocusMode { INFINITY, HYPERFOCAL, MANUAL, AUTO }

enum class FrameType(val label: String, val prefix: String) {
    LIGHT("Light", "light"),
    DARK("Dark", "dark"),
    FLAT("Flat", "flat"),
    BIAS("Bias", "bias")
}

/** Everything the user can change. Saved automatically. */
class Settings {
    var autoExposure = false
    var iso = 800
    var expNs = 10_000_000_000L
    var ev = 0
    var focusMode = FocusMode.INFINITY
    var focusDiopter = 0f
    var awb = 1                       // CONTROL_AWB_MODE_AUTO
    var frameType = FrameType.LIGHT
    var frames = 10                   // 0 = infinite
    var countdownSec = 0
    var intervalSec = 0
    var raw = true
    var jpeg = false
    var grid = false
    var night = true
    var lensIndex = 0

    fun load(c: Context) {
        val p = c.getSharedPreferences("astrocam", Context.MODE_PRIVATE)
        autoExposure = p.getBoolean("auto", autoExposure)
        iso = p.getInt("iso", iso)
        expNs = p.getLong("exp", expNs)
        ev = p.getInt("ev", ev)
        focusMode = try {
            FocusMode.valueOf(p.getString("focus", focusMode.name) ?: "INFINITY")
        } catch (e: Exception) {
            FocusMode.INFINITY
        }
        focusDiopter = p.getFloat("focusD", focusDiopter)
        awb = p.getInt("awb", awb)
        frameType = try {
            FrameType.valueOf(p.getString("type", frameType.name) ?: "LIGHT")
        } catch (e: Exception) {
            FrameType.LIGHT
        }
        frames = p.getInt("frames", frames)
        countdownSec = p.getInt("countdown", countdownSec)
        intervalSec = p.getInt("interval", intervalSec)
        raw = p.getBoolean("raw", raw)
        jpeg = p.getBoolean("jpeg", jpeg)
        grid = p.getBoolean("grid", grid)
        night = p.getBoolean("night", night)
        lensIndex = p.getInt("lens", lensIndex)
    }

    fun save(c: Context) {
        c.getSharedPreferences("astrocam", Context.MODE_PRIVATE).edit()
            .putBoolean("auto", autoExposure)
            .putInt("iso", iso)
            .putLong("exp", expNs)
            .putInt("ev", ev)
            .putString("focus", focusMode.name)
            .putFloat("focusD", focusDiopter)
            .putInt("awb", awb)
            .putString("type", frameType.name)
            .putInt("frames", frames)
            .putInt("countdown", countdownSec)
            .putInt("interval", intervalSec)
            .putBoolean("raw", raw)
            .putBoolean("jpeg", jpeg)
            .putBoolean("grid", grid)
            .putBoolean("night", night)
            .putInt("lens", lensIndex)
            .apply()
    }
}
