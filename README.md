# DanceCam

DanceCam is a native Android-only Kotlin app for dance self-recording. It records the camera preview to an MP4 file while capturing internal system playback audio through Android's AudioPlaybackCapture API. It does not record microphone audio.

This v0 targets Android 10+ only because playback capture requires Android Q / API 29.

## What It Does

- Shows a full-screen portrait CameraX preview.
- Starts with the back camera and includes a simple front/back switch.
- Requests camera, audio, and MediaProjection consent.
- Saves a camera video-only MP4.
- Saves captured internal playback audio as a WAV file.
- Shows status text: `Ready`, `Recording`, `Saved: <path>`, or `Error: <message>`.

## Build With GitHub Actions

1. Push this repository to GitHub.
2. Open the repository on GitHub.
3. Go to **Actions**.
4. Run the **Android** workflow, or push a commit to trigger it.
5. The workflow runs `./gradlew assembleDebug`.

No Android Studio, local Android SDK, USB, or adb is required.

## Download The APK Artifact

1. Open the completed workflow run.
2. Scroll to **Artifacts**.
3. Download `DanceCam-debug-apk`.
4. Unzip it.
5. The debug APK is `app-debug.apk`.

## Install Manually On Android

1. Transfer `app-debug.apk` to the phone using a browser download, cloud drive, email, or file transfer app.
2. Open the APK on the phone.
3. Allow install from that source if Android prompts you.
4. Install and open DanceCam.

## Expected Manual Test

1. Start YouTube Music or another music app.
2. Wear headphones.
3. Open DanceCam.
4. Grant camera and audio permissions.
5. Press **Record**.
6. Approve the Android playback-capture consent dialog.
7. Dance.
8. Press **Stop**.
9. Check the saved MP4 and WAV paths shown on screen.

The MP4 is camera video only. The WAV should contain capturable internal playback audio without external room noise.

## Known Limitations

- v0 does not mux the WAV into the MP4 yet.
- Some apps block playback capture, so their audio may be silent.
- Audio capture requires explicit Android MediaProjection consent.
- The debug APK is unsigned for release and intended for manual testing only.
- Files are saved under app-specific external storage, so uninstalling the app can remove them.

## Android 10+ Behavior

On Android 10 and newer, apps that allow playback capture can be captured through MediaProjection and AudioRecord. DanceCam requests `RECORD_AUDIO` because AudioRecord requires it, but it builds the recorder with `AudioPlaybackCaptureConfiguration`, not microphone input.
