# VCAM – Android Virtual Camera (Xposed)

An Xposed module that replaces the device camera feed with a local video file. Target apps see a virtual camera stream (preview and still capture) instead of the real camera.

**Warning: For testing and legitimate use only. You are responsible for your own use.**

---

## Features

- **Preview replacement**: Replaces live camera preview with a looping video (`virtual.mp4`).
- **Still capture**: Replaces taken photos with a static image (`1000.bmp`) when using the legacy Camera API.
- **Dual API support**: Works with both **Camera (legacy)** and **Camera2**.
- **Per-app control**: Enable/disable and configure via files in `DCIM/Camera1/` or app-private directory.
- **Optional audio**: Can play or mute the replacement video’s sound.

---

## Requirements

- Rooted device with **Xposed Framework** (LSPosed / EdXposed / similar), **min Xposed API 51**.
- **Android 5.0 (API 21)** or higher (minSdk 21; target 26).
- **Storage permission** for the VCAM app (to read/write config and media under `DCIM/Camera1/` or private dir).

---

## Installation & Setup

1. Install the VCAM APK and grant storage permission.
2. In Xposed Manager, enable the **VCAM** module and reboot.
3. Put your replacement media in the correct folder (see below).
4. Optionally use the VCAM app switches or control files to disable toasts, mute sound, force private dir, etc.

### Video and image paths

- **Default path**: ` /storage/emulated/0/DCIM/Camera1/`
- **If “Force every app use private directory” is on**, or the target app has no storage permission:  
  each target app uses its own private dir, e.g.  
  `Android/data/<target.package>/files/Camera1/`

**Required files:**

| File           | Purpose |
|----------------|--------|
| `virtual.mp4` | Video shown as camera preview (loop). Resolution/aspect should match app’s preview when possible. |
| `1000.bmp`    | Image used for **still capture** (legacy Camera API only). Used to generate JPEG and YUV data. |

**Control files (all optional):**

| File             | Effect when present |
|------------------|----------------------|
| `disable.jpg`    | Disable the module (no replacement). |
| `no_toast.jpg`   | Suppress toast messages from the module. |
| `no-silent.jpg`  | Play video sound; if absent, video is muted. |
| `force_show.jpg` | Force showing “permission / path” toasts again. |
| `private_dir.jpg`| Force using app-private directory for video/image. |

The VCAM app UI toggles create/remove these `.jpg` flag files under `DCIM/Camera1/`.

---

## How It Works (Technical Summary)

The module hooks into the Android camera stack so that:

1. **Preview** uses your video (or a virtual surface fed by it) instead of the real camera.
2. **Capture** (legacy API) returns frames derived from `1000.bmp` instead of the real sensor.

Behavior differs slightly for Camera vs Camera2.

### Legacy Camera API

- **`Camera.setPreviewTexture`**  
  Replaces the app’s `SurfaceTexture` with a fake one. The real preview is not used; the module feeds preview from `virtual.mp4` (via `MediaPlayer` or decoded frames).
- **`Camera.setPreviewDisplay`**  
  Intercepted so the app binds to a fake surface; again, video is played onto the surface the app thinks is the camera.
- **`Camera.startPreview`**  
  Starts playback of `virtual.mp4` (one or more `MediaPlayer` instances) onto the appropriate surface(s).
- **Preview callbacks** (`setPreviewCallback`, `setPreviewCallbackWithBuffer`, `setOneShotPreviewCallback`)  
  Hooked so `onPreviewFrame` receives NV21 data from a **VideoToFrames** decoder that decodes `virtual.mp4` in a loop, instead of real camera frames.
- **`addCallbackBuffer`**  
  Replaced with empty buffers so the pipeline does not use real camera buffers.
- **`takePicture`**  
  JPEG and YUV callbacks are hooked; the module supplies JPEG from `1000.bmp` (compressed to JPEG) and YUV from the same bitmap (RGB→YUV conversion) instead of real capture data.

### Camera2 API

- **`CameraManager.openCamera`**  
  When the app opens a camera, the module does not block it but later redirects capture session outputs to a virtual surface.
- **`CameraDevice.StateCallback.onOpened`**  
  The module creates a **virtual `Surface`** (backed by a `SurfaceTexture`) and hooks:
  - `createCaptureSession(List<Surface>, ...)`
  - `createCaptureSessionByOutputConfigurations` (API 24+)
  - `createConstrainedHighSpeedCaptureSession`
  - `createReprocessableCaptureSession` (API 23+)
  - `createReprocessableCaptureSessionByConfigurations` (API 24+)
  - `createCaptureSession(SessionConfiguration)` (API 28+)

  In each case, the **output list is replaced** with a list containing only the virtual surface, so the real camera does not feed the app’s surfaces.

- **`CaptureRequest.Builder.addTarget`**  
  Replaces the app’s target surface with the virtual surface; the module records which surfaces were “preview” vs “reader” for its own use.
- **`CaptureRequest.Builder.build`**  
  Triggers the module’s playback logic: it starts **VideoToFrames** decoders for reader surfaces (feeding NV21/JPEG into the pipeline) and **MediaPlayer** for preview surfaces, both playing `virtual.mp4`.

So for Camera2:

- **Preview**: App draws from the virtual surface; the module renders `virtual.mp4` onto it (via MediaPlayer or decoder).
- **Capture/ImageReader**: Frames come from the module’s video decoder output (or equivalent), not from the real camera.

### Video decoding (VideoToFrames)

- **MediaExtractor** + **MediaCodec** decode `virtual.mp4` in a loop.
- Output format is **YUV (NV21)** or JPEG depending on what the app’s `ImageReader` / pipeline expects (e.g. format 256 → JPEG).
- Decoded frames are either:
  - Rendered to a `Surface` (e.g. for preview), or
  - Written into a shared buffer (`HookMain.data_buffer`) that is then copied into the app’s preview/capture callbacks or surfaces.

### Path and permission logic

- On **`Instrumentation.callApplicationOnCreate`** (each app process start), the module gets the app’s `Application` context and:
  - If the app has no storage permission (or “force private dir” is set), it sets `video_path` to that app’s **private directory** (`getExternalFilesDir(null)/Camera1/`).
  - Otherwise it uses **public** `DCIM/Camera1/`.
- Toasts (e.g. “no video”, “path”, “recording not supported”) are shown in the target app’s context when `need_to_show_toast` is true and `no_toast.jpg` is not present.

### Limitations

- **Recording (MediaRecorder)** is **not** intercepted. If the app starts recording, the module only shows a toast; the real camera is used for recording.
- Some Camera2 session types or high-speed/reprocess flows may not be fully covered on all devices.
- Preview/capture resolution and frame rate depend on the app; for best results, `virtual.mp4` resolution/aspect should match what the app requests.

---

## Project structure (key files)

| Path | Role |
|------|------|
| `app/src/main/java/.../HookMain.java` | Xposed entry; hooks Camera/Camera2 and drives preview/capture replacement. |
| `app/src/main/java/.../VideoToFrames.java` | Decodes `virtual.mp4` to frames (MediaCodec), outputs to Surface or byte buffer (NV21/JPEG). |
| `app/src/main/java/.../MainActivity.java` | UI for storage permission and toggles (disable, toasts, sound, private dir, force show). |
| `app/src/main/java/.../Logger.java` | Logging wrapper (e.g. `android.util.Log` with tag `VCAM`). |
| `app/src/main/assets/xposed_init` | Declares `com.android.vcam.HookMain` as the Xposed module entry class. |

---

## Build

- **JDK 17**, **Android Gradle Plugin**, **compileSdk 34**.
- Xposed API is **compileOnly** (`de.robv.android.xposed:api:82`); no need to ship it in the APK.

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.

---

## License

See [LICENSE](LICENSE) in the repository.

---

## Links

- [GitHub](https://github.com/w2016561536/android_virtual_cam)
- [Gitee (China)](https://gitee.com/w2016561536/android_virtual_cam)
