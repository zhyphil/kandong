package com.kandong.modelprobe;

import android.app.Activity;
import android.os.Bundle;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.TextView;
import dalvik.system.PathClassLoader;

/** Debug-only host. No external Intent data or arbitrary class/package names are accepted. */
public final class RegionVisualLabActivity extends Activity {
    public interface Surface {
        View getView();
        void onForeground(boolean active);
        void dispose();
    }
    private static final String TEST_PACKAGE = "com.kandong.modelprobe.test";
    private static final String SURFACE_CLASS = "com.kandong.modelprobe.RegionVisualLabSurface";
    private Surface surface;
    public Surface getLabSurface() { return surface; }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
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
            PackageManager pm = getPackageManager();
            ApplicationInfo test = pm.getApplicationInfo(TEST_PACKAGE, 0);
            if (pm.checkSignatures(getPackageName(), TEST_PACKAGE) != PackageManager.SIGNATURE_MATCH)
                throw new SecurityException("incompatible signature");
            Class<?> type;
            try { type = Class.forName(SURFACE_CLASS, true, getClassLoader()); }
            catch (ClassNotFoundException absent) {
                type = Class.forName(SURFACE_CLASS, true, new PathClassLoader(test.sourceDir, getClassLoader()));
            }
            surface = (Surface) type.getConstructor(Activity.class).newInstance(this);
            setContentView(surface.getView());
        } catch (ReflectiveOperationException | PackageManager.NameNotFoundException | SecurityException | LinkageError | ClassCastException failure) {
            surface = null;
            TextView error = new TextView(this);
            error.setText("看懂选区实验\n\n固定样例 · 不识别、不翻译、不读取屏幕\n\n无法打开实验：缺少配套测试包，或版本／签名不兼容。请由开发者检查两个实验安装包。");
            error.setTextSize(18); error.setPadding(32, 64, 32, 32);
            error.setFitsSystemWindows(true); error.setTag("lab_error"); setContentView(error);
        }
    }
    @Override protected void onResume() { super.onResume(); if (surface != null) surface.onForeground(true); }
    @Override protected void onPause() { if (surface != null) surface.onForeground(false); super.onPause(); }
    @Override protected void onDestroy() { if (surface != null) { surface.dispose(); surface = null; } super.onDestroy(); }
}
