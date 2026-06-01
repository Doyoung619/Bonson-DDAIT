# Technical Notes

## AudioPlaybackCapture Limitations

DanceCam uses `AudioPlaybackCaptureConfiguration` with `MediaProjection` and `AudioRecord`. This is available only on Android 10 / API 29 and newer.

Captured audio is limited by Android platform policy. Audio can be captured only when the source app allows playback capture and the usage matches the configured usages, such as media or game audio.

## Why Some Apps May Block Capture

Android lets apps opt out of playback capture. DRM-protected content, streaming apps, calls, privacy-sensitive audio, or apps with restrictive capture policies may produce silence even when the user grants MediaProjection consent.

This is expected platform behavior, not a DanceCam microphone issue.

## Why Microphone Is Not Used

DanceCam intentionally does not call CameraX `.withAudioEnabled()` and does not create an `AudioRecord` from `MediaRecorder.AudioSource.MIC`.

The app requests `RECORD_AUDIO` because `AudioRecord` requires the permission, but the `AudioRecord` instance is built with `AudioPlaybackCaptureConfiguration`. That means it targets internal playback audio, not the device microphone.

## Current Muxing Status

v0 writes temporary files during recording:

- `dancecam_<timestamp>_video.mp4`: camera video-only MP4 from CameraX Recorder in app cache.
- `dancecam_<timestamp>_audio.wav`: internal playback PCM audio in app cache.

After recording stops, `VideoAudioMuxer` encodes WAV PCM to AAC with `MediaCodec`, copies the original video track with `MediaExtractor`, and writes one final MP4 with `MediaMuxer` to `Movies/DanceCam`.

TODO for a later version:

- Improve timestamp alignment between CameraX video start and AudioRecord start.
- Move muxing to a foreground worker for longer recordings.
- Add a clearer post-recording progress indicator.
- Optionally keep a debug WAV export switch.

The current muxing path is intentionally simple and designed for short manual tests first.

## Next Steps For AI Auto-Framing

AI auto-framing has been removed from the current stability-focused build. Later versions can add true post-recording reframing:

- Analyze the final recording or sampled frames after capture.
- Estimate dancer position and motion over time.
- Compute a smooth crop window for vertical output.
- Re-encode a separate AI-edited MP4 while preserving the muxed audio track.
- Keep any AI processing on-device unless the user explicitly opts into cloud processing.
