# Native Camera Pro (Android Camera2 project)

Kotlin app (`com.rawcam.nativepro`) using `android.hardware.camera2` + `MediaRecorder`.

## Features
- **RAW ISP switch**: `EDGE_MODE_OFF` + `NOISE_REDUCTION_MODE_MINIMAL` (or `OFF`), applied only if the device reports support (safe on Samsung A-series).
- **Bitrate / FPS**: H.264 MP4 at 20 / 50 / 80 / 120 Mbps, 24 / 30 / 60 FPS (falls back to the closest range the device supports and tells you).
- **Manual controls**: ISO, shutter (1/24 – 1/2000), boosted ISO (`CONTROL_POST_RAW_SENSITIVITY_BOOST`), manual focus, white balance lock.
- **Pro audio**: `UNPROCESSED` mic (falls back to `CAMCORDER`/`MIC`), 48 kHz, 320 kbps stereo AAC.
- **Photos**: full-resolution JPEG (quality 100) with the same manual/ISP settings; photo button is disabled while recording.
- **Storage**: saves to `DCIM/NativeCamera/` via MediaStore (Android 10+; no storage permission needed).

## Get the APK (automatic)
1. Create a GitHub repo and upload everything in this folder (including the hidden `.github` folder).
2. That's it. GitHub builds automatically on every upload, in about 2-3 minutes.
3. Open the repo -> **Releases** (right side) -> latest build -> tap **NativeCameraPro.apk** to download and install.

Local build alternative: open in Android Studio -> *Build > Build APK(s)*; output is `app/build/outputs/apk/debug/app-debug.apk`.
