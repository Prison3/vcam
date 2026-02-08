package com.android.vcam;

import android.util.Log;

/**
 * Central logger using android.util.Log.
 * Provides i/d/w/e levels; default log() maps to info.
 */
public final class Logger {

    private static final String TAG = "VCAM";

    private Logger() {}

    private static String message(String msg) {
        return msg != null ? msg : "";
    }

    public static void d(String msg) {
        Log.d(TAG, message(msg));
    }

    public static void d(String format, Object... args) {
        Log.d(TAG, message(String.format(format, args)));
    }

    public static void i(String msg) {
        Log.i(TAG, message(msg));
    }

    public static void i(String format, Object... args) {
        Log.i(TAG, message(String.format(format, args)));
    }

    public static void w(String msg) {
        Log.w(TAG, message(msg));
    }

    public static void w(String format, Object... args) {
        Log.w(TAG, message(String.format(format, args)));
    }

    public static void w(Throwable t) {
        if (t != null) {
            Log.w(TAG, t);
        }
    }

    public static void e(String msg) {
        Log.e(TAG, message(msg));
    }

    public static void e(String format, Object... args) {
        Log.e(TAG, message(String.format(format, args)));
    }

    public static void e(Throwable t) {
        if (t != null) {
            Log.e(TAG, t.getMessage(), t);
        }
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, message(msg), t);
    }
}
