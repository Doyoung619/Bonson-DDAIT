package com.dancecam.app

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.min

class VideoAudioMuxer(private val contentResolver: ContentResolver) {
    fun muxToMovies(videoFile: File, wavFile: File, displayName: String): Uri {
        val outputUri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/DanceCam")
            }
        ) ?: error("Could not create final MP4")

        val tempOutput = File.createTempFile("dancecam_muxed_", ".mp4")
        try {
            muxToFile(videoFile, wavFile, tempOutput)
            contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                tempOutput.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not open final MP4")
            return outputUri
        } catch (error: Exception) {
            contentResolver.delete(outputUri, null, null)
            throw error
        } finally {
            tempOutput.delete()
        }
    }

    private fun muxToFile(videoFile: File, wavFile: File, outputFile: File) {
        val audioSamples = encodeWavToAac(wavFile)
        val extractor = MediaExtractor()
        extractor.setDataSource(videoFile.absolutePath)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val sourceVideoTrack = findTrack(extractor, "video/")
            if (sourceVideoTrack < 0) error("No video track found")
            extractor.selectTrack(sourceVideoTrack)
            val sourceVideoFormat = extractor.getTrackFormat(sourceVideoTrack)
            if (sourceVideoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(sourceVideoFormat.getInteger(MediaFormat.KEY_ROTATION))
            }

            val muxVideoTrack = muxer.addTrack(sourceVideoFormat)
            val muxAudioTrack = if (audioSamples.format != null && audioSamples.samples.isNotEmpty()) {
                muxer.addTrack(audioSamples.format)
            } else {
                -1
            }

            muxer.start()
            copyVideoSamples(extractor, muxer, muxVideoTrack)
            if (muxAudioTrack >= 0) {
                for (sample in audioSamples.samples) {
                    muxer.writeSampleData(muxAudioTrack, ByteBuffer.wrap(sample.data), sample.info)
                }
            }
        } finally {
            try {
                muxer.stop()
            } catch (_: Exception) {
            }
            muxer.release()
            extractor.release()
        }
    }

    private fun copyVideoSamples(extractor: MediaExtractor, muxer: MediaMuxer, muxVideoTrack: Int) {
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(
                0,
                size,
                extractor.sampleTime.coerceAtLeast(0L),
                extractor.sampleFlags
            )
            muxer.writeSampleData(muxVideoTrack, buffer, info)
            extractor.advance()
            buffer.clear()
        }
    }

    private fun encodeWavToAac(wavFile: File): EncodedAudio {
        val pcm = readPcmFromWav(wavFile)
        if (pcm.isEmpty()) return EncodedAudio(null, emptyList())

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            PlaybackAudioRecorder.SAMPLE_RATE,
            PlaybackAudioRecorder.CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = mutableListOf<EncodedSample>()
        var outputFormat: MediaFormat? = null

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        try {
            var inputOffset = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Missing input buffer")
                        inputBuffer.clear()
                        val size = min(inputBuffer.capacity(), pcm.size - inputOffset)
                        if (size <= 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                pcmDurationUs(inputOffset),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            inputBuffer.put(pcm, inputOffset, size)
                            codec.queueInputBuffer(inputIndex, 0, size, pcmDurationUs(inputOffset), 0)
                            inputOffset += size
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    else -> if (outputIndex >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex) ?: error("Missing output buffer")
                            val data = ByteArray(info.size)
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            outputBuffer.get(data)
                            samples += EncodedSample(data, MediaCodec.BufferInfo().apply {
                                set(0, data.size, info.presentationTimeUs, info.flags)
                            })
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        return EncodedAudio(outputFormat, samples)
    }

    private fun readPcmFromWav(file: File): ByteArray {
        FileInputStream(file).use { input ->
            if (input.skip(WAV_HEADER_SIZE.toLong()) != WAV_HEADER_SIZE.toLong()) return ByteArray(0)
            return input.readBytes()
        }
    }

    private fun pcmDurationUs(byteCount: Int): Long {
        val bytesPerSecond = PlaybackAudioRecorder.SAMPLE_RATE *
            PlaybackAudioRecorder.CHANNEL_COUNT *
            PlaybackAudioRecorder.BYTES_PER_SAMPLE
        return byteCount * 1_000_000L / bytesPerSecond
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return index
        }
        return -1
    }

    private data class EncodedAudio(
        val format: MediaFormat?,
        val samples: List<EncodedSample>
    )

    private data class EncodedSample(
        val data: ByteArray,
        val info: MediaCodec.BufferInfo
    )

    companion object {
        private const val WAV_HEADER_SIZE = 44
    }
}
