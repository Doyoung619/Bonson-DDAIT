package com.dancecam.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var switchButton: Button
    private lateinit var orientationButton: Button
    private lateinit var zoomOutButton: Button
    private lateinit var zoomInButton: Button
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var playbackAudioRecorder: PlaybackAudioRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var captureMode = CaptureMode.PORTRAIT
    private var pendingStartAfterProjection = false
    private var pendingRecordAfterPermissions = false
    private var isStoppingRecording = false
    private var isSaving = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            runOnUiThread {
                playbackAudioRecorder?.stop()
                playbackAudioRecorder = null
                mediaProjection = null
                stopProjectionService()
                if (recording != null && !isStoppingRecording) {
                    recording?.stop()
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true ||
            hasPermission(Manifest.permission.CAMERA)
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            hasPermission(Manifest.permission.RECORD_AUDIO)

        if (cameraGranted && audioGranted) {
            startCamera()
            if (pendingRecordAfterPermissions) {
                pendingRecordAfterPermissions = false
                requestProjectionAndRecord()
            } else {
                showReadyNotice()
            }
        } else {
            pendingRecordAfterPermissions = false
            setStatus("권한이 필요합니다: 카메라와 오디오 캡처를 허용해주세요.")
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            if (!startProjectionService()) {
                pendingStartAfterProjection = false
                setStatus("녹화 준비 실패: 알림 권한을 허용한 뒤 다시 시도해주세요.")
                return@registerForActivityResult
            }

            val projection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
            if (projection == null) {
                pendingStartAfterProjection = false
                stopProjectionService()
                setStatus("오디오 캡처 권한을 준비하지 못했습니다.")
            } else {
                projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                mediaProjection = projection
                if (pendingStartAfterProjection) {
                    pendingStartAfterProjection = false
                    startRecording()
                }
            }
        } else {
            pendingStartAfterProjection = false
            setStatus("오디오 캡처 권한이 취소되었습니다.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        buildUi()
        applyCaptureOrientation()
        showReadyNotice()

        if (hasRequiredPermissions()) {
            startCamera()
        } else {
            requestRequiredPermissions()
        }
    }

    override fun onDestroy() {
        stopRecording()
        finishProjectionSession()
        super.onDestroy()
    }

    private fun buildUi() {
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        statusText = TextView(this).apply {
            text = "준비 완료"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            textSize = 14f
            setPadding(24, 14, 24, 14)
        }

        recordButton = Button(this).apply {
            text = "REC"
            textSize = 18f
            setOnClickListener {
                if (recording == null) ensureReadyThenRecord() else stopRecording()
            }
        }

        switchButton = Button(this).apply {
            text = "전환"
            textSize = 14f
            setOnClickListener { switchCamera() }
        }

        orientationButton = Button(this).apply {
            text = captureMode.label
            textSize = 14f
            setOnClickListener { toggleCaptureOrientation() }
        }

        zoomOutButton = Button(this).apply {
            text = "-"
            textSize = 24f
            setOnClickListener { adjustZoom(-0.08f) }
        }

        zoomInButton = Button(this).apply {
            text = "+"
            textSize = 24f
            setOnClickListener { adjustZoom(0.08f) }
        }

        zoomSeekBar = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) setZoom(progress / 100f)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        val zoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 0, 24, 10)
            addView(zoomOutButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(zoomSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4f))
            addView(zoomInButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 0, 24, 48)
            addView(switchButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(recordButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(orientationButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(zoomControls, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(controls, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val root = FrameLayout(this)
        root.addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        setContentView(root)
    }

    private fun ensureReadyThenRecord() {
        if (isSaving || isStoppingRecording) return
        if (!hasRequiredPermissions()) {
            pendingRecordAfterPermissions = true
            requestRequiredPermissions()
            return
        }
        if (mediaProjection == null) {
            requestProjectionAndRecord()
            return
        }
        startRecording()
    }

    private fun requestProjectionAndRecord() {
        pendingStartAfterProjection = true
        try {
            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (error: Exception) {
            pendingStartAfterProjection = false
            setStatus("캡처 권한 요청 실패: ${error.message}")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .setTargetRotation(targetRotation())
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()

                videoCapture = VideoCapture.withOutput(recorder).also {
                    it.targetRotation = targetRotation()
                }

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                zoomSeekBar.progress = 0
                setZoom(0f)
                showReadyNotice()
            } catch (error: Exception) {
                setStatus("카메라 시작 실패: ${error.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showReadyNotice() {
        setStatus("준비 완료 · ${captureMode.label}\n음악이 켜진 상태에서 안 되면 REC → 캡처 승인 → 음악 재생 순서로 해주세요.")
    }

    private fun startRecording() {
        val capture = videoCapture ?: run {
            setStatus("카메라가 아직 준비되지 않았습니다.")
            finishProjectionSession()
            return
        }
        val projection = mediaProjection ?: run {
            setStatus("오디오 캡처 권한이 준비되지 않았습니다.")
            return
        }

        capture.targetRotation = targetRotation()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val workDir = File(cacheDir, "recordings").apply { mkdirs() }
        val videoFile = File(workDir, "dancecam_${stamp}_video.mp4")
        val audioFile = File(workDir, "dancecam_${stamp}_audio.wav")
        val finalName = "dancecam_$stamp.mp4"

        try {
            isStoppingRecording = false
            playbackAudioRecorder = PlaybackAudioRecorder(projection, audioFile) { message ->
                runOnUiThread { setStatus("오디오 오류: $message") }
            }.also { it.start() }

            val outputOptions = FileOutputOptions.Builder(videoFile).build()
            recording = capture.output
                .prepareRecording(this, outputOptions)
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            recordButton.text = "STOP"
                            switchButton.visibility = View.GONE
                            orientationButton.isEnabled = false
                            setStatus("녹화 중 · ${captureMode.label}")
                        }
                        is VideoRecordEvent.Finalize -> {
                            recording = null
                            isStoppingRecording = false
                            playbackAudioRecorder?.stop()
                            playbackAudioRecorder = null
                            recordButton.text = "REC"
                            switchButton.visibility = View.VISIBLE
                            orientationButton.isEnabled = true
                            finishProjectionSession()

                            if (event.hasError()) {
                                setStatus("영상 저장 실패: ${event.error}")
                            } else {
                                muxAndSave(videoFile, audioFile, finalName)
                            }
                        }
                    }
                }
        } catch (error: SecurityException) {
            cleanupAfterStartFailure()
            setStatus("권한 오류: ${error.message}")
        } catch (error: Exception) {
            cleanupAfterStartFailure()
            setStatus("녹화 시작 실패: ${error.message}")
        }
    }

    private fun stopRecording() {
        if (isStoppingRecording || isSaving) return
        isStoppingRecording = true
        setStatus("저장 준비 중...")
        recording?.stop()
        if (recording == null) {
            playbackAudioRecorder?.stop()
            playbackAudioRecorder = null
            recordButton.text = "REC"
            switchButton.visibility = View.VISIBLE
            orientationButton.isEnabled = true
            finishProjectionSession()
            isStoppingRecording = false
        }
    }

    private fun cleanupAfterStartFailure() {
        playbackAudioRecorder?.stop()
        playbackAudioRecorder = null
        recording?.stop()
        recording = null
        isStoppingRecording = false
        recordButton.text = "REC"
        switchButton.visibility = View.VISIBLE
        orientationButton.isEnabled = true
        finishProjectionSession()
    }

    private fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()
    }

    private fun toggleCaptureOrientation() {
        if (recording != null || isSaving) return
        captureMode = if (captureMode == CaptureMode.PORTRAIT) CaptureMode.LANDSCAPE else CaptureMode.PORTRAIT
        orientationButton.text = captureMode.label
        applyCaptureOrientation()
        startCamera()
    }

    private fun applyCaptureOrientation() {
        requestedOrientation = when (captureMode) {
            CaptureMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            CaptureMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun targetRotation(): Int {
        return when (captureMode) {
            CaptureMode.PORTRAIT -> Surface.ROTATION_0
            CaptureMode.LANDSCAPE -> Surface.ROTATION_90
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasRequiredPermissions(): Boolean {
        return hasPermission(Manifest.permission.CAMERA) && hasPermission(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startProjectionService(): Boolean {
        return try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MediaProjectionForegroundService::class.java)
            )
            true
        } catch (error: Exception) {
            false
        }
    }

    private fun stopProjectionService() {
        stopService(Intent(this, MediaProjectionForegroundService::class.java))
    }

    private fun muxAndSave(videoFile: File, audioFile: File, finalName: String) {
        isSaving = true
        recordButton.isEnabled = false
        setStatus("저장 중입니다...")
        Thread {
            try {
                VideoAudioMuxer(contentResolver).muxToMovies(videoFile, audioFile, finalName)
                runOnUiThread {
                    isSaving = false
                    recordButton.isEnabled = true
                    showReadyNotice()
                    setStatus("저장되었습니다!\n갤러리의 DanceCam 폴더에서 확인하세요.")
                }
            } catch (error: Exception) {
                runOnUiThread {
                    isSaving = false
                    recordButton.isEnabled = true
                    setStatus("저장 실패: ${error.message}")
                }
            } finally {
                videoFile.delete()
                audioFile.delete()
            }
        }.start()
    }

    private fun finishProjectionSession() {
        val projection = mediaProjection
        mediaProjection = null
        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            try {
                projection.stop()
            } catch (_: Exception) {
            }
        }
        stopProjectionService()
    }

    private fun adjustZoom(delta: Float) {
        val next = ((zoomSeekBar.progress / 100f) + delta).coerceIn(0f, 1f)
        zoomSeekBar.progress = (next * 100).toInt()
        setZoom(next)
    }

    private fun setZoom(linearZoom: Float) {
        camera?.cameraControl?.setLinearZoom(linearZoom.coerceIn(0f, 1f))
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private enum class CaptureMode(val label: String) {
        PORTRAIT("세로"),
        LANDSCAPE("가로")
    }
}
