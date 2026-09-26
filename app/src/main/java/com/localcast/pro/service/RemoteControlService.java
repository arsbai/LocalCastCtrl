package com.localcast.pro.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.localcast.pro.LocalCastApplication;
import com.localcast.pro.core.CastManager;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** Executes only user-approved commands belonging to an active paired cast. */
public final class RemoteControlService extends AccessibilityService {
    private static volatile RemoteControlService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastInputAt;

    public static boolean isAvailable() { return active != null; }

    public static void dispatch(byte[] payload, int length) {
        RemoteControlService service = active;
        if (service == null || length < 1 || length > 256) return;
        String json = new String(payload, 0, length, StandardCharsets.UTF_8);
        service.main.post(() -> service.performCommand(json));
    }

    @Override protected void onServiceConnected() { active = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        if (active == this) active = null;
        super.onDestroy();
    }

    private void performCommand(String json) {
        CastManager cast = ((LocalCastApplication) getApplication()).getCastManager();
        if (cast == null || cast.getCurrentMode() != CastManager.CastMode.SENDING
                || !cast.getConnectionManager().isControlPaired()
                || !cast.isRemoteControlAllowed()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastInputAt < 40) return;
        lastInputAt = now;
        try {
            JSONObject input = new JSONObject(json);
            String action = input.optString("action");
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
