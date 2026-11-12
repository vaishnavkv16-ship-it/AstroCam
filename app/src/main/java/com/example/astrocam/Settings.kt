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
    var awb = 1                       // 1 = auto, 0 = custom Kelvin, others = Android presets
    var kelvin = 5500
    var frameType = FrameType.LIGHT
    var frames = 10                   // 0 = until stopped
    var countdownSec = 0
    var intervalSec = 0
    var raw = true
    var jpeg = false
    var grid = false
    var night = true
    var showInfo = true
    var beep = true
    var lensIndex = 0

    // Storage
    var baseDir = "Pictures"          // Pictures or DCIM
    var folderName = "AstroCam"
    var treeUri = ""                  // custom folder chosen with the system folder picker
    var typeFolders = true
    var sessionFolder = false
    var targetName = ""

    fun resetToDefaults() {
        val d = Settings()
        autoExposure = d.autoExposure; iso = d.iso; expNs = d.expNs; ev = d.ev
        focusMode = d.focusMode; focusDiopter = d.focusDiopter
        awb = d.awb; kelvin = d.kelvin
        frameType = d.frameType; frames = d.frames
        countdownSec = d.countdownSec; intervalSec = d.intervalSec
        raw = d.raw; jpeg = d.jpeg; grid = d.grid; night = d.night
        showInfo = d.showInfo; beep = d.beep
        baseDir = d.baseDir; folderName = d.folderName; treeUri = d.treeUri
        typeFolders = d.typeFolders; sessionFolder = d.sessionFolder; targetName = d.targetName
    }

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
        kelvin = p.getInt("kelvin", kelvin)
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
        showInfo = p.getBoolean("showInfo", showInfo)
        beep = p.getBoolean("beep", beep)
        lensIndex = p.getInt("lens", lensIndex)
        baseDir = p.getString("baseDir", baseDir) ?: "Pictures"
        folderName = p.getString("folderName", folderName) ?: "AstroCam"
        treeUri = p.getString("treeUri", treeUri) ?: ""
        typeFolders = p.getBoolean("typeFolders", typeFolders)
        sessionFolder = p.getBoolean("sessionFolder", sessionFolder)
        targetName = p.getString("targetName", targetName) ?: ""
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
            .putInt("kelvin", kelvin)
            .putString("type", frameType.name)
            .putInt("frames", frames)
            .putInt("countdown", countdownSec)
            .putInt("interval", intervalSec)
            .putBoolean("raw", raw)
            .putBoolean("jpeg", jpeg)
            .putBoolean("grid", grid)
            .putBoolean("night", night)
            .putBoolean("showInfo", showInfo)
            .putBoolean("beep", beep)
            .putInt("lens", lensIndex)
            .putString("baseDir", baseDir)
            .putString("folderName", folderName)
            .putString("treeUri", treeUri)
            .putBoolean("typeFolders", typeFolders)
            .putBoolean("sessionFolder", sessionFolder)
            .putString("targetName", targetName)
            .apply()
    }
}
