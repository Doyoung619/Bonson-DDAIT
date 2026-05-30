package com.dancecam.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var switchButton: Button
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var playbackAudioRecorder: PlaybackAudioRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var pendingStartAfterProjection = false
    private var pendingRecordAfterPermissions = false
    private var currentVideoUri: Uri? = null
    private var currentAudioUri: Uri? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            runOnUiThread {
                playbackAudioRecorder?.stop()
                playbackAudioRecorder = null
                mediaProjection = null
                stopProjectionService()
                if (recording != null) {
                    recording?.stop()
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true || hasPermission(Manifest.permission.RECORD_AUDIO)

        if (cameraGranted && audioGranted) {
            startCamera()
            if (pendingRecordAfterPermissions) {
                pendingRecordAfterPermissions = false
                requestProjectionAndRecord()
            } else {
                setStatus("Ready")
            }
        } else {
            pendingRecordAfterPermissions = false
            setStatus("Error: Camera and RECORD_AUDIO permissions are required")
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startProjectionService()
            val projection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
            if (projection == null) {
                pendingStartAfterProjection = false
                stopProjectionService()
                setStatus("Error: Playback capture permission is not ready")
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
            setStatus("Error: Playback capture permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        buildUi()
        setStatus("Ready")

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
            text = "Ready"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            textSize = 14f
            setPadding(24, 14, 24, 14)
        }

        recordButton = Button(this).apply {
            text = "Record"
            textSize = 18f
            setOnClickListener {
                if (recording == null) {
                    ensureReadyThenRecord()
                } else {
                    stopRecording()
                }
            }
        }

        switchButton = Button(this).apply {
            text = "Switch"
            setOnClickListener { switchCamera() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 48)
            addView(switchButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(recordButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        }

        val root = FrameLayout(this)
        root.addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(
            statusText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )
        root.addView(
            controls,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        setContentView(root)
    }

    private fun ensureReadyThenRecord() {
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
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                setStatus("Ready")
            } catch (error: Exception) {
                setStatus("Error: Camera start failed: ${error.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val capture = videoCapture ?: run {
            setStatus("Error: Camera is not ready")
            finishProjectionSession()
            return
        }
        val projection = mediaProjection ?: run {
            setStatus("Error: Playback capture permission is not ready")
            return
        }
        startProjectionService()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val videoName = "dancecam_$stamp.mp4"
        val audioName = "dancecam_$stamp.wav"
        val audioUri = createAudioUri(audioName)
        if (audioUri == null) {
            setStatus("Error: Could not create audio file")
            finishProjectionSession()
            return
        }
        currentAudioUri = audioUri

        try {
            playbackAudioRecorder = PlaybackAudioRecorder(
                projection,
                {
                    contentResolver.openOutputStream(audioUri, "w")
                        ?: error("Could not open WAV output stream")
                }
            ) { message ->
                runOnUiThread { setStatus("Error: $message") }
            }.also { it.start() }

            val outputOptions = MediaStoreOutputOptions.Builder(
                contentResolver,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )
                .setContentValues(videoContentValues(videoName))
                .build()
            recording = capture.output
                .prepareRecording(this, outputOptions)
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            recordButton.text = "Stop"
                            switchButton.visibility = View.GONE
                            setStatus("Recording")
                        }
                        is VideoRecordEvent.Finalize -> {
                            recording = null
                            playbackAudioRecorder?.stop()
                            playbackAudioRecorder = null
                            recordButton.text = "Record"
                            switchButton.visibility = View.VISIBLE
                            finishProjectionSession()

                            if (event.hasError()) {
                                setStatus("Error: Video save failed: ${event.error}")
                            } else {
                                currentVideoUri = event.outputResults.outputUri
                                setStatus("Saved: ${event.outputResults.outputUri}\nAudio: $audioUri")
                            }
                        }
                    }
                }
        } catch (error: SecurityException) {
            cleanupAfterStartFailure()
            setStatus("Error: Permission denied: ${error.message}")
        } catch (error: Exception) {
            cleanupAfterStartFailure()
            setStatus("Error: Recording failed: ${error.message}")
        }
    }

    private fun stopRecording() {
        recording?.stop()
        if (recording == null) {
            playbackAudioRecorder?.stop()
            playbackAudioRecorder = null
            recordButton.text = "Record"
            switchButton.visibility = View.VISIBLE
            finishProjectionSession()
        }
    }

    private fun cleanupAfterStartFailure() {
        playbackAudioRecorder?.stop()
        playbackAudioRecorder = null
        recording?.stop()
        recording = null
        recordButton.text = "Record"
        switchButton.visibility = View.VISIBLE
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

    private fun requestRequiredPermissions() {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun hasRequiredPermissions(): Boolean {
        return hasPermission(Manifest.permission.CAMERA) && hasPermission(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startProjectionService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MediaProjectionForegroundService::class.java)
        )
    }

    private fun stopProjectionService() {
        stopService(Intent(this, MediaProjectionForegroundService::class.java))
    }

    private fun videoContentValues(displayName: String): ContentValues {
        return ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/DanceCam")
        }
    }

    private fun audioContentValues(displayName: String): ContentValues {
        return ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/DanceCam")
        }
    }

    private fun createAudioUri(displayName: String): Uri? {
        return contentResolver.insert(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            audioContentValues(displayName)
        )
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

    private fun setStatus(message: String) {
        statusText.text = message
    }
}
