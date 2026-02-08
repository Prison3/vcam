package com.android.vcam;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed hooks for Camera2 API (android.hardware.camera2).
 * Redirects capture session to a virtual surface and feeds video frames.
 */
public class Camera2Hook {

    private static final String VIDEO_FILE = "virtual.mp4";
    private static final String FILE_NO_SILENT = "no-silent.jpg";

    public void hook(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        hookCameraManagerOpenCamera(host, lpparam);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hookCameraManagerOpenCameraWithExecutor(host, lpparam);
        }
        hookCaptureRequestBuilderAddTarget(host, lpparam);
        hookCaptureRequestBuilderRemoveTarget(host, lpparam);
        hookCaptureRequestBuilderBuild(host, lpparam);
        hookImageReaderNewInstance(host, lpparam);
        hookOnCaptureFailed(lpparam);
    }

    private void hookCameraManagerOpenCamera(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader,
                "openCamera", String.class, CameraDevice.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[1] == null) return;
                        if (param.args[1].equals(HookMain.c2_state_cb)) return;
                        HookMain.c2_state_cb = (CameraDevice.StateCallback) param.args[1];
                        HookMain.c2_state_callback = param.args[1].getClass();
                        if (host.isDisabled()) return;
                        host.updateShouldShowToast();
                        if (!host.hasVirtualVideo()) {
                            host.showNoVideoToast(lpparam.packageName);
                            return;
                        }
                        Logger.i("camera2 init (1-arg), callback class: " + HookMain.c2_state_callback);
                        HookMain.is_first_hook_build = true;
                        processCamera2Init(host, HookMain.c2_state_callback);
                    }
                });
    }

    private void hookCameraManagerOpenCameraWithExecutor(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader,
                "openCamera", String.class, Executor.class, CameraDevice.StateCallback.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args[2] == null) return;
                        if (param.args[2].equals(HookMain.c2_state_cb)) return;
                        HookMain.c2_state_cb = (CameraDevice.StateCallback) param.args[2];
                        if (host.isDisabled()) return;
                        host.updateShouldShowToast();
                        if (!host.hasVirtualVideo()) {
                            host.showNoVideoToast(lpparam.packageName);
                            return;
                        }
                        HookMain.c2_state_callback = param.args[2].getClass();
                        Logger.i("camera2 init (2-arg), callback class: " + HookMain.c2_state_callback);
                        HookMain.is_first_hook_build = true;
                        processCamera2Init(host, HookMain.c2_state_callback);
                    }
                });
    }

    private void hookCaptureRequestBuilderAddTarget(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader,
                "addTarget", Surface.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] == null || param.thisObject == null) return;
                        host.updateShouldShowToast();
                        if (!host.hasVirtualVideo()) {
                            host.showNoVideoToast(lpparam.packageName);
                            return;
                        }
                        if (param.args[0].equals(HookMain.c2_virtual_surface)) return;
                        if (host.isDisabled()) return;
                        String surfaceInfo = param.args[0].toString();
                        if (surfaceInfo.contains("Surface(name=null)")) {
                            if (HookMain.c2_reader_Surfcae == null) {
                                HookMain.c2_reader_Surfcae = (Surface) param.args[0];
                            } else if (!HookMain.c2_reader_Surfcae.equals(param.args[0]) && HookMain.c2_reader_Surfcae_1 == null) {
                                HookMain.c2_reader_Surfcae_1 = (Surface) param.args[0];
                            }
                        } else {
                            if (HookMain.c2_preview_Surfcae == null) {
                                HookMain.c2_preview_Surfcae = (Surface) param.args[0];
                            } else if (!HookMain.c2_preview_Surfcae.equals(param.args[0]) && HookMain.c2_preview_Surfcae_1 == null) {
                                HookMain.c2_preview_Surfcae_1 = (Surface) param.args[0];
                            }
                        }
                        Logger.i("addTarget: " + param.args[0]);
                        param.args[0] = HookMain.c2_virtual_surface;
                    }
                });
    }

    private void hookCaptureRequestBuilderRemoveTarget(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader,
                "removeTarget", Surface.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] == null || param.thisObject == null) return;
                        host.updateShouldShowToast();
                        if (!host.hasVirtualVideo()) {
                            host.showNoVideoToast(lpparam.packageName);
                            return;
                        }
                        if (host.isDisabled()) return;
                        Surface rm = (Surface) param.args[0];
                        if (rm.equals(HookMain.c2_preview_Surfcae)) HookMain.c2_preview_Surfcae = null;
                        if (rm.equals(HookMain.c2_preview_Surfcae_1)) HookMain.c2_preview_Surfcae_1 = null;
                        if (rm.equals(HookMain.c2_reader_Surfcae_1)) HookMain.c2_reader_Surfcae_1 = null;
                        if (rm.equals(HookMain.c2_reader_Surfcae)) HookMain.c2_reader_Surfcae = null;
                        Logger.i("removeTarget: " + param.args[0]);
                    }
                });
    }

    private void hookCaptureRequestBuilderBuild(HookMain host, final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader,
                "build", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject == null) return;
                        if (param.thisObject.equals(HookMain.c2_builder)) return;
                        HookMain.c2_builder = (CaptureRequest.Builder) param.thisObject;
                        host.updateShouldShowToast();
                        if (!host.hasVirtualVideo()) {
                            host.showNoVideoToast(lpparam.packageName);
                            return;
                        }
                        if (host.isDisabled()) return;
                        Logger.i("CaptureRequest.Builder build");
                        processCamera2Play(host);
                    }
                });
    }

    private void hookImageReaderNewInstance(HookMain host, XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.media.ImageReader", lpparam.classLoader,
                "newInstance", int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Logger.i("ImageReader newInstance: width=" + param.args[0] + " height=" + param.args[1] + " format=" + param.args[2]);
                        HookMain.c2_ori_width = (int) param.args[0];
                        HookMain.c2_ori_height = (int) param.args[1];
                        HookMain.imageReaderFormat = (int) param.args[2];
                        host.updateShouldShowToast();
                        if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                            try {
                                Toast.makeText(HookMain.toast_content,
                                        "应用创建了渲染器：\n宽：" + param.args[0] + "\n高：" + param.args[1] + "\n一般只需要宽高比与视频相同",
                                        Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                Logger.i("toast: " + e);
                            }
                        }
                    }
                });
    }

    private void hookOnCaptureFailed(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraCaptureSession.CaptureCallback", lpparam.classLoader,
                "onCaptureFailed", CameraCaptureSession.class, CaptureRequest.class, CaptureFailure.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Logger.i("onCaptureFailed, reason: " + ((CaptureFailure) param.args[2]).getReason());
                    }
                });
    }

    private void processCamera2Play(HookMain host) {
        if (HookMain.c2_reader_Surfcae != null) {
            if (HookMain.c2_hw_decode_obj != null) {
                HookMain.c2_hw_decode_obj.stopDecode();
                HookMain.c2_hw_decode_obj = null;
            }
            HookMain.c2_hw_decode_obj = new VideoToFrames();
            try {
                HookMain.c2_hw_decode_obj.setSaveFrames("null",
                        HookMain.imageReaderFormat == 256 ? OutputImageFormat.JPEG : OutputImageFormat.NV21);
                HookMain.c2_hw_decode_obj.set_surfcae(HookMain.c2_reader_Surfcae);
                HookMain.c2_hw_decode_obj.decode(HookMain.video_path + VIDEO_FILE);
            } catch (Throwable t) {
                Logger.i(String.valueOf(t));
            }
        }
        if (HookMain.c2_reader_Surfcae_1 != null) {
            if (HookMain.c2_hw_decode_obj_1 != null) {
                HookMain.c2_hw_decode_obj_1.stopDecode();
                HookMain.c2_hw_decode_obj_1 = null;
            }
            HookMain.c2_hw_decode_obj_1 = new VideoToFrames();
            try {
                HookMain.c2_hw_decode_obj_1.setSaveFrames("null",
                        HookMain.imageReaderFormat == 256 ? OutputImageFormat.JPEG : OutputImageFormat.NV21);
                HookMain.c2_hw_decode_obj_1.set_surfcae(HookMain.c2_reader_Surfcae_1);
                HookMain.c2_hw_decode_obj_1.decode(HookMain.video_path + VIDEO_FILE);
            } catch (Throwable t) {
                Logger.i(String.valueOf(t));
            }
        }
        if (HookMain.c2_preview_Surfcae != null) {
            if (HookMain.c2_player == null) HookMain.c2_player = new MediaPlayer();
            else {
                HookMain.c2_player.release();
                HookMain.c2_player = new MediaPlayer();
            }
            HookMain.c2_player.setSurface(HookMain.c2_preview_Surfcae);
            if (!new File(host.getDcimCamera1Path() + FILE_NO_SILENT).exists()) HookMain.c2_player.setVolume(0, 0);
            HookMain.c2_player.setLooping(true);
            try {
                HookMain.c2_player.setOnPreparedListener(mp -> HookMain.c2_player.start());
                HookMain.c2_player.setDataSource(HookMain.video_path + VIDEO_FILE);
                HookMain.c2_player.prepare();
            } catch (Exception e) {
                Logger.i("c2player: " + HookMain.c2_preview_Surfcae + " " + e);
            }
        }
        if (HookMain.c2_preview_Surfcae_1 != null) {
            if (HookMain.c2_player_1 == null) HookMain.c2_player_1 = new MediaPlayer();
            else {
                HookMain.c2_player_1.release();
                HookMain.c2_player_1 = new MediaPlayer();
            }
            HookMain.c2_player_1.setSurface(HookMain.c2_preview_Surfcae_1);
            if (!new File(host.getDcimCamera1Path() + FILE_NO_SILENT).exists()) HookMain.c2_player_1.setVolume(0, 0);
            HookMain.c2_player_1.setLooping(true);
            try {
                HookMain.c2_player_1.setOnPreparedListener(mp -> HookMain.c2_player_1.start());
                HookMain.c2_player_1.setDataSource(HookMain.video_path + VIDEO_FILE);
                HookMain.c2_player_1.prepare();
            } catch (Exception e) {
                Logger.i("c2player1: " + HookMain.c2_preview_Surfcae_1 + " " + e);
            }
        }
        Logger.i("camera2 play done");
    }

    private Surface createVirtualSurface(HookMain host) {
        if (HookMain.need_recreate) {
            if (HookMain.c2_virtual_surfaceTexture != null) {
                HookMain.c2_virtual_surfaceTexture.release();
                HookMain.c2_virtual_surfaceTexture = null;
            }
            if (HookMain.c2_virtual_surface != null) {
                HookMain.c2_virtual_surface.release();
                HookMain.c2_virtual_surface = null;
            }
            HookMain.c2_virtual_surfaceTexture = new SurfaceTexture(15);
            HookMain.c2_virtual_surface = new Surface(HookMain.c2_virtual_surfaceTexture);
            HookMain.need_recreate = false;
        } else {
            if (HookMain.c2_virtual_surface == null) {
                HookMain.need_recreate = true;
                return createVirtualSurface(host);
            }
        }
        Logger.i("create_virtual_surface: " + HookMain.c2_virtual_surface);
        return HookMain.c2_virtual_surface;
    }

    private void processCamera2Init(HookMain host, Class<?> hookedClass) {
        XposedHelpers.findAndHookMethod(hookedClass, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                HookMain.need_recreate = true;
                createVirtualSurface(host);
                if (HookMain.c2_player != null) {
                    HookMain.c2_player.stop();
                    HookMain.c2_player.reset();
                    HookMain.c2_player.release();
                    HookMain.c2_player = null;
                }
                if (HookMain.c2_hw_decode_obj_1 != null) {
                    HookMain.c2_hw_decode_obj_1.stopDecode();
                    HookMain.c2_hw_decode_obj_1 = null;
                }
                if (HookMain.c2_hw_decode_obj != null) {
                    HookMain.c2_hw_decode_obj.stopDecode();
                    HookMain.c2_hw_decode_obj = null;
                }
                if (HookMain.c2_player_1 != null) {
                    HookMain.c2_player_1.stop();
                    HookMain.c2_player_1.reset();
                    HookMain.c2_player_1.release();
                    HookMain.c2_player_1 = null;
                }
                HookMain.c2_preview_Surfcae_1 = null;
                HookMain.c2_reader_Surfcae_1 = null;
                HookMain.c2_reader_Surfcae = null;
                HookMain.c2_preview_Surfcae = null;
                HookMain.is_first_hook_build = true;
                Logger.i("camera2 opened");

                host.updateShouldShowToast();
                if (!host.hasVirtualVideo()) {
                    host.showNoVideoToast(HookMain.toast_content != null ? HookMain.toast_content.getPackageName() : "");
                    return;
                }

                hookCreateCaptureSession(param);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    hookCreateCaptureSessionByOutputConfigurations(param);
                }
                hookCreateConstrainedHighSpeedCaptureSession(param);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    hookCreateReprocessableCaptureSession(param);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    hookCreateReprocessableCaptureSessionByConfigurations(param);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    hookCreateCaptureSessionSessionConfiguration(param);
                }
            }
        });

        XposedHelpers.findAndHookMethod(hookedClass, "onError", CameraDevice.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Logger.i("camera onError: " + param.args[1]);
            }
        });

        XposedHelpers.findAndHookMethod(hookedClass, "onDisconnected", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Logger.i("camera onDisconnected");
            }
        });
    }

    private void hookCreateCaptureSession(XC_MethodHook.MethodHookParam param) {
        XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSession",
                List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) {
                        if (paramd.args[0] != null) {
                            Logger.i("createCaptureSession: original=" + paramd.args[0] + " virtual=" + HookMain.c2_virtual_surface);
                            paramd.args[0] = Arrays.asList(HookMain.c2_virtual_surface);
                            if (paramd.args[1] != null) {
                                processCamera2SessionCallback((CameraCaptureSession.StateCallback) paramd.args[1]);
                            }
                        }
                    }
                });
    }

    private void hookCreateCaptureSessionByOutputConfigurations(XC_MethodHook.MethodHookParam param) {
        XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSessionByOutputConfigurations",
                List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) {
                        if (paramd.args[0] != null) {
                            HookMain.outputConfiguration = new OutputConfiguration(HookMain.c2_virtual_surface);
                            paramd.args[0] = Arrays.asList(HookMain.outputConfiguration);
                            Logger.i("createCaptureSessionByOutputConfigurations");
                            if (paramd.args[1] != null) {
                                processCamera2SessionCallback((CameraCaptureSession.StateCallback) paramd.args[1]);
                            }
                        }
                    }
                });
    }

    private void hookCreateConstrainedHighSpeedCaptureSession(XC_MethodHook.MethodHookParam param) {
        XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createConstrainedHighSpeedCaptureSession",
                List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) {
                        if (paramd.args[0] != null) {
                            paramd.args[0] = Arrays.asList(HookMain.c2_virtual_surface);
                            Logger.i("createConstrainedHighSpeedCaptureSession");
                            if (paramd.args[1] != null) {
                                processCamera2SessionCallback((CameraCaptureSession.StateCallback) paramd.args[1]);
                            }
                        }
                    }
                });
    }

    private void hookCreateReprocessableCaptureSession(XC_MethodHook.MethodHookParam param) {
        XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createReprocessableCaptureSession",
                InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) {
                        if (paramd.args[1] != null) {
                            paramd.args[1] = Arrays.asList(HookMain.c2_virtual_surface);
                            Logger.i("createReprocessableCaptureSession");
                            if (paramd.args[2] != null) {
                                processCamera2SessionCallback((CameraCaptureSession.StateCallback) paramd.args[2]);
                            }
                        }
                    }
                });
    }

    private void hookCreateReprocessableCaptureSessionByConfigurations(XC_MethodHook.MethodHookParam param) {
        XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createReprocessableCaptureSessionByConfigurations",
                InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) {
                        if (paramd.args[1] != null) {
                            HookMain.outputConfiguration = new OutputConfiguration(HookMain.c2_virtual_surface);
                            paramd.args[0] = Arrays.asList(HookMain.outputConfiguration);
                            Logger.i("createReprocessableCaptureSessionByConfigurations");
                            if (paramd.args[2] != null) {
                                processCamera2SessionCallback((CameraCaptureSession.StateCallback) paramd.args[2]);
                            }
                        }
                    }
                });
    }

    private void hookCreateCaptureSessionSessionConfiguration(XC_MethodHook.MethodHookParam param) {
        XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSession",
                SessionConfiguration.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) {
                        if (paramd.args[0] != null) {
                            Logger.i("createCaptureSession (SessionConfiguration)");
                            HookMain.sessionConfiguration = (SessionConfiguration) paramd.args[0];
                            HookMain.outputConfiguration = new OutputConfiguration(HookMain.c2_virtual_surface);
                            HookMain.fake_sessionConfiguration = new SessionConfiguration(
                                    HookMain.sessionConfiguration.getSessionType(),
                                    Arrays.asList(HookMain.outputConfiguration),
                                    HookMain.sessionConfiguration.getExecutor(),
                                    HookMain.sessionConfiguration.getStateCallback());
                            paramd.args[0] = HookMain.fake_sessionConfiguration;
                            processCamera2SessionCallback(HookMain.sessionConfiguration.getStateCallback());
                        }
                    }
                });
    }

    private void processCamera2SessionCallback(CameraCaptureSession.StateCallback callback) {
        if (callback == null) return;
        XposedHelpers.findAndHookMethod(callback.getClass(), "onConfigureFailed", CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Logger.i("onConfigureFailed: " + param.args[0]);
            }
        });
        XposedHelpers.findAndHookMethod(callback.getClass(), "onConfigured", CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Logger.i("onConfigured: " + param.args[0]);
            }
        });
        XposedHelpers.findAndHookMethod(callback.getClass(), "onClosed", CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Logger.i("onClosed: " + param.args[0]);
            }
        });
    }
}
