package com.android.vcam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

public class MainActivity extends Activity {

    private static final String TAG_VCAM = "VCAM";
    private static final String DIR_CAMERA1 = "DCIM/Camera1";
    private static final String FILE_DISABLE = "disable.jpg";
    private static final String FILE_FORCE_SHOW = "force_show.jpg";
    private static final String FILE_NO_SILENT = "no-silent.jpg";
    private static final String FILE_PRIVATE_DIR = "private_dir.jpg";
    private static final String FILE_NO_TOAST = "no_toast.jpg";

    private Switch forceShowSwitch;
    private Switch disableSwitch;
    private Switch playSoundSwitch;
    private Switch forcePrivateDirSwitch;
    private Switch disableToastSwitch;

    private static File getCamera1Dir() {
        return new File(Environment.getExternalStorageDirectory().getAbsolutePath(), DIR_CAMERA1);
    }

    private static File getFlagFile(String filename) {
        return new File(getCamera1Dir(), filename);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
            Toast.makeText(this, R.string.permission_lack_warn, Toast.LENGTH_SHORT).show();
        } else if (grantResults.length > 0) {
            File cameraDir = getCamera1Dir();
            if (!cameraDir.exists()) {
                cameraDir.mkdir();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncStateWithFiles();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        disableSwitch = findViewById(R.id.switch2);
        forceShowSwitch = findViewById(R.id.switch1);
        playSoundSwitch = findViewById(R.id.switch3);
        forcePrivateDirSwitch = findViewById(R.id.switch4);
        disableToastSwitch = findViewById(R.id.switch5);

        syncStateWithFiles();

        findViewById(R.id.button).setOnClickListener(v -> openUrl("https://github.com/w2016561536/android_virtual_cam"));
        findViewById(R.id.button2).setOnClickListener(v -> openUrl("https://gitee.com/w2016561536/android_virtual_cam"));

        disableSwitch.setOnCheckedChangeListener((v, checked) -> onSwitchChanged(v, checked, FILE_DISABLE));
        forceShowSwitch.setOnCheckedChangeListener((v, checked) -> onSwitchChanged(v, checked, FILE_FORCE_SHOW));
        playSoundSwitch.setOnCheckedChangeListener((v, checked) -> onSwitchChanged(v, checked, FILE_NO_SILENT));
        forcePrivateDirSwitch.setOnCheckedChangeListener((v, checked) -> onSwitchChanged(v, checked, FILE_PRIVATE_DIR));
        disableToastSwitch.setOnCheckedChangeListener((v, checked) -> onSwitchChanged(v, checked, FILE_NO_TOAST));
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void onSwitchChanged(CompoundButton button, boolean checked, String filename) {
        if (!button.isPressed()) return;
        if (!hasPermission()) {
            requestPermission();
        } else {
            File file = getFlagFile(filename);
            if (file.exists() != checked) {
                if (checked) {
                    try {
                        file.createNewFile();
                    } catch (IOException e) {
                        Log.w(TAG_VCAM, "createNewFile failed: " + filename, e);
                    }
                } else {
                    file.delete();
                }
            }
        }
        syncStateWithFiles();
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_lack_warn)
                    .setMessage(R.string.permission_description)
                    .setNegativeButton(R.string.negative, (d, i) -> Toast.makeText(this, R.string.permission_lack_warn, Toast.LENGTH_SHORT).show())
                    .setPositiveButton(R.string.positive, (d, i) -> requestPermissions(
                            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1))
                    .show();
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_DENIED
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_DENIED;
        }
        return true;
    }

    private void syncStateWithFiles() {
        Log.d(getPackageName(), "VCAM sync switch state");

        if (!hasPermission()) {
            requestPermission();
            return;
        }
        File cameraDir = getCamera1Dir();
        if (!cameraDir.exists()) {
            cameraDir.mkdir();
        }

        disableSwitch.setChecked(getFlagFile(FILE_DISABLE).exists());
        forceShowSwitch.setChecked(getFlagFile(FILE_FORCE_SHOW).exists());
        playSoundSwitch.setChecked(getFlagFile(FILE_NO_SILENT).exists());
        forcePrivateDirSwitch.setChecked(getFlagFile(FILE_PRIVATE_DIR).exists());
        disableToastSwitch.setChecked(getFlagFile(FILE_NO_TOAST).exists());
    }
}
