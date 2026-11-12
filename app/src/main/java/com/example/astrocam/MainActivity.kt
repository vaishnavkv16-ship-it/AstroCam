package com.example.astrocam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
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
import android.net.Uri
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
import android.widget.ScrollView
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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, p)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, p)
        }
        canvas.drawLine(w / 2f - 30f, h / 2f, w / 2f + 30f, h / 2f, p)
        canvas.drawLine(w / 2f, h / 2f - 30f, w / 2f, h / 2f + 30f, p)
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

    private val REQ_TREE = 77

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
    private lateinit var overlay: TextView
    private lateinit var lensBtn: TextView
    private lateinit var shutterBtn: TextView
    private val chips = HashMap<Panel, Pair<LinearLayout, TextView>>()

    private var settingsRefresh: (() -> Unit)? = null

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

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // permission may already be granted
            }
            s.treeUri = uri.toString()
            s.save(this)
            settingsRefresh?.invoke()
            refreshInfo()
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
        s.kelvin = s.kelvin.coerceIn(2000, 10000)
        val badCustom = s.awb == 0 && !engine.manualWb
        val badPreset = s.awb != 0 && !engine.awbModes.contains(s.awb)
        if (badCustom || badPreset) s.awb = 1
    }

    // ---------------------------------------------------------------- UI helpers

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

    /** Row of small fine-adjust buttons, e.g. "- 1/3 stop" and "+ 1/3 stop". */
    private fun stepper(items: List<Pair<String, () -> Unit>>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((label, act) in items) {
            row.addView(pill(label, false) { act() }, lp(WRAP, WRAP, 0f, dp(4), dp(4), dp(4), dp(2)))
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

    private fun note(t: String): TextView = text(t, 12f, dimColor).apply { setPadding(0, dp(4), 0, dp(2)) }

    private fun setStatus(msg: String) {
        runOnUiThread { if (::statusText.isInitialized) statusText.text = msg }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- Main screen

    private fun buildUi() {
        applyBrightness()
        chips.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

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
        overlay = text("", 11f, fg).apply {
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(dp(10), dp(8), dp(10), 0)
            visibility = if (s.showInfo) View.VISIBLE else View.GONE
        }
        previewBox.addView(tv, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        previewBox.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        previewBox.addView(overlay, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        root.addView(previewBox, lp(MATCH, 0, 1f))

        panelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = round(panelBg, dimColor, dp(12))
            visibility = View.GONE
        }
        root.addView(panelBox, lp(MATCH, WRAP, 0f, dp(8), dp(6), dp(8), 0))

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf(
            Panel.MODE to "MODE", Panel.ISO to "ISO", Panel.SHUTTER to "SHUTTER", Panel.FOCUS to "FOCUS",
            Panel.WB to "WB", Panel.TYPE to "TYPE", Panel.FRAMES to "FRAMES", Panel.TIMER to "TIMER"
        )
        for ((p, l) in labels) chipRow.addView(makeChip(p, l), lp(WRAP, WRAP, 0f, dp(4), 0, dp(4), 0))
        root.addView(hscroll(chipRow), lp(MATCH, WRAP, 0f, dp(4), dp(8), dp(4), dp(4)))

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

    private fun focusShort(): String = when (s.focusMode) {
        FocusMode.INFINITY -> "\u221E"
        FocusMode.HYPERFOCAL -> "HYPER"
        FocusMode.MANUAL -> focusLabel()
        FocusMode.AUTO -> "AUTO"
    }

    private fun wbShort(): String = if (s.awb == 0) "${s.kelvin}K" else awbName(s.awb)

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
        setChip(Panel.FOCUS, focusShort())
        setChip(Panel.WB, wbShort())
        setChip(Panel.TYPE, s.frameType.label)
        setChip(Panel.FRAMES, if (s.frames == 0) "\u221E" else s.frames.toString())
        setChip(Panel.TIMER, "${s.countdownSec}s / ${s.intervalSec}s")
        refreshOverlay()
    }

    private fun refreshOverlay() {
        if (!::overlay.isInitialized) return
        overlay.setTextColor(fg)
        val lens = engine.lenses.getOrNull(s.lensIndex)?.label ?: "-"
        val size = engine.rawSize ?: engine.jpegSize
        val res = if (size != null) "  ${size.width}x${size.height}" else ""
        val exp = if (s.autoExposure) "AUTO exposure" else "ISO ${s.iso}  ${fmtShutter(s.expNs)}"
        overlay.text = "$lens  ${formatName()}$res\n$exp  focus ${focusShort()}  WB ${wbShort()}\n${s.frameType.label}"
    }

    private fun frameMb(): Double {
        var mb = 0.0
        val r = engine.rawSize
        val j = engine.jpegSize
        if (s.raw && r != null) mb += r.width.toDouble() * r.height * 2.0 / 1e6
        if (s.jpeg && j != null) mb += j.width.toDouble() * j.height * 0.35 / 1e6
        return mb
    }

    private fun pathHint(): String {
        val parts = ArrayList<String>()
        if (s.treeUri.isNotEmpty()) {
            parts.add("<your folder>")
        } else {
            parts.add(s.baseDir)
            parts.add(if (s.folderName.isBlank()) "AstroCam" else s.folderName.trim())
        }
        if (s.sessionFolder) parts.add("<target>_<date>")
        if (s.typeFolders) parts.add(s.frameType.prefix)
        return parts.joinToString("/")
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
        0 -> "CUSTOM"
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
        panelBox.addView(stepper(listOf(
            "\u2212 EV" to { s.ev = (s.ev - 1).coerceIn(lo, hi); applyAndRefresh(); showPanel(Panel.MODE) },
            "+ EV" to { s.ev = (s.ev + 1).coerceIn(lo, hi); applyAndRefresh(); showPanel(Panel.MODE) }
        )))
    }

    private fun stepIso(dir: Int) {
        val lo = engine.isoRange.lower
        val hi = engine.isoRange.upper
        var n = (s.iso * 2.0.pow(dir / 3.0)).roundToInt()
        if (n == s.iso) n += dir
        s.iso = n.coerceIn(lo, hi)
        applyAndRefresh()
        showPanel(Panel.ISO)
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
        panelBox.addView(stepper(listOf(
            "\u2212 1/3 stop" to { stepIso(-1) },
            "+ 1/3 stop" to { stepIso(1) }
        )))
        panelBox.addView(note("Range $lo - $hi. Higher ISO = brighter but noisier."))
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

    private fun stepShutter(dir: Int) {
        val lo = engine.expRange.lower
        val hi = engine.expRange.upper
        var n = (s.expNs * 2.0.pow(dir / 3.0)).toLong()
        if (n == s.expNs) n += dir
        s.expNs = n.coerceIn(lo, hi)
        applyAndRefresh()
        showPanel(Panel.SHUTTER)
    }

    private fun buildShutterPanel() {
        if (s.autoExposure) {
            panelBox.addView(note("Exposure is AUTO. Set MODE to Manual to choose the shutter."))
            return
        }
        val lo = engine.expRange.lower
        val hi = engine.expRange.upper
        val valueView = text("", 16f, fg, true)
        fun upd() { valueView.text = "Shutter ${fmtShutter(s.expNs)}   (tap to type)" }
        upd()
        valueView.setOnClickListener {
            prompt("Shutter: type 1/250 or seconds like 20", fmtShutter(s.expNs).removeSuffix("s"), InputType.TYPE_CLASS_TEXT) { t ->
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
        panelBox.addView(stepper(listOf(
            "\u2212 1/3 stop" to { stepShutter(-1) },
            "+ 1/3 stop" to { stepShutter(1) }
        )))
        panelBox.addView(note("Range ${fmtShutter(lo)} - ${fmtShutter(hi)}. Live view shows a short boosted exposure; the saved photo uses the full time."))
    }

    private fun stepFocus(dir: Int) {
        val minF = engine.minFocusDiopter
        if (minF <= 0f) return
        s.focusDiopter = (s.focusDiopter - dir * minF * 0.02f).coerceIn(0f, minF)
        applyAndRefresh()
        showPanel(Panel.FOCUS)
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
                panelBox.addView(stepper(listOf(
                    "\u25C0 nearer" to { stepFocus(-1) },
                    "farther \u25B6" to { stepFocus(1) }
                )))
                panelBox.addView(note("Left = near, right = far. For stars, fine-tune on a bright star."))
            }
        } else if (s.focusMode == FocusMode.INFINITY) {
            panelBox.addView(note("Best for stars. If stars look soft, try Manual and move slightly left."))
        } else if (s.focusMode == FocusMode.HYPERFOCAL) {
            panelBox.addView(note("Everything from about half the hyperfocal distance to infinity is sharp."))
        }
    }

    private fun buildWbPanel() {
        val opts = ArrayList<Int>(engine.awbModes.filter { it != 0 })
        if (engine.manualWb) opts.add(0)
        panelBox.addView(choiceRow(opts.map { if (it == 0) "Custom K" else awbName(it) }, opts.indexOf(s.awb)) {
            s.awb = opts[it]
            applyAndRefresh()
            showPanel(Panel.WB)
        })
        if (s.awb == 0 && engine.manualWb) {
            val lbl = text("", 16f, fg, true)
            fun upd() { lbl.text = "${s.kelvin} K   (tap to type)" }
            upd()
            lbl.setOnClickListener {
                prompt("Colour temperature in Kelvin (2000 - 10000)", s.kelvin.toString(), InputType.TYPE_CLASS_NUMBER) { t ->
                    val n = t.trim().toIntOrNull()
                    if (n != null) {
                        s.kelvin = n.coerceIn(2000, 10000)
                        applyAndRefresh()
                        showPanel(Panel.WB)
                    }
                }
            }
            panelBox.addView(lbl)
            panelBox.addView(slider(80, (s.kelvin - 2000) / 100) { p ->
                s.kelvin = 2000 + p * 100
                upd()
                applyAndRefresh()
            })
            panelBox.addView(stepper(listOf(
                "\u2212 500 K" to { s.kelvin = (s.kelvin - 500).coerceIn(2000, 10000); applyAndRefresh(); showPanel(Panel.WB) },
                "\u2212 100 K" to { s.kelvin = (s.kelvin - 100).coerceIn(2000, 10000); applyAndRefresh(); showPanel(Panel.WB) },
                "+ 100 K" to { s.kelvin = (s.kelvin + 100).coerceIn(2000, 10000); applyAndRefresh(); showPanel(Panel.WB) },
                "+ 500 K" to { s.kelvin = (s.kelvin + 500).coerceIn(2000, 10000); applyAndRefresh(); showPanel(Panel.WB) }
            )))
            panelBox.addView(note("Low K = warm light (compensates blue), high K = cool light. Approximate, mainly for preview and JPEG."))
        } else if (!engine.manualWb) {
            panelBox.addView(note("Custom Kelvin is not supported by this camera. Use the presets."))
        } else {
            panelBox.addView(note("In RAW files white balance is only a tag, so you can change it later when editing."))
        }
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
        panelBox.addView(note("Saved to: ${pathHint()}"))
    }

    private fun setFrames(n: Int) {
        s.frames = n.coerceIn(0, 9999)
        applyAndRefresh()
        showPanel(Panel.FRAMES)
    }

    private fun buildFramesPanel() {
        val values = listOf(1, 5, 10, 25, 50, 100, 200, 500, 0)
        panelBox.addView(choiceRow(values.map { if (it == 0) "\u221E" else "$it" }, values.indexOf(s.frames)) {
            setFrames(values[it])
        })
        val value = text(if (s.frames == 0) "Until stopped" else "${s.frames} frames", 16f, fg, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
            setOnClickListener {
                prompt("Number of frames (0 = until stopped)", s.frames.toString(), InputType.TYPE_CLASS_NUMBER) { t ->
                    val n = t.trim().toIntOrNull()
                    if (n != null) setFrames(n)
                }
            }
        }
        panelBox.addView(value, lp(MATCH, WRAP))
        if (s.frames != 0) {
            panelBox.addView(slider(1000, logPos(s.frames.toDouble().coerceAtLeast(1.0), 1.0, 500.0)) { p ->
                s.frames = logVal(p, 1.0, 500.0).roundToInt().coerceIn(1, 500)
                value.text = "${s.frames} frames"
                applyAndRefresh()
            })
        }
        panelBox.addView(stepper(listOf(
            "\u2212 10" to { if (s.frames > 10) setFrames(s.frames - 10) else setFrames(1) },
            "\u2212 1" to { if (s.frames > 1) setFrames(s.frames - 1) },
            "+ 1" to { if (s.frames != 0) setFrames(s.frames + 1) },
            "+ 10" to { if (s.frames != 0) setFrames(s.frames + 10) }
        )))
        val mb = frameMb()
        val est = if (s.frames == 0 || mb <= 0.0) "" else "  Storage about %.1f GB.".format(s.frames * mb / 1000.0)
        panelBox.addView(note("Tap the number to type any value. Choose \u221E to shoot until you press stop or storage runs low.$est"))
    }

    private fun buildTimerPanel() {
        val cd = listOf(0, 2, 5, 10, 30)
        val iv = listOf(0, 1, 2, 5, 10, 30, 60)
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
        panelBox.addView(
            pill("Type custom values", false) {
                prompt("Countdown seconds", s.countdownSec.toString(), InputType.TYPE_CLASS_NUMBER) { a ->
                    val c = a.trim().toIntOrNull()
                    if (c != null) s.countdownSec = c.coerceIn(0, 600)
                    prompt("Pause between frames (seconds)", s.intervalSec.toString(), InputType.TYPE_CLASS_NUMBER) { b ->
                        val i = b.trim().toIntOrNull()
                        if (i != null) s.intervalSec = i.coerceIn(0, 3600)
                        refreshChips()
                        showPanel(Panel.TIMER)
                    }
                }
            }, lp(WRAP, WRAP, 0f, 0, dp(6), 0, 0)
        )
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

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Quick tips")
            .setMessage(
                "1. Put the phone on a tripod or a stable surface.\n" +
                    "2. Tap PRESETS and pick a target (Milky Way, Moon...).\n" +
                    "3. Check FOCUS is set to infinity. Tap a chip at the bottom to change any setting. " +
                    "Every panel has presets, a slider, fine +/- buttons, and you can tap the value to type it.\n" +
                    "4. Use TIMER > Countdown 2-10 s so touching the phone does not shake it.\n" +
                    "5. Press the round button to start. Press it again to stop.\n\n" +
                    "For stacking later, also shoot some Dark, Flat and Bias frames (TYPE chip).\n\n" +
                    "Choose where files go in the settings page (gear icon)."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------------------------------------------------------------- Settings page

    private fun sectionTitle(t: String): TextView =
        text(t.uppercase(Locale.US), 12f, dimColor, true).apply { setPadding(0, dp(22), 0, dp(6)) }

    private fun toggleRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f))
        if (sub.isNotEmpty()) col.addView(text(sub, 11f, dimColor))
        row.addView(col, lp(0, WRAP, 1f))
        row.addView(pill(if (on) "ON" else "OFF", on) { onChange(!on) })
        return row
    }

    private fun valueRow(title: String, value: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f))
        if (sub.isNotEmpty()) col.addView(text(sub, 11f, dimColor))
        row.addView(col, lp(0, WRAP, 1f))
        row.addView(text(value, 14f, fg, true).apply { gravity = Gravity.END }, lp(WRAP, WRAP, 0f, dp(8), 0, 0, 0))
        return row
    }

    private fun cameraDetails(): List<String> {
        val out = ArrayList<String>()
        val lens = engine.lenses.getOrNull(s.lensIndex)
        out.add("Lens: ${lens?.label ?: "-"}  (camera id ${lens?.cameraId ?: "-"})")
        val raw = engine.rawSize
        val jpg = engine.jpegSize
        out.add("RAW size: ${if (raw != null) "${raw.width} x ${raw.height}" else "-"}")
        out.add("JPEG size: ${if (jpg != null) "${jpg.width} x ${jpg.height}" else "-"}")
        val sz = engine.sensorSize
        if (sz != null) out.add("Sensor: %.1f x %.1f mm".format(sz.width, sz.height))
        if (engine.focalMm > 0) out.add("Focal length: ${engine.focalMm} mm (35mm equivalent)")
        out.add("ISO range: ${engine.isoRange.lower} - ${engine.isoRange.upper}")
        out.add("Shutter range: ${fmtShutter(engine.expRange.lower)} - ${fmtShutter(engine.expRange.upper)}")
        val minF = engine.minFocusDiopter
        out.add("Closest focus: ${if (minF > 0f) "%.0f cm".format(100f / minF) else "fixed focus"}")
        out.add("Custom white balance: ${if (engine.manualWb) "supported" else "not supported"}")
        out.add(
            "Camera hardware level: " + when (engine.hwLevel) {
                0 -> "LIMITED"
                1 -> "FULL"
                2 -> "LEGACY"
                3 -> "LEVEL 3"
                4 -> "EXTERNAL"
                else -> "unknown"
            }
        )
        out.add("Lenses with manual control: ${engine.lenses.joinToString { it.label }}")
        out.add("Free storage: %.1f GB".format(engine.freeBytes() / (1024.0 * 1024.0 * 1024.0)))
        return out
    }

    private fun openSettings() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(40))
        }
        scroll.addView(box)
        var formatChanged = false
        var nightChanged = false

        fun render() {
            box.removeAllViews()

            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(text("Settings", 22f, fg, true), lp(0, WRAP, 1f))
            head.addView(pill("Close", false) { dialog.dismiss() })
            box.addView(head)

            box.addView(sectionTitle("Display"))
            box.addView(toggleRow("Night red mode", "Red screen and dim brightness to protect night vision", s.night) {
                s.night = it
                nightChanged = true
                s.save(this)
                render()
            })
            box.addView(toggleRow("Grid overlay", "Thirds grid and centre cross on the preview", s.grid) {
                s.grid = it
                grid.visibility = if (it) View.VISIBLE else View.GONE
                s.save(this)
                render()
            })
            box.addView(toggleRow("Details on preview", "Lens, resolution, ISO, shutter, focus and WB at the top left", s.showInfo) {
                s.showInfo = it
                overlay.visibility = if (it) View.VISIBLE else View.GONE
                s.save(this)
                render()
            })

            box.addView(sectionTitle("Capture"))
            box.addView(text("File format", 15f))
            val formats = listOf("RAW (DNG)", "JPEG", "RAW + JPEG")
            val fi = when {
                s.raw && s.jpeg -> 2
                s.raw -> 0
                else -> 1
            }
            box.addView(choiceRow(formats, fi) {
                if (running) {
                    toast("Stop shooting first")
                } else {
                    s.raw = it != 1
                    s.jpeg = it != 0
                    formatChanged = true
                    s.save(this)
                    render()
                }
            })
            box.addView(valueRow(
                "Target name",
                if (s.targetName.isBlank()) "Not set" else s.targetName,
                "Added to file names and session folders, e.g. M31"
            ) {
                prompt("Target name (e.g. M31, Moon)", s.targetName, InputType.TYPE_CLASS_TEXT) {
                    s.targetName = it.trim()
                    s.save(this)
                    render()
                }
            })
            box.addView(toggleRow("Sound when finished", "Short beep when a sequence ends", s.beep) {
                s.beep = it
                s.save(this)
                render()
            })

            box.addView(sectionTitle("Storage"))
            box.addView(text("Save location", 15f))
            val bases = listOf("Pictures", "DCIM")
            box.addView(choiceRow(bases, bases.indexOf(s.baseDir).coerceAtLeast(0)) {
                s.baseDir = bases[it]
                s.save(this)
                render()
            })
            box.addView(valueRow(
                "Folder name",
                if (s.folderName.isBlank()) "AstroCam" else s.folderName,
                "Created inside ${s.baseDir}. You can use sub-folders like Astro/2026"
            ) {
                prompt("Folder name", s.folderName, InputType.TYPE_CLASS_TEXT) {
                    s.folderName = it.trim()
                    s.save(this)
                    render()
                }
            })
            val treeName = if (s.treeUri.isEmpty()) "Not set" else (Uri.parse(s.treeUri).lastPathSegment ?: "Selected")
            box.addView(valueRow(
                "Custom folder",
                treeName,
                "Pick any folder, even on an SD card. It replaces the location above."
            ) {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_TREE)
            })
            if (s.treeUri.isNotEmpty()) {
                box.addView(pill("Clear custom folder", false) {
                    s.treeUri = ""
                    s.save(this)
                    render()
                }, lp(WRAP, WRAP, 0f, 0, dp(4), 0, dp(4)))
            }
            box.addView(toggleRow("Sub-folder per frame type", "light / dark / flat / bias", s.typeFolders) {
                s.typeFolders = it
                s.save(this)
                render()
            })
            box.addView(toggleRow("New folder for each session", "Target name + date and time", s.sessionFolder) {
                s.sessionFolder = it
                s.save(this)
                render()
            })
            box.addView(note("Files are saved to: ${pathHint()}/"))

            box.addView(sectionTitle("Camera details"))
            for (line in cameraDetails()) box.addView(text(line, 13f, fg), lp(MATCH, WRAP, 0f, 0, dp(2), 0, dp(2)))

            box.addView(sectionTitle("Other"))
            val other = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            other.addView(pill("Help and tips", false) { showHelp() }, lp(WRAP, WRAP, 0f, 0, 0, dp(8), 0))
            other.addView(pill("Reset all settings", false) {
                AlertDialog.Builder(this)
                    .setTitle("Reset all settings?")
                    .setMessage("ISO, shutter, folders and everything else go back to the defaults.")
                    .setPositiveButton("Reset") { _, _ ->
                        s.resetToDefaults()
                        s.save(this)
                        nightChanged = true
                        formatChanged = true
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            box.addView(other)
        }

        settingsRefresh = { render() }
        dialog.setContentView(scroll)
        dialog.setOnDismissListener {
            settingsRefresh = null
            s.save(this)
            if (nightChanged) {
                engineReady = false
                opening = false
                engine.close()
                buildUi()
            } else if (formatChanged) {
                reopenCamera()
                refreshAll()
            } else {
                refreshAll()
            }
        }
        render()
        dialog.show()
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
        setStatus(msg ?: "Done: $shotsDone frame(s) saved in ${pathHint()}")
        refreshAll()
        if (s.beep) {
            try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).startTone(ToneGenerator.TONE_PROP_ACK, 250)
            } catch (e: Exception) {
                // no sound is fine
            }
        }
    }
}
