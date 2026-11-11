package com.rawcam.nativepro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    // ---- Views
    private lateinit var textureView: AutoFitTextureView
    private lateinit var btnRecord: Button
    private lateinit var btnPhoto: Button
    private lateinit var timerText: TextView
    private lateinit var spBitrate: Spinner
    private lateinit var spFps: Spinner
    private lateinit var swRaw: Switch
    private lateinit var swMic: Switch
    private lateinit var swManual: Switch
    private lateinit var swFocus: Switch
    private lateinit var swBoost: Switch
    private lateinit var swAwb: Switch
    private lateinit var sbIso: SeekBar
    private lateinit var sbShutter: SeekBar
    private lateinit var sbFocus: SeekBar
    private lateinit var tvIso: TextView
    private lateinit var tvShutter: TextView
    private lateinit var tvFocus: TextView

    // ---- Threads
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val ui = Handler(Looper.getMainLooper())

    // ---- Camera
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var chars: CameraCharacteristics? = null
    private var previewSurface: Surface? = null
    private var targets: List<Surface> = emptyList()
    private var openingCamera = false
    private var previewSize = Size(1920, 1080)
    private var videoSize = Size(1920, 1080)
    private var photoSize = Size(1920, 1080)
    private var imageReader: ImageReader? = null
    private var sensorOrientation = 90

    private var isoRange: Range<Int>? = null
    private var expRange: Range<Long>? = null
    private var boostRange: Range<Int>? = null
    private var minFocus = 0f

    // ---- Recording
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    @Volatile private var recording = false
    private var recordStartMs = 0L

    // ---- Settings (volatile: read from camera thread)
    @Volatile private var bitrateMbps = 50
    @Volatile private var targetFps = 30
    @Volatile private var rawIsp = true
    @Volatile private var proMic = true
    @Volatile private var manualExp = false
    @Volatile private var manualFocus = false
    @Volatile private var boostIso = false
    @Volatile private var awbLock = false
    @Volatile private var iso = 100
    @Volatile private var exposureNs = 16_666_667L
    @Volatile private var focusDiopters = 0f

    private val shutterDenominators = intArrayOf(24, 30, 48, 60, 100, 125, 250, 500, 1000, 2000)

    // ============================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()
        setupControls()
        textureView.surfaceTextureListener = surfaceListener
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) openCamera()
    }

    override fun onPause() {
        if (recording) stopRecording(restartPreview = false)
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun bindViews() {
        textureView = findViewById(R.id.texture)
        btnRecord = findViewById(R.id.btnRecord)
        btnPhoto = findViewById(R.id.btnPhoto)
        timerText = findViewById(R.id.timer)
        spBitrate = findViewById(R.id.spBitrate)
        spFps = findViewById(R.id.spFps)
        swRaw = findViewById(R.id.swRaw)
        swMic = findViewById(R.id.swMic)
        swManual = findViewById(R.id.swManual)
        swFocus = findViewById(R.id.swFocus)
        swBoost = findViewById(R.id.swBoost)
        swAwb = findViewById(R.id.swAwb)
        sbIso = findViewById(R.id.sbIso)
        sbShutter = findViewById(R.id.sbShutter)
        sbFocus = findViewById(R.id.sbFocus)
        tvIso = findViewById(R.id.tvIso)
        tvShutter = findViewById(R.id.tvShutter)
        tvFocus = findViewById(R.id.tvFocus)
    }

    // ============================================================ permissions

    private fun hasCam() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasCam()) {
            toast("Camera permission is required")
            return
        }
        if (!hasMic()) toast("Microphone denied: videos will have no sound")
        if (textureView.isAvailable) openCamera()
    }

    // ============================================================ UI wiring

    private fun setupControls() {
        spBitrate.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("20 Mbps", "50 Mbps", "80 Mbps", "120 Mbps"))
        spBitrate.setSelection(1)
        spBitrate.onItemPicked { pos -> bitrateMbps = intArrayOf(20, 50, 80, 120)[pos] }

        spFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("24 FPS", "30 FPS", "60 FPS"))
        spFps.setSelection(1)
        spFps.onItemPicked { pos ->
            targetFps = intArrayOf(24, 30, 60)[pos]
            if (chars != null) {
                val r = fpsRange()
                if (r.lower != targetFps || r.upper != targetFps) {
                    toast("$targetFps FPS not exact on this device, using ${r.lower}-${r.upper}")
                }
                updateRepeating()
            }
        }

        swRaw.setOnCheckedChangeListener { _, c -> rawIsp = c; updateRepeating() }
        swMic.setOnCheckedChangeListener { _, c -> proMic = c }
        swManual.setOnCheckedChangeListener { _, c -> manualExp = c; refreshEnabled(); updateRepeating() }
        swFocus.setOnCheckedChangeListener { _, c -> manualFocus = c; refreshEnabled(); updateRepeating() }
        swBoost.setOnCheckedChangeListener { _, c -> boostIso = c; updateRepeating() }
        swAwb.setOnCheckedChangeListener { _, c -> awbLock = c; updateRepeating() }

        sbIso.onProgress { p -> setIsoFromProgress(p); updateRepeating() }
        sbShutter.onProgress { p -> setShutterFromIndex(p); updateRepeating() }
        sbFocus.onProgress { p -> setFocusFromProgress(p); updateRepeating() }

        sbIso.progress = 20
        sbShutter.progress = 3
        sbFocus.progress = 0
        setIsoFromProgress(20)
        setShutterFromIndex(3)
        setFocusFromProgress(0)
        refreshEnabled()

        btnPhoto.setOnClickListener { takePhoto() }
        btnRecord.setOnClickListener {
            if (recording) stopRecording(restartPreview = true) else startRecording()
        }
    }

    private fun refreshEnabled() {
        val hasIso = isoRange != null && expRange != null
        sbIso.isEnabled = manualExp && hasIso
        sbShutter.isEnabled = manualExp && hasIso
        swManual.isEnabled = hasIso
        val br = boostRange
        swBoost.isEnabled = manualExp && br != null && br.upper > 100
        sbFocus.isEnabled = manualFocus && minFocus > 0f
        swFocus.isEnabled = minFocus > 0f
    }

    private fun setIsoFromProgress(p: Int) {
        val r = isoRange
        iso = if (r == null) 100 else {
            val lo = r.lower.toDouble().coerceAtLeast(1.0)
            val hi = r.upper.toDouble()
            (lo * (hi / lo).pow(p / 100.0)).roundToInt().coerceIn(r.lower, r.upper)
        }
        tvIso.text = "ISO $iso"
    }

    private fun setShutterFromIndex(i: Int) {
        val den = shutterDenominators[i.coerceIn(0, shutterDenominators.size - 1)]
        var ns = (1_000_000_000.0 / den).toLong()
        expRange?.let { ns = ns.coerceIn(it.lower, it.upper) }
        exposureNs = ns
        tvShutter.text = "1/${(1_000_000_000.0 / ns).roundToInt()} s"
    }

    private fun setFocusFromProgress(p: Int) {
        focusDiopters = minFocus * p / 100f
        tvFocus.text = if (focusDiopters <= 0.01f) "Focus \u221E" else "Focus %.2f m".format(1f / focusDiopters)
    }

    private fun setRecordingUi(on: Boolean) {
        btnRecord.text = if (on) "STOP" else "RECORD"
        btnPhoto.isEnabled = !on
        spBitrate.isEnabled = !on
        spFps.isEnabled = !on
        swMic.isEnabled = !on
        timerText.visibility = if (on) View.VISIBLE else View.GONE
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (SystemClock.elapsedRealtime() - recordStartMs) / 1000
            timerText.text = "REC %02d:%02d".format(s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    // ============================================================ threads

    private fun startBackgroundThread() {
        if (bgThread != null) return
        bgThread = HandlerThread("CameraBg").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackgroundThread() {
        bgThread?.quitSafely()
        try { bgThread?.join() } catch (_: InterruptedException) {}
        bgThread = null
        bgHandler = null
    }

    // ============================================================ camera open / close

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            if (recording) stopRecording(restartPreview = false)
            closeSession()
            previewSurface?.release()
            previewSurface = null
            return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!hasCam() || cameraDevice != null || openingCamera) return
        if (bgHandler == null) return
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: mgr.cameraIdList.firstOrNull()
            if (id == null) { toast("No camera found"); return }

            val c = mgr.getCameraCharacteristics(id)
            chars = c
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            boostRange = c.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
            minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            pickSizes(c)
            ui.post {
                setIsoFromProgress(sbIso.progress)
                setShutterFromIndex(sbShutter.progress)
                setFocusFromProgress(sbFocus.progress)
                refreshEnabled()
            }

            openingCamera = true
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    openingCamera = false
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    openingCamera = false
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    openingCamera = false
                    camera.close()
                    cameraDevice = null
                    toast("Camera error $error")
                }
            }, bgHandler)
        } catch (e: CameraAccessException) {
            openingCamera = false
            toast("Cannot open camera: ${e.message}")
        } catch (e: SecurityException) {
            openingCamera = false
            toast("Camera permission missing")
        }
    }

    private fun closeCamera() {
        closeSession()
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        openingCamera = false
        previewSurface?.release()
        previewSurface = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    private fun closeSession() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    private fun pickSizes(c: CameraCharacteristics) {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val vs = map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        videoSize = vs.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }
            .maxByOrNull { it.width * it.height }
            ?: vs.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width * it.height }
            ?: vs.firstOrNull() ?: Size(1280, 720)

        val ps = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        previewSize = ps.filter {
            it.width * videoSize.height == it.height * videoSize.width && it.width <= 1920
        }.maxByOrNull { it.width * it.height }
            ?: ps.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: videoSize

        photoSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } ?: videoSize
        val portrait = sensorOrientation % 180 == 90
        ui.post {
            if (portrait) textureView.setAspectRatio(previewSize.height, previewSize.width)
            else textureView.setAspectRatio(previewSize.width, previewSize.height)
        }
    }

    // ============================================================ preview

    private fun startPreview() {
        val cam = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        try {
            closeSession()
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface?.release()
            val surface = Surface(st)
            previewSurface = surface
            targets = listOf(surface)
            if (imageReader == null) {
                imageReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2).also {
                    it.setOnImageAvailableListener({ r -> savePhoto(r) }, bgHandler)
                }
            }
            val sessionSurfaces = listOfNotNull(surface, imageReader?.surface)
            cam.createCaptureSession(sessionSurfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cameraDevice == null) { s.close(); return }
                    session = s
                    updateRepeating()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { toast("Preview configuration failed") }
            }, bgHandler)
        } catch (e: Exception) {
            toast("Preview error: ${e.message}")
        }
    }

    // ============================================================ capture request

    private fun fpsRange(): Range<Int> {
        val ranges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val f = targetFps
        return ranges.firstOrNull { it.lower == f && it.upper == f }
            ?: ranges.filter { it.upper == f }.maxByOrNull { it.lower }
            ?: ranges.filter { it.lower <= f && it.upper >= f }.minByOrNull { it.upper - it.lower }
            ?: ranges.filter { it.upper < f }.maxByOrNull { it.upper * 1000 + it.lower }
            ?: Range(f, f)
    }

    private fun effectiveFps(): Int = minOf(targetFps, fpsRange().upper)

    private fun applyControls(b: CaptureRequest.Builder) {
        val c = chars ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange())

        // Exposure
        if (manualExp && isoRange != null && expRange != null) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(1_000_000_000L / effectiveFps(), exposureNs))
            val br = boostRange
            if (boostIso && br != null && br.upper > 100) {
                b.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, minOf(br.upper, 400))
            }
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }

        // Focus
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        if (manualFocus && minFocus > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        } else if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        // White balance
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        if (c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            b.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock)
        }

        // ISP bypass (only flags the hardware reports as supported)
        if (rawIsp) {
            val edge = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty()
            if (edge.contains(CameraMetadata.EDGE_MODE_OFF)) {
                b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
            }
            val nr = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty()
            when {
                nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) ->
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) ->
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            }
        }
    }

    private fun updateRepeating() {
        val s = session ?: return
        val cam = cameraDevice ?: return
        if (targets.isEmpty()) return
        try {
            val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val b = cam.createCaptureRequest(template)
            targets.forEach { b.addTarget(it) }
            applyControls(b)
            s.setRepeatingRequest(b.build(), null, bgHandler)
        } catch (_: IllegalStateException) {
            // session closing; ignore
        } catch (e: Exception) {
            toast("Camera settings error: ${e.message}")
        }
    }

    // ============================================================ recording

    private fun createRecorder(audioSource: Int?): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            if (audioSource != null) r.setAudioSource(audioSource)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(pfd!!.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoEncodingBitRate(bitrateMbps * 1_000_000)
            r.setVideoFrameRate(effectiveFps())
            r.setVideoSize(videoSize.width, videoSize.height)
            if (audioSource != null) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(320_000)
                r.setAudioSamplingRate(48_000)
                r.setAudioChannels(2)
            }
            r.setOrientationHint(sensorOrientation)
            r.prepare()
            return r
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            throw e
        }
    }

    private fun setUpRecorder(): Boolean {
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/NativeCamera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { toast("Cannot create video file"); return false }
        val fd = contentResolver.openFileDescriptor(uri, "rw")
        if (fd == null) {
            contentResolver.delete(uri, null, null)
            toast("Cannot open video file")
            return false
        }
        videoUri = uri
        pfd = fd

        val sources: List<Int?> = when {
            !hasMic() -> listOf(null)
            proMic -> listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
            else -> listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
        }
        var rec: MediaRecorder? = null
        var lastError: Exception? = null
        for (s in sources) {
            try { rec = createRecorder(s); break } catch (e: Exception) { lastError = e }
        }
        if (rec == null) {
            toast("Recorder setup failed (try a lower bitrate/FPS): ${lastError?.message}")
            discardFile()
            return false
        }
        recorder = rec
        return true
    }

    private fun startRecording() {
        val cam = cameraDevice ?: run { toast("Camera not ready"); return }
        val ps = previewSurface ?: run { toast("Preview not ready"); return }
        if (recording) return
        try {
            closeSession()
            if (!setUpRecorder()) { startPreview(); return }
            val rs = recorder!!.surface
            targets = listOf(ps, rs)
            cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cameraDevice == null) { s.close(); return }
                    session = s
                    recording = true
                    updateRepeating()
                    try {
                        recorder?.start()
                        recordStartMs = SystemClock.elapsedRealtime()
                        ui.post {
                            setRecordingUi(true)
                            ui.post(timerRunnable)
                        }
                    } catch (e: Exception) {
                        recording = false
                        toast("Encoder rejected settings (try a lower bitrate/FPS)")
                        ui.post { releaseRecorderAndFinish(discard = true); startPreview() }
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    toast("Recording session failed (try lower FPS)")
                    ui.post { releaseRecorderAndFinish(discard = true); startPreview() }
                }
            }, bgHandler)
        } catch (e: Exception) {
            toast("Cannot start recording: ${e.message}")
            releaseRecorderAndFinish(discard = true)
            startPreview()
        }
    }

    private fun stopRecording(restartPreview: Boolean) {
        if (!recording) return
        recording = false
        ui.removeCallbacks(timerRunnable)
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        var bad = false
        try { recorder?.stop() } catch (_: RuntimeException) { bad = true }
        releaseRecorderAndFinish(discard = bad)
        setRecordingUi(false)
        toast(if (bad) "Recording too short, discarded" else "Saved to DCIM/NativeCamera")
        if (restartPreview) startPreview()
    }

    private fun releaseRecorderAndFinish(discard: Boolean) {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        if (discard) discardFile() else finalizeFile()
    }

    private fun finalizeFile() {
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        videoUri?.let {
            val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            try { contentResolver.update(it, v, null, null) } catch (_: Exception) {}
        }
        videoUri = null
    }

    private fun discardFile() {
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        videoUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        videoUri = null
    }

    // ============================================================ photos

    private fun takePhoto() {
        if (recording) return
        val cam = cameraDevice ?: return
        val s = session ?: return
        val ir = imageReader ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(ir.surface)
            applyControls(b)
            val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            if (!(manualFocus && minFocus > 0f) && afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            s.capture(b.build(), null, bgHandler)
        } catch (e: Exception) {
            toast("Photo failed: ${e.message}")
        }
    }

    private fun savePhoto(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/NativeCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) { toast("Cannot create photo file"); return }
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            toast("Photo saved to DCIM/NativeCamera")
        } catch (e: Exception) {
            toast("Saving photo failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    // ============================================================ helpers

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun SeekBar.onProgress(cb: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { cb(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun Spinner.onItemPicked(cb: (Int) -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { cb(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}
