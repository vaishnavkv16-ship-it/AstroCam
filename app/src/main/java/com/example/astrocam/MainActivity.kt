package com.example.astrocam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Step 1: manual camera with RAW (DNG) long-exposure capture.
 * Red-on-black UI to keep night vision.
 */
class MainActivity : Activity() {

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var captureButton: Button

    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var chars: CameraCharacteristics? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var isoRange: Range<Int> = Range(100, 800)
    private var expRange: Range<Long> = Range(1_000_000L, 1_000_000_000L)

    // User settings
    private var isoProgress = 500
    private var expProgress = 600
    private var frames = 1

    // Capture state
    private var shotsLeft = 0
    private var frameIndex = 0
    private var stamp = ""
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var savedCount = 0

    private val iso: Int
        get() = lerpLog(isoRange.lower.toDouble(), isoRange.upper.toDouble(), isoProgress).roundToInt()
    private val expNs: Long
        get() = lerpLog(expRange.lower.toDouble(), expRange.upper.toDouble(), expProgress).toLong()

    private fun lerpLog(lo: Double, hi: Double, progress: Int): Double =
        lo * (hi / lo).pow(progress / 1000.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    private fun buildUi() {
        val red = Color.rgb(255, 60, 60)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        textureView = TextureView(this)
        root.addView(textureView, LinearLayout.LayoutParams(-1, 0, 1f))

        statusText = TextView(this).apply {
            setTextColor(red)
            setPadding(24, 12, 24, 12)
            textSize = 14f
        }
        root.addView(statusText)

        root.addView(labeledSeek("ISO", 1000, isoProgress) { isoProgress = it; onSettingsChanged() })
        root.addView(labeledSeek("Exposure", 1000, expProgress) { expProgress = it; onSettingsChanged() })
        root.addView(labeledSeek("Frames", 99, frames - 1) { frames = it + 1; updateStatus("") })

        captureButton = Button(this).apply {
            text = "START"
            setTextColor(red)
            setBackgroundColor(Color.rgb(40, 0, 0))
            setOnClickListener { onCaptureClicked() }
        }
        root.addView(captureButton, LinearLayout.LayoutParams(-1, -2).apply { setMargins(24, 8, 24, 24) })
        setContentView(root)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openIfReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        updateStatus("")
    }

    private fun labeledSeek(label: String, max: Int, start: Int, onChange: (Int) -> Unit): LinearLayout {
        val red = Color.rgb(255, 60, 60)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 4, 24, 4)
            addView(TextView(context).apply { text = label; setTextColor(red) }, LinearLayout.LayoutParams(220, -2))
            addView(SeekBar(context).apply {
                this.max = max
                progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) onChange(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun updateStatus(extra: String) {
        val sec = expNs / 1_000_000_000.0
        val text = "ISO $iso   Exp ${"%.2f".format(sec)} s   Frames $frames" +
            if (extra.isNotEmpty()) "\n$extra" else ""
        runOnUiThread { statusText.text = text }
    }

    private fun onSettingsChanged() {
        updateStatus("")
        startPreview()
    }

    // ---------- Lifecycle / permission ----------

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("camera").also { it.start() }
        bg = Handler(bgThread!!.looper)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            openIfReady()
        }
    }

    override fun onPause() {
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null
        bg = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) openIfReady()
        else updateStatus("Camera permission denied")
    }

    private fun openIfReady() {
        if (camera != null) return
        if (!textureView.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        openCamera()
    }

    // ---------- Camera ----------

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.firstOrNull {
            val c = mgr.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true &&
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        }
        if (id == null) {
            updateStatus("No back camera with RAW + manual sensor control found")
            return
        }
        val c = mgr.getCameraCharacteristics(id)
        chars = c
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange = it }
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expRange = it }

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull { it.width.toLong() * it.height }!!
        rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3).also {
            it.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage()
                if (img != null) {
                    pendingImage = img
                    trySave()
                }
            }, bg)
        }

        val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height }!!
        textureView.surfaceTexture!!.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(textureView.surfaceTexture)

        updateStatus("")
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                camera = cam
                createSession(cam)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); camera = null }
            override fun onError(cam: CameraDevice, error: Int) {
                cam.close(); camera = null
                updateStatus("Camera error $error")
            }
        }, bg)
    }

    @Suppress("DEPRECATION")
    private fun createSession(cam: CameraDevice) {
        val surfaces = listOf(previewSurface!!, rawReader!!.surface)
        cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                startPreview()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                updateStatus("Session configuration failed")
            }
        }, bg)
    }

    private fun applyManual(b: CaptureRequest.Builder, exposureNs: Long, isoValue: Int) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // 0 = infinity
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, exposureNs)
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
    }

    /** Short, boosted exposure so stars are visible for aiming; the real shot uses the full values. */
    private fun startPreview() {
        val cam = camera ?: return
        val s = session ?: return
        try {
            val previewExp = minOf(expNs, 200_000_000L).coerceAtLeast(expRange.lower)
            val boost = expNs.toDouble() / previewExp
            val previewIso = (iso * boost).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL)
            b.addTarget(previewSurface!!)
            applyManual(b, previewExp, previewIso)
            s.setRepeatingRequest(b.build(), null, bg)
        } catch (e: CameraAccessException) {
            updateStatus("Preview error: ${e.message}")
        } catch (e: IllegalStateException) {
            // session closed
        }
    }

    // ---------- Capture ----------

    private fun onCaptureClicked() {
        if (shotsLeft > 0) {
            shotsLeft = 0
            updateStatus("Stopping after current frame...")
            return
        }
        shotsLeft = frames
        frameIndex = 0
        savedCount = 0
        stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        runOnUiThread { captureButton.text = "STOP" }
        captureOne()
    }

    private fun captureOne() {
        val cam = camera ?: return
        val s = session ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL)
            b.addTarget(rawReader!!.surface)
            applyManual(b, expNs, iso)
            updateStatus("Exposing frame ${frameIndex + 1}/${frameIndex + shotsLeft}...")
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    pendingResult = result
                    trySave()
                }
            }, bg)
        } catch (e: CameraAccessException) {
            updateStatus("Capture error: ${e.message}")
            finishShooting()
        }
    }

    @Synchronized
    private fun trySave() {
        val img = pendingImage ?: return
        val res = pendingResult ?: return
        pendingImage = null
        pendingResult = null
        try {
            val dir = File(getExternalFilesDir(null), "AstroCam").apply { mkdirs() }
            val file = File(dir, "light_${stamp}_${"%03d".format(++frameIndex)}.dng")
            val dng = DngCreator(chars!!, res)
            try {
                FileOutputStream(file).use { dng.writeImage(it, img) }
            } finally {
                dng.close()
            }
            savedCount++
        } catch (e: Exception) {
            updateStatus("Save error: ${e.message}")
        } finally {
            img.close()
        }
        shotsLeft--
        if (shotsLeft > 0) {
            captureOne()
        } else {
            finishShooting()
        }
    }

    private fun finishShooting() {
        shotsLeft = 0
        updateStatus("Done: $savedCount frame(s) saved in\nAndroid/data/$packageName/files/AstroCam")
        runOnUiThread { captureButton.text = "START" }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        rawReader?.close()
        rawReader = null
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
    }
}
