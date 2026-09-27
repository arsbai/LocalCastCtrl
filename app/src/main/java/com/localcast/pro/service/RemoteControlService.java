package com.localcast.pro.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.localcast.pro.LocalCastApplication;
import com.localcast.pro.core.CastManager;
import com.localcast.pro.utils.Logger;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** Executes only user-approved commands belonging to an active paired cast. */
public final class RemoteControlService extends AccessibilityService {
    private static final String TAG = "RemoteControlService";
    private static volatile RemoteControlService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastInputAt;
    private WindowManager dimWindowManager;
    private View dimOverlay;
    private volatile boolean displayDimmed;

    public static boolean isAvailable() { return active != null; }
    public static boolean isLocalDisplayDimmed() {
        RemoteControlService service = active;
        return service != null && service.displayDimmed;
    }

    /** Lowest physical brightness without covering the captured pixels. */
    public static boolean enableLocalDisplayDim() {
        RemoteControlService service = active;
        if (service == null || Looper.myLooper() != Looper.getMainLooper()) return false;
        CastManager cast = ((LocalCastApplication) service.getApplication()).getCastManager();
        return cast != null && cast.getCurrentMode() == CastManager.CastMode.SENDING
                && cast.isRemoteControlAllowed() && service.showDimOverlay();
    }

    public static void restoreLocalDisplay() {
        RemoteControlService service = active;
        if (service == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) service.removeDimOverlay();
        else service.main.post(service::removeDimOverlay);
    }

    public static void dispatch(byte[] payload, int length) {
        RemoteControlService service = active;
        if (service == null || length < 1 || length > 256) return;
        String json = new String(payload, 0, length, StandardCharsets.UTF_8);
        service.main.post(() -> service.performCommand(json));
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        active = this;
        Logger.i(TAG, "Remote control accessibility service connected");
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent intent) {
        removeDimOverlay();
        if (active == this) active = null;
        Logger.w(TAG, "Remote control accessibility service unbound by system");
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        removeDimOverlay();
        if (active == this) active = null;
        super.onDestroy();
    }

    private boolean showDimOverlay() {
        if (dimOverlay != null) return true;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return false;
        View overlay = new View(this);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
        params.setTitle("LocalCastCtrl minimum brightness");
        try {
            wm.addView(overlay, params);
            dimWindowManager = wm;
            dimOverlay = overlay;
            displayDimmed = true;
            return true;
        } catch (RuntimeException e) {
            Logger.e(TAG, "Cannot enable minimum-brightness overlay", e);
            return false;
        }
    }

    private void removeDimOverlay() {
        View overlay = dimOverlay;
        WindowManager wm = dimWindowManager;
        dimOverlay = null;
        dimWindowManager = null;
        displayDimmed = false;
        if (overlay != null && wm != null) {
            try { wm.removeViewImmediate(overlay); }
            catch (RuntimeException e) { Logger.e(TAG, "Cannot remove brightness overlay", e); }
        }
    }

    private void performCommand(String json) {
        CastManager cast = ((LocalCastApplication) getApplication()).getCastManager();
        if (cast == null || cast.getCurrentMode() != CastManager.CastMode.SENDING
                || cast.getConnectionManager().getState()
                    != com.localcast.pro.core.ConnectionManager.ConnectionState.CONNECTED
                || !cast.isRemoteControlAllowed()) return;
        try {
            JSONObject input = new JSONObject(json);
            String action = input.optString("action");
            if ("restore_brightness".equals(action)) {
                removeDimOverlay();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastInputAt < 40) return;
            lastInputAt = now;
            if ("back".equals(action)) { performGlobalAction(GLOBAL_ACTION_BACK); return; }
            if ("home".equals(action)) { performGlobalAction(GLOBAL_ACTION_HOME); return; }
            if (!"tap".equals(action) && !"swipe".equals(action)) return;

            double x1 = input.optDouble("x1", Double.NaN);
            double y1 = input.optDouble("y1", Double.NaN);
            double x2 = "tap".equals(action) ? x1 : input.optDouble("x2", Double.NaN);
            double y2 = "tap".equals(action) ? y1 : input.optDouble("y2", Double.NaN);
            if (!valid(x1) || !valid(y1) || !valid(x2) || !valid(y2)) return;
            int duration = "tap".equals(action) ? 80 : input.optInt("duration", 300);
            if (duration < 50 || duration > 1500) return;

            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display display = wm != null ? wm.getDefaultDisplay() : null;
            if (display == null) return;
            Point size = new Point();
            display.getRealSize(size);
            if (size.x < 1 || size.y < 1) return;
            Path path = new Path();
            path.moveTo((float) (x1 * (size.x - 1)), (float) (y1 * (size.y - 1)));
            if ("swipe".equals(action))
                path.lineTo((float) (x2 * (size.x - 1)), (float) (y2 * (size.y - 1)));
            dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null);
        } catch (Exception ignored) {
            // A malformed network packet must never trigger an input action.
        }
    }

    private static boolean valid(double coordinate) {
        return !Double.isNaN(coordinate) && !Double.isInfinite(coordinate)
                && coordinate >= 0 && coordinate <= 1;
    }
}
