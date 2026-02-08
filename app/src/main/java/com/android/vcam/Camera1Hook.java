package com.android.vcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.os.Environment;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed hooks for legacy Camera API (android.hardware.Camera).
 * Replaces preview with virtual.mp4 and still capture with 1000.bmp.
 */
public class Camera1Hook {

    private static final String SUBDIR_CAMERA1 = "DCIM/Camera1/";
    private static final String VIDEO_FILE = "virtual.mp4";
    private static final String FILE_NO_SILENT = "no-silent.jpg";

    public void hook(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        hookSetPreviewTexture(host, lpparam);
        hookPreviewCallbacks(host, lpparam);
        hookAddCallbackBuffer(lpparam);
        hookTakePicture(host, lpparam);
        hookMediaRecorder(host, lpparam);
        hookStartPreview(host, lpparam);
        hookSetPreviewDisplay(host, lpparam);
    }

    private void hookSetPreviewTexture(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!host.hasVirtualVideo()) {
                            host.updateShouldShowToast();
                            host.showNoVideoToast(lpparam.packageName);
                            return;
                        }
                        if (host.isDisabled()) return;
                        if (HookMain.is_hooked) {
                            HookMain.is_hooked = false;
                            return;
                        }
                        if (param.args[0] == null) return;
                        if (param.args[0].equals(HookMain.c1_fake_texture)) return;
                        if (HookMain.origin_preview_camera != null && HookMain.origin_preview_camera.equals(param.thisObject)) {
                            param.args[0] = HookMain.fake_SurfaceTexture;
                            Logger.i("duplicate preview camera: " + HookMain.origin_preview_camera);
                            return;
                        }
                        Logger.i("create preview");

                        HookMain.origin_preview_camera = (Camera) param.thisObject;
                        HookMain.mSurfacetexture = (SurfaceTexture) param.args[0];
                        if (HookMain.fake_SurfaceTexture == null) {
                            HookMain.fake_SurfaceTexture = new SurfaceTexture(10);
                        } else {
                            HookMain.fake_SurfaceTexture.release();
                            HookMain.fake_SurfaceTexture = new SurfaceTexture(10);
                        }
                        param.args[0] = HookMain.fake_SurfaceTexture;
                    }
                });
    }

    private void hookPreviewCallbacks(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null) processCallback(host, param);
                    }
                });
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewCallback", Camera.PreviewCallback.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null) processCallback(host, param);
                    }
                });
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setOneShotPreviewCallback", Camera.PreviewCallback.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null) processCallback(host, param);
                    }
                });
    }

    private void hookAddCallbackBuffer(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "addCallbackBuffer", byte[].class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null) {
                            param.args[0] = new byte[((byte[]) param.args[0]).length];
                        }
                    }
                });
    }

    private void hookTakePicture(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "takePicture", Camera.ShutterCallback.class, Camera.PictureCallback.class,
                Camera.PictureCallback.class, Camera.PictureCallback.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Logger.i("takePicture (4-arg)");
                        if (param.args[1] != null) processAShotYUV(host, param);
                        if (param.args[3] != null) processAShotJpeg(host, param, 3);
                    }
                });
    }

    private void hookMediaRecorder(HookMain host, XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.media.MediaRecorder", lpparam.classLoader,
                "setCamera", Camera.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        host.updateShouldShowToast();
                        Logger.i("record triggered: " + lpparam.packageName);
                        if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                            try {
                                Toast.makeText(HookMain.toast_content,
                                        "应用：" + lpparam.appInfo.name + "(" + lpparam.packageName + ") 触发了录像，但目前无法拦截",
                                        Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                Logger.i("toast: " + Arrays.toString(e.getStackTrace()));
                            }
                        }
                    }
                });
    }

    private void hookStartPreview(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "startPreview", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        host.updateShouldShowToast();
                        if (!host.hasVirtualVideo()) {
                            host.showNoVideoToast(lpparam.packageName);
                            return;
                        }
                        if (host.isDisabled()) return;
                        HookMain.is_someone_playing = false;
                        Logger.i("start preview");
                        HookMain.start_preview_camera = (Camera) param.thisObject;

                        if (HookMain.ori_holder != null) {
                            if (HookMain.mplayer1 == null) {
                                HookMain.mplayer1 = new MediaPlayer();
                            } else {
                                HookMain.mplayer1.release();
                                HookMain.mplayer1 = null;
                                HookMain.mplayer1 = new MediaPlayer();
                            }
                            if (!HookMain.ori_holder.getSurface().isValid()) return;
                            HookMain.mplayer1.setSurface(HookMain.ori_holder.getSurface());
                            boolean noSilent = new File(host.getDcimCamera1Path() + FILE_NO_SILENT).exists();
                            if (!noSilent || HookMain.is_someone_playing) {
                                HookMain.mplayer1.setVolume(0, 0);
                                HookMain.is_someone_playing = false;
                            } else {
                                HookMain.is_someone_playing = true;
                            }
                            HookMain.mplayer1.setLooping(true);
                            HookMain.mplayer1.setOnPreparedListener(mp -> HookMain.mplayer1.start());
                            try {
                                HookMain.mplayer1.setDataSource(HookMain.video_path + VIDEO_FILE);
                                HookMain.mplayer1.prepare();
                            } catch (IOException e) {
                                Logger.i(String.valueOf(e));
                            }
                        }

                        if (HookMain.mSurfacetexture != null) {
                            if (HookMain.mSurface == null) {
                                HookMain.mSurface = new Surface(HookMain.mSurfacetexture);
                            } else {
                                HookMain.mSurface.release();
                                HookMain.mSurface = new Surface(HookMain.mSurfacetexture);
                            }
                            if (HookMain.mMediaPlayer == null) {
                                HookMain.mMediaPlayer = new MediaPlayer();
                            } else {
                                HookMain.mMediaPlayer.release();
                                HookMain.mMediaPlayer = new MediaPlayer();
                            }
                            HookMain.mMediaPlayer.setSurface(HookMain.mSurface);
                            boolean noSilent = new File(host.getDcimCamera1Path() + FILE_NO_SILENT).exists();
                            if (!noSilent || HookMain.is_someone_playing) {
                                HookMain.mMediaPlayer.setVolume(0, 0);
                                HookMain.is_someone_playing = false;
                            } else {
                                HookMain.is_someone_playing = true;
                            }
                            HookMain.mMediaPlayer.setLooping(true);
                            HookMain.mMediaPlayer.setOnPreparedListener(mp -> HookMain.mMediaPlayer.start());
                            try {
                                HookMain.mMediaPlayer.setDataSource(HookMain.video_path + VIDEO_FILE);
                                HookMain.mMediaPlayer.prepare();
                            } catch (IOException e) {
                                Logger.i(String.valueOf(e));
                            }
                        }
                    }
                });
    }

    private void hookSetPreviewDisplay(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewDisplay", SurfaceHolder.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Logger.i("add SurfaceView preview");
                        host.updateShouldShowToast();
                        if (!host.hasVirtualVideo()) {
                            host.showNoVideoToast(lpparam.packageName);
                            return;
                        }
                        if (host.isDisabled()) return;
                        HookMain.mcamera1 = (Camera) param.thisObject;
                        HookMain.ori_holder = (SurfaceHolder) param.args[0];
                        if (HookMain.c1_fake_texture == null) {
                            HookMain.c1_fake_texture = new SurfaceTexture(11);
                        } else {
                            HookMain.c1_fake_texture.release();
                            HookMain.c1_fake_texture = null;
                            HookMain.c1_fake_texture = new SurfaceTexture(11);
                        }
                        if (HookMain.c1_fake_surface == null) {
                            HookMain.c1_fake_surface = new Surface(HookMain.c1_fake_texture);
                        } else {
                            HookMain.c1_fake_surface.release();
                            HookMain.c1_fake_surface = null;
                            HookMain.c1_fake_surface = new Surface(HookMain.c1_fake_texture);
                        }
                        HookMain.is_hooked = true;
                        try {
                            HookMain.mcamera1.setPreviewTexture(HookMain.c1_fake_texture);
                        } catch (IOException e) {
                            Logger.i(String.valueOf(e));
                        }
                        param.setResult(null);
                    }
                });
    }

    private void processCallback(HookMain host, XC_MethodHook.MethodHookParam param) {
        Class<?> previewCbClass = param.args[0].getClass();
        int needStop = (host.isDisabled() || !host.hasVirtualVideo()) ? 1 : 0;
        if (needStop == 0) {
            host.updateShouldShowToast();
            if (!host.hasVirtualVideo()) {
                host.showNoVideoToast(HookMain.toast_content != null ? HookMain.toast_content.getPackageName() : "");
                needStop = 1;
            }
        }
        final int finalNeedStop = needStop;
        XposedHelpers.findAndHookMethod(previewCbClass, "onPreviewFrame", byte[].class, Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                Camera localCam = (Camera) paramd.args[1];
                if (localCam.equals(HookMain.camera_onPreviewFrame)) {
                    while (HookMain.data_buffer == null) { }
                    System.arraycopy(HookMain.data_buffer, 0, paramd.args[0], 0,
                            Math.min(HookMain.data_buffer.length, ((byte[]) paramd.args[0]).length));
                } else {
                    HookMain.camera_callback_calss = previewCbClass;
                    HookMain.camera_onPreviewFrame = (Camera) paramd.args[1];
                    HookMain.mwidth = HookMain.camera_onPreviewFrame.getParameters().getPreviewSize().width;
                    HookMain.mhight = HookMain.camera_onPreviewFrame.getParameters().getPreviewSize().height;
                    int frameRate = HookMain.camera_onPreviewFrame.getParameters().getPreviewFrameRate();
                    Logger.i("preview callback init: width=" + HookMain.mwidth + " height=" + HookMain.mhight + " frameRate=" + frameRate);
                    host.updateShouldShowToast();
                    if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                        try {
                            Toast.makeText(HookMain.toast_content,
                                    "发现预览\n宽：" + HookMain.mwidth + "\n高：" + HookMain.mhight + "\n需要视频分辨率与其完全相同",
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            Logger.i("toast: " + ee);
                        }
                    }
                    if (finalNeedStop == 1) return;
                    if (HookMain.hw_decode_obj != null) HookMain.hw_decode_obj.stopDecode();
                    HookMain.hw_decode_obj = new VideoToFrames();
                    HookMain.hw_decode_obj.setSaveFrames("", OutputImageFormat.NV21);
                    HookMain.hw_decode_obj.decode(HookMain.video_path + VIDEO_FILE);
                    while (HookMain.data_buffer == null) { }
                    System.arraycopy(HookMain.data_buffer, 0, paramd.args[0], 0,
                            Math.min(HookMain.data_buffer.length, ((byte[]) paramd.args[0]).length));
                }
            }
        });
    }

    private void processAShotJpeg(HookMain host, XC_MethodHook.MethodHookParam param, int index) {
        try {
            Logger.i("JPEG callback: " + param.args[index]);
        } catch (Exception e) {
            Logger.i(String.valueOf(e));
        }
        Class<?> callback = param.args[index].getClass();
        XposedHelpers.findAndHookMethod(callback, "onPictureTaken", byte[].class, Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                try {
                    Camera cam = (Camera) paramd.args[1];
                    HookMain.onemwidth = cam.getParameters().getPreviewSize().width;
                    HookMain.onemhight = cam.getParameters().getPreviewSize().height;
                    Logger.i("JPEG picture callback init: width=" + HookMain.onemwidth + " height=" + HookMain.onemhight + " camera=" + cam);
                    host.updateShouldShowToast();
                    if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                        try {
                            Toast.makeText(HookMain.toast_content,
                                    "发现拍照\n宽：" + HookMain.onemwidth + "\n高：" + HookMain.onemhight + "\n格式：JPEG",
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Logger.i("toast: " + e);
                        }
                    }
                    if (host.isDisabled()) return;
                    Bitmap pict = getBMP(HookMain.video_path + "1000.bmp");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    pict.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                    paramd.args[0] = baos.toByteArray();
                } catch (Exception ee) {
                    Logger.i(String.valueOf(ee));
                }
            }
        });
    }

    private void processAShotYUV(HookMain host, XC_MethodHook.MethodHookParam param) {
        try {
            Logger.i("YUV picture callback: " + param.args[1]);
        } catch (Exception e) {
            Logger.i(String.valueOf(e));
        }
        Class<?> callback = param.args[1].getClass();
        XposedHelpers.findAndHookMethod(callback, "onPictureTaken", byte[].class, Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                try {
                    Camera cam = (Camera) paramd.args[1];
                    HookMain.onemwidth = cam.getParameters().getPreviewSize().width;
                    HookMain.onemhight = cam.getParameters().getPreviewSize().height;
                    Logger.i("YUV picture callback init: width=" + HookMain.onemwidth + " height=" + HookMain.onemhight + " camera=" + cam);
                    host.updateShouldShowToast();
                    if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                        try {
                            Toast.makeText(HookMain.toast_content,
                                    "发现拍照\n宽：" + HookMain.onemwidth + "\n高：" + HookMain.onemhight + "\n格式：YUV_420_888",
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Logger.i("toast: " + e);
                        }
                    }
                    if (host.isDisabled()) return;
                    HookMain.input = getYUVByBitmap(getBMP(HookMain.video_path + "1000.bmp"));
                    paramd.args[0] = HookMain.input;
                } catch (Exception ee) {
                    Logger.i(String.valueOf(ee));
                }
            }
        });
    }

    private static Bitmap getBMP(String path) throws Throwable {
        return BitmapFactory.decodeFile(path);
    }

    private static byte[] rgb2YCbCr420(int[] pixels, int width, int height) {
        int len = width * height;
        byte[] yuv = new byte[len * 3 / 2];
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int rgb = (pixels[i * width + j]) & 0x00FFFFFF;
                int r = rgb & 0xFF, g = (rgb >> 8) & 0xFF, b = (rgb >> 16) & 0xFF;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                y = Math.max(16, Math.min(255, y));
                u = Math.max(0, Math.min(255, u));
                v = Math.max(0, Math.min(255, v));
                yuv[i * width + j] = (byte) y;
                yuv[len + (i >> 1) * width + (j & ~1)] = (byte) u;
                yuv[len + (i >> 1) * width + (j & ~1) + 1] = (byte) v;
            }
        }
        return yuv;
    }

    private static byte[] getYUVByBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        return rgb2YCbCr420(pixels, w, h);
    }
}
