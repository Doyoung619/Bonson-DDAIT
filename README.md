# DanceCam

DanceCam is a native Android-only Kotlin app for dance self-recording. It records the camera preview to an MP4 file while capturing internal system playback audio through Android's AudioPlaybackCapture API. It does not record microphone audio.

This v0 targets Android 10+ only because playback capture requires Android Q / API 29.

## What It Does

- Shows a full-screen portrait CameraX preview.
- Starts with the back camera and includes a simple front/back switch.
- Supports portrait/landscape capture mode from an in-app button.
- Requests camera, audio, and MediaProjection consent.
- Saves a merged MP4 to `Movies/DanceCam`.
- Shows simple Korean status text such as ready, recording, saving, and saved.

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
9. Check the saved MP4 URI shown on screen.
10. Open Gallery or Files and look under `Movies/DanceCam`.

The MP4 should contain camera video plus capturable internal playback audio without external room noise.

## Known Limitations

- v0 muxes temporary video and WAV audio into one MP4 after pressing Stop.
- Keep first tests short because v0 still does simple local post-processing after recording.
- If Record does not start correctly while music is already playing, press Record first, approve Android capture, then resume music from the notification shade or music app. This is shown in the app because Android's MediaProjection consent flow can interrupt already-playing media.
- Some apps block playback capture, so their audio may be silent.
- Audio capture requires explicit Android MediaProjection consent.
- The debug APK is unsigned for release and intended for manual testing only.
- Files are saved under app-specific external storage, so uninstalling the app can remove them.

## Android 10+ Behavior

On Android 10 and newer, apps that allow playback capture can be captured through MediaProjection and AudioRecord. DanceCam requests `RECORD_AUDIO` because AudioRecord requires it, but it builds the recorder with `AudioPlaybackCaptureConfiguration`, not microphone input.
