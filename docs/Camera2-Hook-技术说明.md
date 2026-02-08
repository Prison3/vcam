# Camera2 Hook 技术说明

本文介绍 VCAM 模块对 **Camera2 API**（`android.hardware.camera2`）的 Hook 原理与实现要点。

---

## 1. 目标与范围

- **目标**：让使用 Camera2 的应用认为“相机已打开、会话已建立”，但实际预览与采集的数据来自本地视频 `virtual.mp4`，而不是真实物理摄像头。
- **范围**：Hook 目标进程内的 `CameraManager`、`CaptureRequest.Builder`、`ImageReader`、`CameraDevice.StateCallback` 及 `CameraCaptureSession` 相关 API，不修改系统框架。

---

## 2. Camera2 流程简述

应用侧典型流程：

1. `CameraManager.openCamera(id, StateCallback, Handler)` 打开相机  
2. 在 `StateCallback.onOpened(CameraDevice)` 里用 `CameraDevice.createCaptureSession(surfaces, sessionCallback, handler)` 创建会话  
3. 用 `CaptureRequest.Builder.addTarget(Surface)` 把预览 Surface 或 ImageReader 的 Surface 加入请求  
4. `builder.build()` 得到 `CaptureRequest`，再 `session.setRepeatingRequest(request, ...)` 开始预览或采集  

我们要做的是：**在应用和真实 CameraDevice 之间插入一层“虚拟 Surface”**，让会话建在我们提供的虚拟 Surface 上，再把 `virtual.mp4` 的内容喂给应用原本期望的预览/采集 Surface。

---

## 3. Hook 点与数据流

### 3.1 CameraManager.openCamera

- **作用**：应用打开相机时，我们**不阻止** `openCamera` 执行（相机仍会打开），但需要拿到应用的 `StateCallback`，以便在 `onOpened` 里把后续的 `createCaptureSession` 的 Surface 列表替换成我们的虚拟 Surface。
- **逻辑要点**：
  - Hook 两个重载：`openCamera(String, StateCallback, Handler)` 与（API 28+）`openCamera(String, Executor, StateCallback)`。
  - 在 **before**（或 **after**，视重载）里取到 `StateCallback`（即 `param.args[1]` 或 `param.args[2]`），保存其 Class（`c2_state_callback`），并调用 **processCamera2Init(host, c2_state_callback)**。
  - 在 processCamera2Init 里对该 StateCallback 的 **onOpened** 做 `findAndHookMethod`，在 onOpened 里创建虚拟 Surface，并 Hook 该 CameraDevice 的各类 **createCaptureSession**，把传入的 Surface 列表替换成只含虚拟 Surface 的列表。

这样，应用认为“用我传的 Surface 建了会话”，实际系统会话建在“虚拟 Surface”上；真实相机输出到虚拟 Surface，我们再从 virtual.mp4 解码/播放到应用原本的 Surface 上。

---

### 3.2 虚拟 Surface 的创建与复用

- **createVirtualSurface(host)**  
  - 使用 `SurfaceTexture(15)` 创建一块离屏纹理，再 `new Surface(surfaceTexture)` 得到 **c2_virtual_surface**。  
  - 当 `need_recreate == true` 时先 release 旧 Surface 和 SurfaceTexture，再新建，避免重复创建导致泄漏或黑屏。  
  - 若 `c2_virtual_surface == null` 且当前未标记 need_recreate，则递归一次并设 need_recreate，保证首次一定走“创建”分支。  

- **何时创建/重建**  
  - 在 StateCallback **onOpened** 里先设 `need_recreate = true`，再调用 `createVirtualSurface(host)`，保证每次打开相机都用新的虚拟 Surface。

---

### 3.3 createCaptureSession 系列

应用会通过多种 API 创建会话，我们需覆盖所有常见重载：

| 方法 | 说明 | 替换方式 |
|------|------|----------|
| createCaptureSession(List\<Surface\>, StateCallback, Handler) | 最常用 | 将 `param.args[0]` 改为 `Arrays.asList(c2_virtual_surface)` |
| createCaptureSessionByOutputConfigurations (API 24+) | 使用 OutputConfiguration 列表 | 用只含 `new OutputConfiguration(c2_virtual_surface)` 的 List 替换原列表 |
| createConstrainedHighSpeedCaptureSession | 高帧率会话 | 同上，List 只含虚拟 Surface |
| createReprocessableCaptureSession (API 23+) | 可重处理会话 | 替换 Surface 列表参数为虚拟 Surface 列表 |
| createReprocessableCaptureSessionByConfigurations (API 24+) | 同上，OutputConfiguration 形式 | 用虚拟 Surface 的 OutputConfiguration 替换 |
| createCaptureSession(SessionConfiguration) (API 28+) | 通过 SessionConfiguration 创建 | 构造新的 SessionConfiguration，outputs 仅含虚拟 Surface 的 OutputConfiguration，其余沿用原配置，再替换 `param.args[0]` |

以上均在 **processCamera2Init** 里对 **param.args[0].getClass()**（即 CameraDevice 的运行时类）做 `findAndHookMethod`，在 **beforeHookedMethod** 里替换 Surface 列表或 SessionConfiguration，使会话建在虚拟 Surface 上。

---

### 3.4 CaptureRequest.Builder.addTarget(Surface)

- **作用**：应用把“预览 Surface”或“ImageReader 的 Surface”加入 CaptureRequest。我们把这些 Surface **替换成虚拟 Surface**，这样重复请求会发往虚拟 Surface，而不是应用自己的 Surface；同时我们**记录**应用原本的 Surface，用于后面把 virtual.mp4 的内容写进去。
- **逻辑要点**：
  - 若传入的已是 `c2_virtual_surface`，直接 return，避免重复记录。
  - 根据 `Surface.toString()` 是否包含 `"Surface(name=null)"` 区分：
    - **Reader 类**（通常是 ImageReader 的 Surface）：记到 `c2_reader_Surfcae` 或 `c2_reader_Surfcae_1`。
    - **预览类**：记到 `c2_preview_Surfcae` 或 `c2_preview_Surfcae_1`。
  - 将 `param.args[0]` 改为 `c2_virtual_surface`，这样 builder 后续 build 出的请求目标是虚拟 Surface。

---

### 3.5 CaptureRequest.Builder.removeTarget(Surface)

- **作用**：应用移除某个 Surface 时，我们同步从本地记录的 reader/preview 引用里移除，避免已关闭的 Surface 仍被用来播放或解码。
- **逻辑**：根据 `param.args[0]` 与 `c2_reader_Surfcae`、`c2_reader_Surfcae_1`、`c2_preview_Surfcae`、`c2_preview_Surfcae_1` 比较，相等则置为 null。

---

### 3.6 CaptureRequest.Builder.build()

- **作用**：应用调用 `build()` 表示“用当前 addTarget 的 Surface 组成一个请求”。我们在这里统一启动“往应用 Surface 上送 virtual.mp4 数据”的逻辑。
- **逻辑要点**：
  - 若当前 builder 已是我们上次处理过的（`param.thisObject.equals(c2_builder)`），直接 return，避免重复启动。
  - 否则保存 `c2_builder`，并调用 **processCamera2Play(host)**：
    - **Reader Surface**（c2_reader_Surfcae / c2_reader_Surfcae_1）：用 **VideoToFrames** 解码 `virtual.mp4`，根据 `imageReaderFormat == 256` 选 JPEG 或 NV21，通过 `set_surfcae(readerSurface)` 把解码结果渲染到该 Surface，应用侧的 ImageReader 就会拿到我们喂的帧。
    - **Preview Surface**（c2_preview_Surfcae / c2_preview_Surfcae_1）：用 **MediaPlayer** 把 `virtual.mp4` 播到该 Surface，应用看到的预览就是视频。
  - 静音同样由 `no-silent.jpg` 控制。

---

### 3.7 ImageReader.newInstance(width, height, format, maxImages)

- **作用**：应用创建 ImageReader 时，我们记录宽高和 format（`c2_ori_width`、`c2_ori_height`、`imageReaderFormat`），供 processCamera2Play 里选择 JPEG 还是 NV21 以及后续扩展；可选 Toast 提示“应用创建了渲染器”。
- **逻辑**：beforeHookedMethod 里读取 `param.args[0..2]` 写入 HookMain 的静态变量，不修改参数，不阻止创建。

---

### 3.8 CaptureCallback.onCaptureFailed

- **作用**：仅打日志（如失败原因），便于排查；不改变应用行为。

---

## 4. 依赖与共享状态

- **HookMain** 提供：`video_path`、`toast_content`、`hasVirtualVideo()`、`isDisabled()`、`getDcimCamera1Path()`，以及 Camera2 用的静态变量（如 `c2_virtual_surface`、`c2_reader_Surfcae`、`c2_preview_Surfcae`、`c2_player`、`c2_hw_decode_obj`、`imageReaderFormat`、`need_recreate` 等）。
- **VideoToFrames**：解码 `virtual.mp4` 并输出到指定 Surface（NV21/JPEG），供 ImageReader 路径使用。
- **MediaPlayer**：把 `virtual.mp4` 播到预览 Surface，供预览路径使用。

---

## 5. 小结

| 环节 | 手段 | 效果 |
|------|------|------|
| 打开相机 | Hook openCamera，保存 StateCallback，在 onOpened 里替换 createCaptureSession 的 Surface 列表 | 会话建在虚拟 Surface 上 |
| 请求目标 | Hook addTarget，把应用 Surface 换成虚拟 Surface，并记录原 Surface | 请求发往虚拟 Surface；我们持有应用 Surface 引用 |
| 开始请求 | Hook build()，调用 processCamera2Play | 对 Reader Surface 用 VideoToFrames 喂帧，对 Preview Surface 用 MediaPlayer 播视频 |
| 采集格式 | Hook ImageReader.newInstance 记录 format | 正确选择 JPEG/NV21 解码输出 |

整体上，Camera2 Hook 通过“虚拟 Surface + 替换会话输出 + 主动向应用 Surface 喂 virtual.mp4”的方式，在不改系统 CameraService 的前提下，让 Camera2 应用使用虚拟摄像头。
