package com.dancecam.app

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

class AiFollowAnalyzer(
    private val onZoomSuggestion: (Float) -> Unit
) : ImageAnalysis.Analyzer {
    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )
    private val isProcessing = AtomicBoolean(false)
    private var currentZoom = 0f
    private var lastUpdateMs = 0L

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || !isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { pose ->
                val landmarks = pose.allPoseLandmarks
                if (landmarks.isNotEmpty()) {
                    updateZoom(landmarks, image.width, image.height)
                }
            }
            .addOnCompleteListener {
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
    }

    private fun updateZoom(landmarks: List<PoseLandmark>, width: Int, height: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdateMs < UPDATE_INTERVAL_MS) return
        lastUpdateMs = now

        val minX = landmarks.minOf { it.position.x }.coerceIn(0f, width.toFloat())
        val maxX = landmarks.maxOf { it.position.x }.coerceIn(0f, width.toFloat())
        val minY = landmarks.minOf { it.position.y }.coerceIn(0f, height.toFloat())
        val maxY = landmarks.maxOf { it.position.y }.coerceIn(0f, height.toFloat())

        val marginX = minOf(minX / width, (width - maxX) / width)
        val marginY = minOf(minY / height, (height - maxY) / height)
        val bodyHeight = (maxY - minY) / height
        val edgeMargin = minOf(marginX, marginY)

        val target = when {
            edgeMargin < 0.10f -> 0f
            bodyHeight < 0.42f && edgeMargin > 0.18f -> 0.32f
            bodyHeight < 0.58f && edgeMargin > 0.16f -> 0.18f
            else -> currentZoom
        }

        val smoothed = currentZoom + (target - currentZoom) * 0.25f
        if (kotlin.math.abs(smoothed - currentZoom) > 0.015f) {
            currentZoom = smoothed.coerceIn(0f, 0.45f)
            onZoomSuggestion(currentZoom)
        }
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 350L
    }
}
