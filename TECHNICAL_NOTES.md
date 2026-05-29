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

v0 saves two files:

- `dancecam_<timestamp>.mp4`: camera video-only MP4 from CameraX Recorder.
- `dancecam_<timestamp>.wav`: internal playback PCM audio written as WAV.

TODO for a later version:

- Convert or encode WAV PCM to an MP4-compatible audio track, usually AAC.
- Mux the camera MP4 video track and encoded audio track into one MP4.
- Use Android `MediaExtractor`, `MediaCodec`, and `MediaMuxer`, or add a carefully licensed FFmpeg-based pipeline.
- Preserve timestamps so camera video and internal audio stay synchronized.

The fallback is deliberate so the current app remains buildable and testable without introducing fragile media muxing code in v0.

## Next Steps For AI Auto-Framing

Later versions can add on-device pose or person tracking to improve dance framing:

- Add an analysis pipeline using CameraX `ImageAnalysis`.
- Run a lightweight pose detector or person detector on preview frames.
- Estimate dancer position and motion over time.
- Provide framing guidance, zoom hints, or crop recommendations after recording.
- Keep any AI processing on-device unless the user explicitly opts into cloud processing.
