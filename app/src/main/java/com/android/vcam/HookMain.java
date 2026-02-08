package com.android.vcam;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed module entry. Coordinates shared state (video path, toast context) and
 * delegates Camera API hooks to {@link Camera1Hook} and {@link Camera2Hook}.
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String SUBDIR_CAMERA1 = "DCIM/Camera1/";
    static final String VIDEO_FILE = "virtual.mp4";
    private static final String FILE_DISABLE = "disable.jpg";
    private static final String FILE_NO_TOAST = "no_toast.jpg";

    // ---------- Shared state (used by Camera1Hook & Camera2Hook) ----------
    public static String video_path = "/storage/emulated/0/" + SUBDIR_CAMERA1;
    public static Context toast_content;
    public static boolean need_to_show_toast = true;

    // ---------- Camera1 (legacy) state ----------
    public static android.view.Surface mSurface;
    public static android.graphics.SurfaceTexture mSurfacetexture;
    public static android.media.MediaPlayer mMediaPlayer;
    public static android.graphics.SurfaceTexture fake_SurfaceTexture;
    public static android.hardware.Camera origin_preview_camera;
    public static android.hardware.Camera camera_onPreviewFrame;
    public static android.hardware.Camera start_preview_camera;
    public static volatile byte[] data_buffer = {0};
    public static byte[] input;
    public static int mhight;
    public static int mwidth;
    public static boolean is_someone_playing;
    public static boolean is_hooked;
    public static VideoToFrames hw_decode_obj;
    public static android.graphics.SurfaceTexture c1_fake_texture;
    public static android.view.Surface c1_fake_surface;
    public static android.view.SurfaceHolder ori_holder;
    public static android.media.MediaPlayer mplayer1;
    public static android.hardware.Camera mcamera1;
    public static int onemhight;
    public static int onemwidth;
    public static Class<?> camera_callback_calss;

    // ---------- Camera2 state ----------
    public static android.view.Surface c2_preview_Surfcae;
    public static android.view.Surface c2_preview_Surfcae_1;
    public static android.view.Surface c2_reader_Surfcae;
    public static android.view.Surface c2_reader_Surfcae_1;
    public static android.media.MediaPlayer c2_player;
    public static android.media.MediaPlayer c2_player_1;
    public static android.view.Surface c2_virtual_surface;
    public static android.graphics.SurfaceTexture c2_virtual_surfaceTexture;
    public static boolean need_recreate;
    public static android.hardware.camera2.CameraDevice.StateCallback c2_state_cb;
    public static android.hardware.camera2.CaptureRequest.Builder c2_builder;
    public static android.hardware.camera2.params.SessionConfiguration fake_sessionConfiguration;
    public static android.hardware.camera2.params.SessionConfiguration sessionConfiguration;
    public static android.hardware.camera2.params.OutputConfiguration outputConfiguration;
    public static int imageReaderFormat = 0;
    public static boolean is_first_hook_build = true;
    public static int c2_ori_width = 1280;
    public static int c2_ori_height = 720;
    public static Class<?> c2_state_callback;
    public static VideoToFrames c2_hw_decode_obj;
    public static VideoToFrames c2_hw_decode_obj_1;

    // ---------- Helpers (package-private for Camera1Hook/Camera2Hook) ----------
    static String getDcimCamera1Path() {
        return Environment.getExternalStorageDirectory().getPath() + "/" + SUBDIR_CAMERA1;
    }

    boolean isDisabled() {
        return new File(getDcimCamera1Path() + FILE_DISABLE).exists();
    }

    void updateShouldShowToast() {
        need_to_show_toast = !new File(getDcimCamera1Path() + FILE_NO_TOAST).exists();
    }

    void showNoVideoToast(String packageName) {
        if (toast_content == null || !need_to_show_toast) return;
        try {
            Toast.makeText(toast_content, "不存在替换视频\n" + packageName + " 当前路径：" + video_path, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Logger.i("toast: " + e);
        }
    }

    boolean hasVirtualVideo() {
        return new File(video_path + VIDEO_FILE).exists();
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        hookApplicationOnCreate(lpparam);
        new Camera1Hook().hook(this, lpparam);
        new Camera2Hook().hook(this, lpparam);
    }

    /**
     * Hook Application.onCreate to set video_path and toast_content per process.
     */
    private void hookApplicationOnCreate(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader,
                "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (!(param.args[0] instanceof Application)) return;
                        try {
                            toast_content = ((Application) param.args[0]).getApplicationContext();
                        } catch (Exception e) {
                            Logger.i(String.valueOf(e));
                        }
                        File forcePrivate = new File(getDcimCamera1Path() + "private_dir.jpg");
                        if (toast_content != null) {
                            int authStatus = 0;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    authStatus += (toast_content.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) + 1);
                                } catch (Exception e) {
                                    Logger.i("permission-check: " + e);
                                }
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        authStatus += (toast_content.checkSelfPermission(Manifest.permission.MANAGE_EXTERNAL_STORAGE) + 1);
                                    }
                                } catch (Exception e) {
                                    Logger.i("permission-check: " + e);
                                }
                            } else {
                                if (toast_content.checkCallingPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                    authStatus = 2;
                                }
                            }
                            if (authStatus < 1 || forcePrivate.exists()) {
                                File shownDir = new File(toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/");
                                if (shownDir.exists() && !shownDir.isDirectory()) shownDir.delete();
                                if (!shownDir.exists()) shownDir.mkdir();
                                File hasShown = new File(toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/has_shown");
                                File forceShow = new File(getDcimCamera1Path() + "force_show.jpg");
                                if (!lpparam.packageName.equals(BuildConfig.APPLICATION_ID) && (!hasShown.exists() || forceShow.exists())) {
                                    try {
                                        Toast.makeText(toast_content,
                                                lpparam.packageName + "未授予读取本地目录权限，请检查权限\nCamera1目前重定向为 " + toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/",
                                                Toast.LENGTH_SHORT).show();
                                        try (FileOutputStream fos = new FileOutputStream(toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/has_shown")) {
                                            fos.write("shown".getBytes());
                                        }
                                    } catch (Exception e) {
                                        Logger.i("switch-dir: " + e);
                                    }
                                }
                                video_path = toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/";
                            } else {
                                video_path = getDcimCamera1Path();
                            }
                        } else {
                            video_path = getDcimCamera1Path();
                            File dcim = new File(getDcimCamera1Path());
                            if (dcim.canWrite()) {
                                File cam1 = new File(video_path);
                                if (!cam1.exists()) cam1.mkdir();
                            }
                        }
                    }
                });
    }
}
