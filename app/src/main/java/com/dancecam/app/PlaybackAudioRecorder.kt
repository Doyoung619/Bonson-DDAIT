package com.dancecam.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import java.io.OutputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PlaybackAudioRecorder(
    private val mediaProjection: MediaProjection,
    private val outputStreamProvider: () -> OutputStream,
    private val onError: (String) -> Unit
) {
    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var dataBytesWritten = 0L

    fun start() {
        if (!isRecording.compareAndSet(false, true)) return

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBufferSize.coerceAtLeast(SAMPLE_RATE) * 2)

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        worker = thread(name = "DanceCamPlaybackAudio") {
            writeWav(audioRecord ?: return@thread, bufferSize)
        }
    }

    fun stop() {
        if (!isRecording.compareAndSet(true, false)) return

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        worker?.join(1500)
        audioRecord?.release()
        audioRecord = null
        worker = null
    }

    private fun writeWav(record: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        dataBytesWritten = 0L

        try {
            val pcmBuffer = ByteArrayOutputStream()
            BufferedOutputStream(pcmBuffer).use { stream ->
                record.startRecording()

                while (isRecording.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        stream.write(buffer, 0, read)
                        dataBytesWritten += read.toLong()
                    } else if (read < 0) {
                        onError("AudioRecord read failed: $read")
                        isRecording.set(false)
                    }
                }
            }
            outputStreamProvider().use { output ->
                output.write(wavHeader(dataBytesWritten))
                pcmBuffer.writeTo(output)
            }
        } catch (error: SecurityException) {
            onError("Playback audio permission denied: ${error.message}")
        } catch (error: Exception) {
            onError("Playback audio capture failed: ${error.message}")
        } finally {
            isRecording.set(false)
        }
    }

    private fun wavHeader(pcmDataSize: Long): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE
        val blockAlign = CHANNEL_COUNT * BYTES_PER_SAMPLE
        val totalDataLen = pcmDataSize + 36

        return ByteBuffer.allocate(WAV_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt(totalDataLen.toInt())
            .put("WAVE".toByteArray(Charsets.US_ASCII))
            .put("fmt ".toByteArray(Charsets.US_ASCII))
            .putInt(16)
            .putShort(1.toShort())
            .putShort(CHANNEL_COUNT.toShort())
            .putInt(SAMPLE_RATE)
            .putInt(byteRate)
            .putShort(blockAlign.toShort())
            .putShort(16.toShort())
            .put("data".toByteArray(Charsets.US_ASCII))
            .putInt(pcmDataSize.toInt())
            .array()
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val WAV_HEADER_SIZE = 44
    }
}
