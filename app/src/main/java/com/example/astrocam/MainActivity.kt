package com.example.astrocam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Panel { MODE, ISO, SHUTTER, FOCUS, WB, TYPE, FRAMES, TIMER }

/** Thirds grid + centre cross to help aim. */
class GridOverlay(ctx: android.content.Context) : View(ctx) {
    private val p = Paint().apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = 2f
    }

    fun setLineColor(c: Int) {
        p.color = c
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            c.drawLine(w * i / 3f, 0f, w * i / 3f, h, p)
            c.drawLine(0f, h * i / 3f, w, h * i / 3f, p)
        }
        c.drawLine(w / 2f - 30f, h / 2f, w / 2f + 30f, h / 2f, p)
        c.drawLine(w / 2f, h / 2f - 30f, w / 2f, h / 2f + 30f, p)
    }
}

class MainActivity : Activity(), CameraEngine.Listener {

    private class Preset(val name: String, val iso: Int, val expNs: Long, val frames: Int)

    private val presets = listOf(
        Preset("Milky Way  (ISO 1600, 15 s, 30 frames)", 1600, 15_000_000_000L, 30),
        Preset("Deep sky  (ISO 800, 30 s, until stopped)", 800, 30_000_000_000L, 0),
        Preset("Star trails  (ISO 400, 30 s, until stopped)", 400, 30_000_000_000L, 0),
        Preset("Meteor shower  (ISO 1600, 10 s, until stopped)", 1600, 10_000_000_000L, 0),
        Preset("Moon  (ISO 100, 1/250 s)", 100, 4_000_000L, 1)
    )

    private val s = Settings()
    private lateinit var engine: CameraEngine
    private val ui = Handler(Looper.getMainLooper())

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private var panel: Panel? = null

    private lateinit var tv: TextureView
    private lateinit var grid: GridOverlay
    private lateinit var previewBox: FrameLayout
    private lateinit var panelBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var formatText: TextView
    private lateinit var lensBtn: TextView
    private lateinit var shutterBtn: TextView
    private val chips = HashMap<Panel, Pair<LinearLayout, TextView>>()

    private var engineReady = false
    private var opening = false
    private var running = false
    private var inFlight = false
    private var shotsDone = 0
    private var seqStamp = ""

    // ---------------------------------------------------------------- Colours

    private val fg: Int get() = if (s.night) Color.rgb(255, 70, 70) else Color.WHITE
    private val dimColor: Int get() = if (s.night) Color.rgb(140, 40, 40) else Color.LTGRAY
    private val panelBg: Int get() = if (s.night) Color.rgb(24, 0, 0) else Color.rgb(34, 34, 38)
    private val accent: Int get() = if (s.night) Color.rgb(100, 0, 0) else Color.rgb(60, 100, 170)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    // ---------------------------------------------------------------- Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        s.load(this)
        engine = CameraEngine(this, this)
        if (s.lensIndex !in engine.lenses.indices) s.lensIndex = 0
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            openIfReady()
        }
    }

    override fun onPause() {
        if (running) {
            running = false
            inFlight = false
            ui.removeCallbacksAndMessages(null)
        }
        s.save(this)
        engineReady = false
        opening = false
        engine.close()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            openIfReady()
        } else {
            setStatus("Camera permission is needed. Allow it in phone Settings > Apps > AstroCam.")
        }
    }

    private fun openIfReady() {
        if (opening || engineReady) return
        if (!::tv.isInitialized || !tv.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (engine.lenses.isEmpty()) {
            setStatus("No back camera with manual control found on this phone.")
            return
        }
        if (s.lensIndex !in engine.lenses.indices) s.lensIndex = 0
        opening = true
        setStatus("Opening camera...")
        engine.open(engine.lenses[s.lensIndex], tv, s.raw, s.jpeg)
        refreshAll()
    }

    private fun reopenCamera() {
        engineReady = false
        opening = false
        engine.close()
        openIfReady()
    }

    // ---------------------------------------------------------------- Engine callbacks

    override fun onMessage(msg: String) = setStatus(msg)

    override fun onError(msg: String) {
        runOnUiThread {
            opening = false
            engineReady = false
            statusText.text = msg
        }
    }

    override fun onOpened() {
        runOnUiThread {
            opening = false
            engineReady = true
            clampSettings()
            engine.applyPreview(s)
            setStatus("Ready")
            refreshAll()
        }
    }

    override fun onPreviewSize(w: Int, h: Int) {
        runOnUiThread { fitPreview(w, h) }
    }

    override fun onFrameSaved(path: String) {
        runOnUiThread {
            inFlight = false
            shotsDone++
            refreshInfo()
            when {
                !running -> finishSequence("Stopped: $shotsDone frame(s) saved")
                s.frames != 0 && shotsDone >= s.frames -> finishSequence(null)
                s.intervalSec > 0 -> {
                    setStatus("Next frame in ${s.intervalSec} s")
                    ui.postDelayed({ shootNext() }, s.intervalSec * 1000L)
                }
                else -> shootNext()
            }
        }
    }

    override fun onCaptureFailed(msg: String) {
        runOnUiThread { finishSequence(msg) }
    }

    private fun clampSettings() {
        s.iso = s.iso.coerceIn(engine.isoRange.lower, engine.isoRange.upper)
        s.expNs = s.expNs.coerceIn(engine.expRange.lower, engine.expRange.upper)
        s.ev = s.ev.coerceIn(engine.evRange.lower, engine.evRange.upper)
        s.focusDiopter = s.focusDiopter.coerceIn(0f, max(engine.minFocusDiopter, 0f))
        if (!engine.awbModes.contains(s.awb)) s.awb = 1
    }

    // ---------------------------------------------------------------- UI building

    private fun round(fill: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b) }

    private fun text(t: String, sp: Float = 14f, color: Int = fg, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun pill(t: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = t
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setTextColor(if (selected) Color.BLACK else fg)
            background = round(if (selected) fg else Color.TRANSPARENT, fg, dp(18))
            setOnClickListener { onClick() }
        }

    private fun hscroll(child: View): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(child)
        }

    private fun choiceRow(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, o) in options.withIndex()) {
            row.addView(pill(o, i == selected) { onPick(i) }, lp(WRAP, WRAP, 0f, 0, dp(2), dp(8), dp(2)))
        }
        return hscroll(row)
    }

    private fun slider(maxValue: Int, progressValue: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(this).apply {
            max = maxValue
            progress = progressValue
            progressTintList = ColorStateList.valueOf(fg)
            thumbTintList = ColorStateList.valueOf(fg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

    private fun buildUi() {
        applyBrightness()
        chips.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        lensBtn = pill("Lens", false) { chooseLens() }
        statusText = text("", 12f).apply { gravity = Gravity.CENTER }
        val presetBtn = pill("PRESETS", false) { choosePreset() }
        val setBtn = pill("\u2699", false) { openSettings() }
        top.addView(lensBtn, lp(WRAP, WRAP))
        top.addView(statusText, lp(0, WRAP, 1f, dp(6), 0, dp(6), 0))
        top.addView(presetBtn, lp(WRAP, WRAP, 0f, 0, 0, dp(6), 0))
        top.addView(setBtn, lp(WRAP, WRAP))
        root.addView(top, lp(MATCH, WRAP))

        // Preview
        previewBox = FrameLayout(this)
        tv = TextureView(this)
        if (s.night) {
            val cm = ColorMatrix(
                floatArrayOf(
                    0.3f, 0.59f, 0.11f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val paint = Paint()
            paint.colorFilter = ColorMatrixColorFilter(cm)
            tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
        grid = GridOverlay(this)
        grid.setLineColor(if (s.night) Color.argb(120, 255, 60, 60) else Color.argb(140, 255, 255, 255))
        grid.visibility = if (s.grid) View.VISIBLE else View.GONE
        previewBox.addView(tv, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        previewBox.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        root.addView(previewBox, lp(MATCH, 0, 1f))

        // Panel
        panelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = round(panelBg, dimColor, dp(12))
            visibility = View.GONE
        }
        root.addView(panelBox, lp(MATCH, WRAP, 0f, dp(8), dp(6), dp(8), 0))

        // Chips
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf(
            Panel.MODE to "MODE", Panel.ISO to "ISO", Panel.SHUTTER to "SHUTTER", Panel.FOCUS to "FOCUS",
            Panel.WB to "WB", Panel.TYPE to "TYPE", Panel.FRAMES to "FRAMES", Panel.TIMER to "TIMER"
        )
        for ((p, l) in labels) chipRow.addView(makeChip(p, l), lp(WRAP, WRAP, 0f, dp(4), 0, dp(4), 0))
        root.addView(hscroll(chipRow), lp(MATCH, WRAP, 0f, dp(4), dp(8), dp(4), dp(4)))

        // Bottom row
        infoText = text("", 11f, dimColor)
        formatText = text("", 11f, dimColor).apply { gravity = Gravity.END }
        shutterBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 26f
            setOnClickListener { onShutterClicked() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(14))
        }
        bottom.addView(infoText, lp(0, WRAP, 1f))
        bottom.addView(shutterBtn, lp(dp(76), dp(76)))
        bottom.addView(formatText, lp(0, WRAP, 1f))
        root.addView(bottom, lp(MATCH, WRAP))

        setContentView(root)

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openIfReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        showPanel(panel)
        refreshAll()
    }

    private fun makeChip(p: Panel, name: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            minimumWidth = dp(64)
            setOnClickListener { showPanel(if (panel == p) null else p) }
        }
        val value = text("", 15f, fg, true).apply { gravity = Gravity.CENTER }
        box.addView(text(name, 10f, dimColor).apply { gravity = Gravity.CENTER })
        box.addView(value)
        chips[p] = Pair(box, value)
        return box
    }

    private fun applyBrightness() {
        val a = window.attributes
        a.screenBrightness = if (s.night) 0.05f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = a
    }

    private fun fitPreview(pw: Int, ph: Int) {
        previewBox.post {
            val cw = previewBox.width
            val ch = previewBox.height
            if (cw == 0 || ch == 0) return@post
            // Phone is held upright, so the sensor's long side runs vertically.
            var vw = cw
            var vh = (cw.toFloat() * pw / ph).toInt()
            if (vh > ch) {
                vh = ch
                vw = (ch.toFloat() * ph / pw).toInt()
            }
            tv.layoutParams = FrameLayout.LayoutParams(vw, vh, Gravity.CENTER)
            grid.layoutParams = FrameLayout.LayoutParams(vw, vh, Gravity.CENTER)
        }
    }

    // ---------------------------------------------------------------- Refreshing

    private fun setStatus(msg: String) {
        runOnUiThread { if (::statusText.isInitialized) statusText.text = msg }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun refreshAll() {
        if (!::lensBtn.isInitialized) return
        val lens = engine.lenses.getOrNull(s.lensIndex)
        lensBtn.text = (lens?.label ?: "No lens") + " \u25BE"
        refreshChips()
        refreshInfo()
        formatText.text = formatName()
        setShutterLook()
    }

    private fun focusLabel(): String {
        val d = s.focusDiopter
        return if (d < 0.05f) "\u221E" else "%.2f m".format(1f / d)
    }

    private fun refreshChips() {
        fun setChip(p: Panel, v: String) {
            val c = chips[p] ?: return
            c.second.text = v
            c.second.setTextColor(fg)
            c.first.background = round(if (panel == p) accent else Color.TRANSPARENT, dimColor, dp(10))
        }
        setChip(Panel.MODE, if (s.autoExposure) "AUTO" else "MANUAL")
        setChip(Panel.ISO, if (s.autoExposure) "AUTO" else s.iso.toString())
        setChip(Panel.SHUTTER, if (s.autoExposure) "AUTO" else fmtShutter(s.expNs))
        setChip(
            Panel.FOCUS, when (s.focusMode) {
                FocusMode.INFINITY -> "\u221E"
                FocusMode.HYPERFOCAL -> "HYPER"
                FocusMode.MANUAL -> focusLabel()
                FocusMode.AUTO -> "AUTO"
            }
        )
        setChip(Panel.WB, awbName(s.awb))
        setChip(Panel.TYPE, s.frameType.label)
        setChip(Panel.FRAMES, if (s.frames == 0) "\u221E" else s.frames.toString())
        setChip(Panel.TIMER, "${s.countdownSec}s / ${s.intervalSec}s")
    }

    private fun refreshInfo() {
        if (!::infoText.isInitialized) return
        val eff = if (s.frameType == FrameType.BIAS) engine.expRange.lower else s.expNs
        val total = when {
            s.autoExposure -> "-"
            s.frames == 0 -> "\u221E"
            else -> fmtDuration(eff / 1e9 * s.frames)
        }
        val free = engine.freeBytes() / (1024.0 * 1024.0 * 1024.0)
        val count = if (s.frames == 0) "$shotsDone / \u221E" else "$shotsDone / ${s.frames}"
        infoText.text = "$count\nTotal $total\nFree ${"%.1f".format(free)} GB"
    }

    private fun setShutterLook() {
        shutterBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) accent else Color.TRANSPARENT)
            setStroke(dp(4), fg)
        }
        shutterBtn.text = if (running) "\u25A0" else "\u25CF"
        shutterBtn.setTextColor(fg)
    }

    // ---------------------------------------------------------------- Formatting helpers

    private fun fmtShutter(ns: Long): String {
        val sec = ns / 1e9
        return if (sec >= 1.0) {
            if (sec >= 10 || sec % 1.0 == 0.0) "%.0fs".format(sec) else "%.1fs".format(sec)
        } else {
            "1/${(1.0 / sec).roundToInt()}"
        }
    }

    private fun fmtDuration(sec: Double): String {
        val t = sec.roundToInt()
        return when {
            t < 60 -> "${t}s"
            t < 3600 -> "${t / 60}m ${t % 60}s"
            else -> "${t / 3600}h ${(t % 3600) / 60}m"
        }
    }

    private fun awbName(m: Int): String = when (m) {
        1 -> "AUTO"
        2 -> "TUNGSTEN"
        3 -> "FLUOR"
        4 -> "WARM FL"
        5 -> "DAYLIGHT"
        6 -> "CLOUDY"
        7 -> "TWILIGHT"
        8 -> "SHADE"
        else -> "MODE $m"
    }

    private fun formatName(): String = when {
        s.raw && s.jpeg -> "RAW + JPEG"
        s.raw -> "RAW (DNG)"
        else -> "JPEG"
    }

    private fun logPos(v: Double, lo: Double, hi: Double): Int =
        if (hi <= lo) 0 else (1000.0 * ln(v / lo) / ln(hi / lo)).roundToInt().coerceIn(0, 1000)

    private fun logVal(p: Int, lo: Double, hi: Double): Double = lo * (hi / lo).pow(p / 1000.0)

    private fun applyAndRefresh() {
        engine.applyPreview(s)
        refreshChips()
        refreshInfo()
    }

    private fun prompt(title: String, initial: String, inputType: Int, onOk: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(initial)
            setSingleLine()
            this.inputType = inputType
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("OK") { _, _ -> onOk(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- Panels

    private fun showPanel(p: Panel?) {
        panel = p
        panelBox.removeAllViews()
        if (p == null) {
            panelBox.visibility = View.GONE
            refreshChips()
            return
        }
        panelBox.visibility = View.VISIBLE
        when (p) {
            Panel.MODE -> buildModePanel()
            Panel.ISO -> buildIsoPanel()
            Panel.SHUTTER -> buildShutterPanel()
            Panel.FOCUS -> buildFocusPanel()
            Panel.WB -> buildWbPanel()
            Panel.TYPE -> buildTypePanel()
            Panel.FRAMES -> buildFramesPanel()
            Panel.TIMER -> buildTimerPanel()
        }
        refreshChips()
    }

    private fun note(t: String): TextView = text(t, 12f, dimColor).apply { setPadding(0, dp(4), 0, dp(2)) }

    private fun buildModePanel() {
        panelBox.addView(choiceRow(listOf("Manual", "Auto"), if (s.autoExposure) 1 else 0) {
            s.autoExposure = it == 1
            applyAndRefresh()
            showPanel(Panel.MODE)
        })
        if (!s.autoExposure) {
            panelBox.addView(note("Manual: you set ISO and shutter. Best for astrophotography."))
            return
        }
        val lo = engine.evRange.lower
        val hi = engine.evRange.upper
        if (lo == hi) {
            panelBox.addView(note("Exposure compensation is not supported on this lens."))
            return
        }
        val lbl = text("", 15f)
        fun upd() { lbl.text = "EV %+.1f".format(s.ev * engine.evStep) }
        upd()
        panelBox.addView(lbl)
        panelBox.addView(slider(hi - lo, s.ev - lo) { p ->
            s.ev = p + lo
            upd()
            applyAndRefresh()
        })
    }

    private fun buildIsoPanel() {
        if (s.autoExposure) {
            panelBox.addView(note("Exposure is AUTO. Set MODE to Manual to choose the ISO."))
            return
        }
        val lo = engine.isoRange.lower
        val hi = engine.isoRange.upper
        val valueView = text("", 16f, fg, true)
        fun upd() { valueView.text = "ISO ${s.iso}   (tap to type)" }
        upd()
        valueView.setOnClickListener {
            prompt("ISO ($lo - $hi)", s.iso.toString(), InputType.TYPE_CLASS_NUMBER) { t ->
                val n = t.trim().toIntOrNull()
                if (n != null) {
                    s.iso = n.coerceIn(lo, hi)
                    applyAndRefresh()
                    showPanel(Panel.ISO)
                }
            }
        }
        panelBox.addView(valueView)
        val values = (listOf(100, 200, 400, 800, 1600, 3200).filter { it in lo..hi } + hi).distinct()
        panelBox.addView(choiceRow(values.map { if (it == hi) "MAX" else "$it" }, values.indexOf(s.iso)) {
            s.iso = values[it]
            applyAndRefresh()
            showPanel(Panel.ISO)
        })
        panelBox.addView(slider(1000, logPos(s.iso.toDouble(), lo.toDouble(), hi.toDouble())) { p ->
            s.iso = logVal(p, lo.toDouble(), hi.toDouble()).roundToInt().coerceIn(lo, hi)
            upd()
            applyAndRefresh()
        })
        panelBox.addView(note("Higher ISO = brighter but noisier."))
    }

    private fun parseShutter(t: String): Long? {
        val x = t.trim().lowercase(Locale.US).removeSuffix("s").trim()
        return try {
            if (x.contains("/")) {
                val parts = x.split("/")
                (parts[0].toDouble() / parts[1].toDouble() * 1e9).toLong()
            } else {
                (x.toDouble() * 1e9).toLong()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildShutterPanel() {
        if (s.autoExposure) {
            panelBox.addView(note("Exposure is AUTO. Set MODE to Manual to choose the shutter."))
            return
        }
        val lo = engine.expRange.lower
        val hi = engine.expRange.upper
        val valueView = text("", 16f, fg, true)
        fun upd() { valueView.text = "Shutter ${fmtShutter(s.expNs)}   (tap to type, e.g. 1/250 or 20)" }
        upd()
        valueView.setOnClickListener {
            prompt("Shutter (1/250 or seconds)", fmtShutter(s.expNs).removeSuffix("s"), InputType.TYPE_CLASS_TEXT) { t ->
                val n = parseShutter(t)
                if (n != null && n > 0) {
                    s.expNs = n.coerceIn(lo, hi)
                    applyAndRefresh()
                    showPanel(Panel.SHUTTER)
                }
            }
        }
        panelBox.addView(valueView)
        val names = listOf("1/250", "1/60", "1/15", "1s", "5s", "10s", "15s", "20s", "30s", "MAX")
        val nsList = listOf(
            4_000_000L, 16_666_667L, 66_666_667L, 1_000_000_000L, 5_000_000_000L,
            10_000_000_000L, 15_000_000_000L, 20_000_000_000L, 30_000_000_000L, hi
        )
        panelBox.addView(choiceRow(names, nsList.indexOf(s.expNs)) {
            s.expNs = nsList[it].coerceIn(lo, hi)
            applyAndRefresh()
            showPanel(Panel.SHUTTER)
        })
        panelBox.addView(slider(1000, logPos(s.expNs.toDouble(), lo.toDouble(), hi.toDouble())) { p ->
            s.expNs = logVal(p, lo.toDouble(), hi.toDouble()).toLong().coerceIn(lo, hi)
            upd()
            applyAndRefresh()
        })
        panelBox.addView(note("Live view shows a short boosted exposure for aiming. The saved photo uses the full time."))
    }

    private fun buildFocusPanel() {
        val modes = listOf(FocusMode.INFINITY, FocusMode.HYPERFOCAL, FocusMode.MANUAL, FocusMode.AUTO)
        panelBox.addView(choiceRow(listOf("\u221E Infinity", "Hyperfocal", "Manual", "Auto"), modes.indexOf(s.focusMode)) {
            s.focusMode = modes[it]
            applyAndRefresh()
            showPanel(Panel.FOCUS)
        })
        if (s.focusMode == FocusMode.MANUAL) {
            if (engine.minFocusDiopter <= 0f) {
                panelBox.addView(note("This lens has fixed focus."))
            } else {
                val lbl = text("Distance: ${focusLabel()}", 15f)
                panelBox.addView(lbl)
                val startPos = ((1f - s.focusDiopter / engine.minFocusDiopter) * 1000f).roundToInt().coerceIn(0, 1000)
                panelBox.addView(slider(1000, startPos) { p ->
                    s.focusDiopter = engine.minFocusDiopter * (1f - p / 1000f)
                    lbl.text = "Distance: ${focusLabel()}"
                    applyAndRefresh()
                })
                panelBox.addView(note("Left = near, right = far. For stars, fine-tune on a bright star."))
            }
        } else if (s.focusMode == FocusMode.INFINITY) {
            panelBox.addView(note("Best for stars. If stars look soft, try Manual and move slightly left."))
        }
    }

    private fun buildWbPanel() {
        val modes = engine.awbModes
        panelBox.addView(choiceRow(modes.map { awbName(it) }, modes.indexOf(s.awb)) {
            s.awb = modes[it]
            applyAndRefresh()
            showPanel(Panel.WB)
        })
        panelBox.addView(note("In RAW files white balance is only a tag, so you can change it later when editing."))
    }

    private fun buildTypePanel() {
        val types = FrameType.values().toList()
        panelBox.addView(choiceRow(types.map { it.label }, types.indexOf(s.frameType)) {
            s.frameType = types[it]
            applyAndRefresh()
            showPanel(Panel.TYPE)
        })
        panelBox.addView(
            note(
                when (s.frameType) {
                    FrameType.LIGHT -> "Light: the normal photos of the sky."
                    FrameType.DARK -> "Dark: cover the lens. Use the same ISO and shutter as your lights."
                    FrameType.FLAT -> "Flat: shoot an evenly lit white surface. Use a short shutter."
                    FrameType.BIAS -> "Bias: cover the lens. The shortest shutter is used automatically."
                }
            )
        )
        panelBox.addView(note("Files are saved in Pictures/AstroCam/${s.frameType.prefix}."))
    }

    private fun buildFramesPanel() {
        val values = listOf(1, 5, 10, 25, 50, 100, 0)
        panelBox.addView(choiceRow(values.map { if (it == 0) "\u221E" else "$it" }, values.indexOf(s.frames)) {
            s.frames = values[it]
            applyAndRefresh()
            showPanel(Panel.FRAMES)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minus = pill("\u2212", false) {
            if (s.frames > 1) s.frames--
            applyAndRefresh()
            showPanel(Panel.FRAMES)
        }
        val plus = pill("+", false) {
            if (s.frames != 0) s.frames++
            applyAndRefresh()
            showPanel(Panel.FRAMES)
        }
        val value = text(if (s.frames == 0) "Until stopped" else "${s.frames} frames", 16f, fg, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                prompt("Number of frames (0 = until stopped)", s.frames.toString(), InputType.TYPE_CLASS_NUMBER) { t ->
                    val n = t.trim().toIntOrNull()
                    if (n != null) {
                        s.frames = n.coerceIn(0, 9999)
                        applyAndRefresh()
                        showPanel(Panel.FRAMES)
                    }
                }
            }
        }
        row.addView(minus, lp(WRAP, WRAP))
        row.addView(value, lp(0, WRAP, 1f))
        row.addView(plus, lp(WRAP, WRAP))
        panelBox.addView(row, lp(MATCH, WRAP, 0f, 0, dp(6), 0, 0))
        panelBox.addView(note("Tap the number to type it. Frames saved until you press stop or storage runs low."))
    }

    private fun buildTimerPanel() {
        val cd = listOf(0, 2, 5, 10)
        val iv = listOf(0, 1, 2, 5, 10)
        panelBox.addView(note("Countdown before the first frame"))
        panelBox.addView(choiceRow(cd.map { if (it == 0) "Off" else "$it s" }, cd.indexOf(s.countdownSec)) {
            s.countdownSec = cd[it]
            refreshChips()
            showPanel(Panel.TIMER)
        })
        panelBox.addView(note("Pause between frames"))
        panelBox.addView(choiceRow(iv.map { if (it == 0) "Off" else "$it s" }, iv.indexOf(s.intervalSec)) {
            s.intervalSec = iv[it]
            refreshChips()
            showPanel(Panel.TIMER)
        })
    }

    // ---------------------------------------------------------------- Dialogs

    private fun chooseLens() {
        if (running) {
            toast("Stop shooting first")
            return
        }
        val names = engine.lenses.map { it.label }.toTypedArray()
        if (names.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Choose lens")
            .setItems(names) { _, i ->
                s.lensIndex = i
                reopenCamera()
            }
            .show()
    }

    private fun choosePreset() {
        val names = presets.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Presets (point the phone at the sky first)")
            .setItems(names) { _, i ->
                val p = presets[i]
                s.autoExposure = false
                s.iso = p.iso.coerceIn(engine.isoRange.lower, engine.isoRange.upper)
                s.expNs = p.expNs.coerceIn(engine.expRange.lower, engine.expRange.upper)
                s.frames = p.frames
                s.focusMode = FocusMode.INFINITY
                s.frameType = FrameType.LIGHT
                applyAndRefresh()
                showPanel(panel)
                setStatus("Preset applied")
            }
            .show()
    }

    private fun openSettings() {
        fun onOff(b: Boolean) = if (b) "ON" else "OFF"
        val items = arrayOf(
            "Night red mode: ${onOff(s.night)}",
            "Grid: ${onOff(s.grid)}",
            "Format: ${formatName()}",
            "Help and tips"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        s.night = !s.night
                        s.save(this)
                        engineReady = false
                        opening = false
                        engine.close()
                        buildUi()
                    }
                    1 -> {
                        s.grid = !s.grid
                        grid.visibility = if (s.grid) View.VISIBLE else View.GONE
                        openSettings()
                    }
                    2 -> {
                        if (running) {
                            toast("Stop shooting first")
                        } else {
                            if (s.raw && !s.jpeg) {
                                s.raw = false; s.jpeg = true
                            } else if (!s.raw && s.jpeg) {
                                s.raw = true; s.jpeg = true
                            } else {
                                s.raw = true; s.jpeg = false
                            }
                            refreshAll()
                            reopenCamera()
                        }
                    }
                    3 -> showHelp()
                }
            }
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Quick tips")
            .setMessage(
                "1. Put the phone on a tripod or a stable surface.\n" +
                    "2. Tap PRESETS and pick a target (Milky Way, Moon...).\n" +
                    "3. Check FOCUS is set to infinity. Tap a chip at the bottom to change any setting.\n" +
                    "4. Use TIMER > Countdown 2-10 s so touching the phone does not shake it.\n" +
                    "5. Press the round button to start. Press it again to stop.\n\n" +
                    "For stacking later, also shoot some Dark, Flat and Bias frames (TYPE chip).\n\n" +
                    "Files: Pictures/AstroCam/<type> in your gallery."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------------------------------------------------------------- Shooting

    private fun onShutterClicked() {
        if (running) {
            stopSequence()
            return
        }
        if (!engineReady) {
            setStatus("Camera is not ready yet")
            return
        }
        running = true
        inFlight = false
        shotsDone = 0
        seqStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        showPanel(null)
        refreshAll()
        if (s.countdownSec > 0) countdown(s.countdownSec) else shootNext()
    }

    private fun countdown(n: Int) {
        if (!running) return
        if (n <= 0) {
            shootNext()
            return
        }
        setStatus("Starting in $n s")
        ui.postDelayed({ countdown(n - 1) }, 1000)
    }

    private fun shootNext() {
        if (!running) return
        if (engine.freeBytes() < 300L * 1024 * 1024) {
            finishSequence("Stopped: storage almost full")
            return
        }
        inFlight = true
        val total = if (s.frames == 0) "" else "/${s.frames}"
        val exp = if (s.autoExposure) "auto" else fmtShutter(
            if (s.frameType == FrameType.BIAS) engine.expRange.lower else s.expNs
        )
        setStatus("${s.frameType.label} ${shotsDone + 1}$total  $exp")
        engine.capture(s, seqStamp, shotsDone + 1)
    }

    private fun stopSequence() {
        running = false
        ui.removeCallbacksAndMessages(null)
        if (!inFlight) {
            finishSequence("Stopped: $shotsDone frame(s) saved")
        } else {
            setStatus("Stopping after this frame...")
        }
    }

    private fun finishSequence(msg: String?) {
        running = false
        inFlight = false
        ui.removeCallbacksAndMessages(null)
        setStatus(msg ?: "Done: $shotsDone frame(s) saved in Pictures/AstroCam/${s.frameType.prefix}")
        refreshAll()
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).startTone(ToneGenerator.TONE_PROP_ACK, 250)
        } catch (e: Exception) {
            // no sound is fine
        }
    }
}
