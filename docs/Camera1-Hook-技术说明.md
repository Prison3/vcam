# Camera1 Hook 技术说明

本文介绍 VCAM 模块对 **旧版 Camera API**（`android.hardware.Camera`）的 Hook 原理与实现要点。

---

## 1. 目标与范围

- **目标**：让使用旧版 Camera 的应用看到“虚拟摄像头”——预览来自本地视频 `virtual.mp4`，拍照结果来自静态图 `1000.bmp`。
- **范围**：仅 Hook 目标进程内的 `android.hardware.Camera` 及 `MediaRecorder.setCamera`，不修改系统框架。

---

## 2. Hook 点与调用顺序

应用使用 Camera1 的典型流程：

1. `Camera.open()` 获取 Camera 实例  
2. 设置预览目标：`setPreviewTexture(SurfaceTexture)` 或 `setPreviewDisplay(SurfaceHolder)`  
3. 可选：`setPreviewCallback` / `setPreviewCallbackWithBuffer` / `setOneShotPreviewCallback` 获取预览帧  
4. `startPreview()` 开始预览  
5. 可选：`takePicture(...)` 拍照  

模块在以下位置插入 Hook，并在此顺序下配合工作。

---

## 3. 各 Hook 点说明

### 3.1 setPreviewTexture(SurfaceTexture)

- **作用**：应用把“真实预览”的 `SurfaceTexture` 传给相机。我们把它换成一块**假 SurfaceTexture**，让相机往假纹理上画（我们不真正用这块数据），而真正给用户看的画面由我们后面用 `virtual.mp4` 提供。
- **逻辑要点**：
  - 若未放置 `virtual.mp4` 或已禁用模块，直接 return，不替换。
  - 保存应用传入的**真实** `SurfaceTexture`（`mSurfacetexture`）和当前 Camera 实例（`origin_preview_camera`）。
  - 创建或重建**假** `SurfaceTexture`（`fake_SurfaceTexture`），并把 `param.args[0]` 替换为该假纹理，这样相机后续预览输出到假纹理，不会把真实画面送到应用。
  - 若是同一 Camera 重复设置，则继续使用已有假纹理，避免重复创建。

---

### 3.2 setPreviewDisplay(SurfaceHolder)

- **作用**：应用通过 SurfaceView 等传入 `SurfaceHolder` 作为预览目标。我们改为不把真实 Holder 交给相机，而是自己建一块假 `SurfaceTexture`，让 Camera 绑定到假纹理；预览画面再由我们在 `startPreview` 里用 MediaPlayer 播到应用的真实 Surface 上。
- **逻辑要点**：
  - 保存当前 Camera（`mcamera1`）和应用的 `SurfaceHolder`（`ori_holder`）。
  - 创建假 `SurfaceTexture`（`c1_fake_texture`）和对应 `Surface`（`c1_fake_surface`）。
  - 调用 `mcamera1.setPreviewTexture(c1_fake_texture)`，让相机输出到假纹理；**不**调用原来的 `setPreviewDisplay`（`param.setResult(null)`），这样应用认为设置完成，但实际并未把真实 Holder 交给相机。
  - 标记 `is_hooked = true`，供 `setPreviewTexture` 等处判断。

---

### 3.3 startPreview()

- **作用**：应用认为“开始预览”时，我们并不启动真实相机预览，而是用 **MediaPlayer** 把 `virtual.mp4` 播到应用看到的画面上。
- **逻辑要点**：
  - 若没有 `virtual.mp4` 或模块被禁用，直接 return。
  - **SurfaceView 路径**：若之前通过 `setPreviewDisplay` 拿到了 `ori_holder`，则用 `mplayer1` 把 `virtual.mp4` 播到 `ori_holder.getSurface()`，这样应用界面上的 SurfaceView 显示的是视频内容。
  - **TextureView / 仅 setPreviewTexture 路径**：若之前通过 `setPreviewTexture` 拿到了 `mSurfacetexture`，则用 `mSurface = new Surface(mSurfacetexture)` 和 `mMediaPlayer` 把 `virtual.mp4` 播到该 Surface，应用看到的纹理内容就是视频。
  - 静音由 `no-silent.jpg` 控制：不存在则静音，存在则可带声音。

---

### 3.4 预览回调（setPreviewCallback / setPreviewCallbackWithBuffer / setOneShotPreviewCallback）

- **作用**：应用通过回调拿“预览帧”（如 NV21 数据）。我们改为不把真实相机帧给应用，而是用 **VideoToFrames** 解码 `virtual.mp4` 得到 NV21，在 `onPreviewFrame` 里把解码结果拷贝进应用传入的 `byte[]`。
- **逻辑要点**：
  - 在 `setPreviewCallback*` 被调用时，不直接放行，而是对传入的 **回调类** 做一次 `findAndHookMethod(..., "onPreviewFrame", byte[].class, Camera.class, ...)`。
  - 在 Hook 到的 `onPreviewFrame` 里：
    - 若已是“我们正在喂数据的那个 Camera”（`camera_onPreviewFrame`），则从共享的 `HookMain.data_buffer`（由 VideoToFrames 写入）拷贝到 `paramd.args[0]`。
    - 若是首次进入，则根据当前 Camera 的预览宽高和帧率启动 **VideoToFrames**，解码 `virtual.mp4`，输出 NV21 到 `data_buffer`，再拷贝到 `paramd.args[0]`。
  - 这样应用拿到的每一帧都是视频帧，而不是真实摄像头。

---

### 3.5 addCallbackBuffer(byte[])

- **作用**：带 Buffer 的预览回调会复用应用提供的 byte 数组。我们把应用传进来的 buffer 替换成**同长度的空数组**，避免真实相机往应用 buffer 里写真实帧数据；实际给应用的数据在 `onPreviewFrame` 的 Hook 里从 `data_buffer` 拷贝进去。
- **逻辑**：`param.args[0] = new byte[((byte[]) param.args[0]).length];`

---

### 3.6 takePicture(...)

- **作用**：应用拍照时，我们不返回真实传感器画面，而是用 **1000.bmp** 生成 JPEG 或 YUV 数据，在回调里替换掉原始数据。
- **逻辑要点**：
  - Hook `Camera.takePicture(ShutterCallback, raw, postview, jpeg)`，在 **afterHookedMethod** 里根据 `param.args[1]`（YUV 回调）和 `param.args[3]`（JPEG 回调）是否非空，分别调用 `processAShotYUV` 和 `processAShotJpeg`。
  - **processAShotJpeg**：对回调类做 `findAndHookMethod(..., "onPictureTaken", byte[].class, Camera.class, ...)`，在 Hook 里用 `BitmapFactory.decodeFile("1000.bmp")` 得到 Bitmap，再 `compress(JPEG)` 成 byte 数组，赋给 `paramd.args[0]`。
  - **processAShotYUV**：同样 Hook 该回调的 `onPictureTaken`，用 `1000.bmp` 转 Bitmap 再通过 **rgb2YCbCr420** 转成 NV21 的 byte 数组，赋给 `paramd.args[0]`。
  - 这样应用拿到的“拍照结果”始终是 1000.bmp 的内容。

---

### 3.7 MediaRecorder.setCamera(Camera)

- **作用**：应用要录像时会把 Camera 绑到 MediaRecorder。当前**不拦截录像**，只打日志并可选 Toast 提示“触发了录像，但目前无法拦截”，避免误以为已支持录像替换。
- **逻辑**：Hook `setCamera`，在 before 里记录包名并视配置弹出 Toast，不修改参数。

---

## 4. 依赖与共享状态

- **HookMain** 提供：`video_path`、`toast_content`、`hasVirtualVideo()`、`isDisabled()`、`getDcimCamera1Path()` 等，以及供 Camera1 使用的静态变量（如 `data_buffer`、`mSurface`、`mMediaPlayer`、`hw_decode_obj`、`c1_fake_texture`、`ori_holder`、`mplayer1` 等）。
- **VideoToFrames**：解码 `virtual.mp4`，按 NV21 输出到 `HookMain.data_buffer` 或指定 Surface，供预览回调或后续扩展使用。
- **资源文件**：`virtual.mp4`（预览）、`1000.bmp`（拍照）、`no-silent.jpg`（是否静音）等，路径由 HookMain 的 `video_path` 与配置决定。

---

## 5. 小结

| 环节       | 手段                         | 效果                     |
|------------|------------------------------|--------------------------|
| 预览显示   | 假 SurfaceTexture / 拦截 setPreviewDisplay + MediaPlayer 播 virtual.mp4 | 界面显示视频而非真实摄像头 |
| 预览帧回调 | Hook onPreviewFrame + VideoToFrames 解码 virtual.mp4 → data_buffer 拷贝 | 回调拿到的是视频帧       |
| 拍照       | Hook onPictureTaken + 1000.bmp 转 JPEG/YUV 填入回调参数 | 拍照结果固定为 1000.bmp 画面 |
| 录像       | 仅提示，不替换               | 录像仍为真实摄像头       |

整体上，Camera1 Hook 通过“替换预览目标 + 自己喂视频/图片数据”的方式，在不改系统的前提下，让旧版 Camera 应用使用虚拟摄像头。
