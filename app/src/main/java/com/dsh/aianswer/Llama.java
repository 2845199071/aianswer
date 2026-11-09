package com.dsh.aianswer;

import android.util.Log;

/** llama.cpp 的 JNI 封装。so 按依赖顺序加载，失败时把原因留给 UI 显示。 */
public final class Llama {

    private static final String TAG = "AIQ";
    private static volatile boolean sLoaded = false;
    private static String sLoadError = null;

    private long handle = 0;

    public static synchronized boolean ensureLoaded() {
        if (sLoaded) return true;
        try {
            // 顺序不能颠倒：aiq 依赖前面四个
            System.loadLibrary("ggml-base");
            System.loadLibrary("ggml");
            System.loadLibrary("ggml-cpu");
            System.loadLibrary("llama");
            System.loadLibrary("aiq");
            sLoaded = true;
        } catch (Throwable t) {
            sLoadError = t.toString();
            Log.e(TAG, "原生库加载失败", t);
        }
        return sLoaded;
    }

    public static String loadError() { return sLoadError; }

    public synchronized boolean open(String path, int nCtx, int threads) {
        if (!ensureLoaded()) return false;
        close();
        long t0 = System.currentTimeMillis();
        handle = nativeLoad(path, nCtx, threads);
        Log.i(TAG, "模型打开耗时 " + (System.currentTimeMillis() - t0) + "ms handle=" + handle);
        return handle != 0;
    }

    public boolean isReady() { return handle != 0; }

    /** 返回每个候选字母的归一化概率，与 letters 同序。 */
    public synchronized float[] score(String prompt, String[] letters) {
        if (handle == 0) return null;
        return nativeScore(handle, prompt, letters);
    }

    public synchronized String gen(String prompt, int maxTok) {
        if (handle == 0) return null;
        return nativeGen(handle, prompt, maxTok);
    }

    public synchronized void close() {
        if (handle != 0) {
            nativeFree(handle);
            handle = 0;
        }
    }

    private static native long    nativeLoad (String path, int nCtx, int threads);
    private static native void    nativeFree (long h);
    private static native float[] nativeScore(long h, String prompt, String[] letters);
    private static native String  nativeGen  (long h, String prompt, int maxTok);
}
