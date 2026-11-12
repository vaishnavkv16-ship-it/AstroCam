package com.example.astrocam

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Lens(val label: String, val cameraId: String, val hasRaw: Boolean)

/** All Camera2 work: lens list, preview, manual controls, RAW/JPEG capture and saving. */
class CameraEngine(private val activity: Activity, private val listener: Listener) {

    interface Listener {
        fun onMessage(msg: String)
        fun onError(msg: String)
        fun onOpened()
        fun onPreviewSize(w: Int, h: Int)
        fun onFrameSaved(path: String)
        fun onCaptureFailed(msg: String)
    }

    private val mgr = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    var lenses: List<Lens> = emptyList()
        private set
    var isoRange: Range<Int> = Range(100, 800)
        private set
    var expRange: Range<Long> = Range(1_000_000L, 1_000_000_000L)
        private set
    var evRange: Range<Int> = Range(0, 0)
        private set
    var evStep = 0f
        private set
    var minFocusDiopter = 0f
        private set
    var hyperfocalDiopter = 0f
        private set
    var awbModes: List<Int> = listOf(1)
        private set
    var sensorOrientation = 90
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var camera: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    private var chars: CameraCharacteristics? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var wantRaw = true
    private var wantJpeg = false
    private var gen = 0

    private val lock = Any()
    private var pendingRaw: Image? = null
    private var pendingJpeg: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var curStamp = ""
    private var curIndex = 0
    private var curType = FrameType.LIGHT
    private var curWantRaw = true
    private var curWantJpeg = false

    init {
        try {
            discover()
        } catch (e: Exception) {
            lenses = emptyList()
        }
    }

    // ------------------------------------------------------------ Lenses

    private fun equivMm(c: CameraCharacteristics): Int? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = sqrt(sz.width * sz.width + sz.height * sz.height)
        if (diag <= 0f) return null
        return (f * 43.27f / diag).roundToInt()
    }

    private fun discover() {
        val found = ArrayList<Pair<Int, Lens>>()
        for (id in mgr.cameraIdList) {
            val c = try {
                mgr.getCameraCharacteristics(id)
            } catch (e: Exception) {
                continue
            }
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) continue
            val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val mm = equivMm(c)
            val name = (if (mm != null) "${mm}mm" else "Cam $id") + (if (raw) "" else " (no RAW)")
            found.add((mm ?: 0) to Lens(name, id, raw))
        }
        lenses = found.sortedBy { it.first }.map { it.second }
    }

    // ------------------------------------------------------------ Open / close

    val isOpen: Boolean
        get() = camera != null

    fun freeBytes(): Long = activity.getExternalFilesDir(null)?.usableSpace ?: 0L

    @SuppressLint("MissingPermission")
    fun open(target: Lens, tv: TextureView, raw: Boolean, jpeg: Boolean) {
        close()
        val myGen = ++gen
        thread = HandlerThread("cam").also { it.start() }
        handler = Handler(thread!!.looper)
        wantRaw = raw
        wantJpeg = jpeg
        if (raw && !target.hasRaw) {
            listener.onError("This lens has no RAW. Change the format to JPEG in Settings.")
            return
        }
        try {
            val c = mgr.getCameraCharacteristics(target.cameraId)
            chars = c
            c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange = it }
            c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expRange = it }
            c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { evRange = it }
            evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
            minFocusDiopter = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            hyperfocalDiopter = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
            awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: listOf(1)
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                listener.onError("Camera has no stream map")
                return
            }
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1280, 720))
            val pv = previewSizes.filter { it.width <= 1920 }
                .maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)
            val st = tv.surfaceTexture
            if (st == null) {
                listener.onError("Preview surface is not ready")
                return
            }
            st.setDefaultBufferSize(pv.width, pv.height)
            previewSurface = Surface(st)
            listener.onPreviewSize(pv.width, pv.height)

            if (raw) {
                val rs = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
                if (rs == null) {
                    listener.onError("RAW is not available on this lens")
                    return
                }
                rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 3).also {
                    it.setOnImageAvailableListener({ r -> onRawImage(r) }, handler)
                }
            }
            if (jpeg) {
                val js = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
                if (js == null) {
                    listener.onError("JPEG is not available on this lens")
                    return
                }
                jpegReader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 3).also {
                    it.setOnImageAvailableListener({ r -> onJpegImage(r) }, handler)
                }
            }

            mgr.openCamera(target.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (myGen != gen) {
                        cam.close()
                        return
                    }
                    camera = cam
                    createSession(cam, myGen)
                }

                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    if (camera === cam) camera = null
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close()
                    if (camera === cam) camera = null
                    listener.onError("Camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            listener.onError("Could not open camera: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(cam: CameraDevice, myGen: Int) {
        val surfaces = ArrayList<Surface>()
        previewSurface?.let { surfaces.add(it) }
        rawReader?.let { surfaces.add(it.surface) }
        jpegReader?.let { surfaces.add(it.surface) }
        try {
            cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (myGen != gen) {
                        s.close()
                        return
                    }
                    session = s
                    listener.onOpened()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener.onError("This format did not work on this lens. Try RAW only or JPEG only.")
                }
            }, handler)
        } catch (e: Exception) {
            listener.onError("Session error: ${e.message}")
        }
    }

    fun close() {
        gen++
        try { session?.close() } catch (e: Exception) { }
        session = null
        try { camera?.close() } catch (e: Exception) { }
        camera = null
        try { rawReader?.close() } catch (e: Exception) { }
        rawReader = null
        try { jpegReader?.close() } catch (e: Exception) { }
        jpegReader = null
        synchronized(lock) {
            pendingRaw?.close()
            pendingRaw = null
            pendingJpeg?.close()
            pendingJpeg = null
            pendingResult = null
        }
        previewSurface?.release()
        previewSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------ Requests

    private fun applySettings(b: CaptureRequest.Builder, s: Settings, preview: Boolean) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(
            CaptureRequest.CONTROL_AWB_MODE,
            if (awbModes.contains(s.awb)) s.awb else CaptureRequest.CONTROL_AWB_MODE_AUTO
        )

        val bias = !preview && s.frameType == FrameType.BIAS
        if (s.autoExposure && !bias) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.ev.coerceIn(evRange.lower, evRange.upper))
        } else {
            var exp = if (bias) expRange.lower else s.expNs.coerceIn(expRange.lower, expRange.upper)
            var isoValue = s.iso.coerceIn(isoRange.lower, isoRange.upper)
            if (preview) {
                // Short boosted exposure so the live view stays smooth.
                val pe = min(exp, 200_000_000L).coerceAtLeast(expRange.lower)
                isoValue = (isoValue.toDouble() * exp / pe).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
                exp = pe
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (exp > 33_000_000L) exp else 33_000_000L)
        }

        when (s.focusMode) {
            FocusMode.AUTO -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            FocusMode.INFINITY -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
            FocusMode.HYPERFOCAL -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, hyperfocalDiopter)
            }
            FocusMode.MANUAL -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                val d = if (minFocusDiopter > 0f) s.focusDiopter.coerceIn(0f, minFocusDiopter) else 0f
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, d)
            }
        }

        if (wantRaw && !wantJpeg) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        }
    }

    fun applyPreview(s: Settings) {
        val cam = camera ?: return
        val ss = session ?: return
        val surface = previewSurface ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            applySettings(b, s, true)
            ss.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            listener.onMessage("Preview: ${e.message}")
        }
    }

    // ------------------------------------------------------------ Capture

    fun capture(s: Settings, stamp: String, index: Int) {
        val cam = camera
        val ss = session
        if (cam == null || ss == null) {
            listener.onCaptureFailed("Camera is not ready")
            return
        }
        synchronized(lock) {
            pendingRaw?.close()
            pendingRaw = null
            pendingJpeg?.close()
            pendingJpeg = null
            pendingResult = null
            curStamp = stamp
            curIndex = index
            curType = s.frameType
            curWantRaw = wantRaw
            curWantJpeg = wantJpeg
        }
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            rawReader?.let { b.addTarget(it.surface) }
            jpegReader?.let {
                b.addTarget(it.surface)
                b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }
            applySettings(b, s, false)
            ss.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    synchronized(lock) { pendingResult = result }
                    trySave()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    listener.onCaptureFailed("Capture failed (reason ${failure.reason})")
                }
            }, handler)
        } catch (e: Exception) {
            listener.onCaptureFailed("Capture error: ${e.message}")
        }
    }

    private fun onRawImage(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        synchronized(lock) {
            pendingRaw?.close()
            pendingRaw = img
        }
        trySave()
    }

    private fun onJpegImage(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        synchronized(lock) {
            pendingJpeg?.close()
            pendingJpeg = img
        }
        trySave()
    }

    private fun trySave() {
        val taken: Triple<TotalCaptureResult, Image?, Image?> = synchronized(lock) {
            val r = pendingResult ?: return
            if (curWantRaw && pendingRaw == null) return
            if (curWantJpeg && pendingJpeg == null) return
            val t = Triple(r, pendingRaw, pendingJpeg)
            pendingRaw = null
            pendingJpeg = null
            pendingResult = null
            t
        }
        val result = taken.first
        val rawImg = taken.second
        val jpgImg = taken.third
        val base = "${curType.prefix}_${curStamp}_${"%03d".format(curIndex)}"
        var lastPath = ""
        try {
            if (rawImg != null) {
                val dng = DngCreator(chars!!, result)
                dng.setOrientation(exifOrientation())
                val out = openOut("$base.dng", "image/x-adobe-dng", curType.prefix)
                try {
                    dng.writeImage(out.stream, rawImg)
                } finally {
                    dng.close()
                    finishOut(out)
                }
                lastPath = out.path
            }
            if (jpgImg != null) {
                val buf = jpgImg.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val out = openOut("$base.jpg", "image/jpeg", curType.prefix)
                try {
                    out.stream.write(bytes)
                } finally {
                    finishOut(out)
                }
                lastPath = out.path
            }
            listener.onFrameSaved(lastPath)
        } catch (e: Exception) {
            listener.onCaptureFailed("Save error: ${e.message}")
        } finally {
            rawImg?.close()
            jpgImg?.close()
        }
    }

    private fun exifOrientation(): Int = when (sensorOrientation) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    // ------------------------------------------------------------ Saving

    private class Out(val stream: OutputStream, val uri: Uri?, val path: String)

    /** Android 10+: saved to Pictures/AstroCam/<type> so they show in the gallery. Older: app folder. */
    private fun openOut(name: String, mime: String, sub: String): Out {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val rel = "Pictures/AstroCam/$sub"
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                if (uri != null) {
                    val st = activity.contentResolver.openOutputStream(uri)
                    if (st != null) return Out(st, uri, "$rel/$name")
                }
            } catch (e: Exception) {
                // fall back to the app folder below
            }
        }
        val dir = File(activity.getExternalFilesDir(null), "AstroCam/$sub")
        dir.mkdirs()
        val f = File(dir, name)
        return Out(FileOutputStream(f), null, f.absolutePath)
    }

    private fun finishOut(o: Out) {
        try { o.stream.close() } catch (e: Exception) { }
        val uri = o.uri
        if (uri != null) {
            try {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.IS_PENDING, 0)
                activity.contentResolver.update(uri, v, null, null)
            } catch (e: Exception) { }
        }
    }
}
