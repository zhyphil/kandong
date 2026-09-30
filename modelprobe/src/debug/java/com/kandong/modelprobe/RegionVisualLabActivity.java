package com.kandong.modelprobe;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.TextView;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Debug-only fixed-package host. External Intent data never selects mode, assets or classes. */
public final class RegionVisualLabActivity extends Activity {
    public interface Surface {
        View getView();
        void onForeground(boolean active);
        void dispose();
    }
    private static final String TEST_PACKAGE = "com.kandong.modelprobe.test";
    private static final String SURFACE_CLASS = "com.kandong.modelprobe.RegionVisualLabSurface";
    private static final int PROTOCOL_VERSION = 2;
    // Cache code/native ownership only, never Activity, View or resource Context.
    private static Class<?> cachedSurface;
    private static String loadedPackageIdentity;
    private static boolean restartRequired;
    private Surface surface;
    public Surface getLabSurface() { return surface; }

    private static void verifyArm64Library(ZipFile apk, String name) throws IOException {
        ZipEntry entry = apk.getEntry("lib/arm64-v8a/" + name);
        if (entry == null || entry.getSize() < 64 || entry.getMethod() != ZipEntry.STORED)
            throw new IOException("Unsupported native package");
        byte[] header = new byte[20];
        try (InputStream stream = apk.getInputStream(entry)) {
            int offset = 0;
            while (offset < header.length) {
                int read = stream.read(header, offset, header.length - offset);
                if (read <= 0) throw new IOException("Native header missing");
                offset += read;
            }
        }
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' ||
                header[4] != 2 || header[5] != 1 || (header[18] & 0xff) != 183 || header[19] != 0)
            throw new IOException("Unsupported native ABI");
    }

    private synchronized Class<?> surfaceType() throws Exception {
        PackageManager pm = getPackageManager();
        PackageInfo installed = pm.getPackageInfo(TEST_PACKAGE, 0);
        ApplicationInfo test = installed.applicationInfo;
        if (test == null || !TEST_PACKAGE.equals(test.packageName) ||
                pm.checkSignatures(getPackageName(), TEST_PACKAGE) != PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Incompatible fixed package");
        if (!Process.is64Bit() || !Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a") ||
                (test.splitSourceDirs != null && test.splitSourceDirs.length != 0))
            throw new IOException("Unsupported lab package ABI");
        File apkFile = new File(test.sourceDir);
        String identity = test.sourceDir + ":" + installed.getLongVersionCode() + ":" +
                installed.lastUpdateTime + ":" + apkFile.length() + ":" + apkFile.lastModified();
        synchronized (RegionVisualLabActivity.class) {
            if (loadedPackageIdentity != null && !loadedPackageIdentity.equals(identity)) restartRequired = true;
            if (restartRequired) throw new IllegalStateException("Restart process after package replacement");
            if (cachedSurface != null) return cachedSurface;
            try (ZipFile apk = new ZipFile(apkFile)) {
                verifyArm64Library(apk, "libopencv_java5.so");
                verifyArm64Library(apk, "libc++_shared.so");
            }
            ClassLoader parent = getClassLoader();
            Class<?> type;
            try {
                // Instrumentation already exposes the test code. Reuse its native ownership.
                type = Class.forName(SURFACE_CLASS, false, parent);
            } catch (ClassNotFoundException absent) {
                String nativePath = test.sourceDir + "!/lib/arm64-v8a";
                if (test.nativeLibraryDir != null && !test.nativeLibraryDir.isEmpty())
                    nativePath = test.nativeLibraryDir + File.pathSeparator + nativePath;
                // Normal PathClassLoader delegation remains parent-first for host Kotlin/ORT.
                ClassLoader loader = new PathClassLoader(test.sourceDir, nativePath, parent);
                type = Class.forName(SURFACE_CLASS, false, loader);
            }
            ClassLoader loader = type.getClassLoader();
            for (String name : new String[] {"kotlin.Unit", "com.kandong.modelprobe.OrtProbeEngine", "ai.onnxruntime.OrtSession"}) {
                if (Class.forName(name, false, loader) != Class.forName(name, false, parent))
                    throw new LinkageError("Host runtime ownership mismatch");
            }
            if (Class.forName("org.opencv.android.OpenCVLoader", false, loader).getClassLoader() != loader)
                throw new LinkageError("OpenCV test-loader ownership mismatch");
            if (!Surface.class.isAssignableFrom(type) || type.getField("PROTOCOL_VERSION").getInt(null) != PROTOCOL_VERSION)
                throw new LinkageError("Surface protocol mismatch");
            type.getConstructor(Activity.class, Context.class);
            cachedSurface = type;
            loadedPackageIdentity = identity;
            return type;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) bars.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        try {
            Class<?> type = surfaceType();
            // Resources only: code is always resolved through the verified, cached loader above.
            Context resources = createPackageContext(TEST_PACKAGE, 0);
            surface = (Surface) type.getConstructor(Activity.class, Context.class).newInstance(this, resources);
            setContentView(surface.getView());
        } catch (Exception | LinkageError failure) {
            surface = null;
            TextView error = new TextView(this);
            error.setText("看懂选区实验\n\n固定 PNG 整页 OCR · 不读取屏幕、不翻译\n\n无法打开实验：配套测试包、签名、协议或 ARM64 原生库不兼容。安装包更新后须重启实验进程。");
            error.setTextSize(18); error.setPadding(32, 64, 32, 32);
            error.setFitsSystemWindows(true); error.setTag("lab_error"); setContentView(error);
        }
    }
    @Override protected void onResume() { super.onResume(); if (surface != null) surface.onForeground(true); }
    @Override protected void onPause() { if (surface != null) surface.onForeground(false); super.onPause(); }
    @Override protected void onDestroy() { if (surface != null) { surface.dispose(); surface = null; } super.onDestroy(); }
}
