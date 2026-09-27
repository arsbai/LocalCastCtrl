package com.localcast.pro.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.localcast.pro.LocalCastApplication;
import com.localcast.pro.R;
import com.localcast.pro.core.CastManager;
import com.localcast.pro.service.RemoteControlService;
import com.localcast.pro.utils.Logger;
import com.localcast.pro.utils.FrameRateUtils;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SenderActivity extends AppCompatActivity {

    private static final String TAG = "SenderActivity";
    private static final int REQUEST_AUDIO_PERMISSION = 1001;

    private TextView tvReceiverName, tvLatency, tvBitrate, tvFps;
    private MaterialButton btnPause, btnStop, btnSwitch, btnFullscreen, btnAllowControl, btnDim;
    private View layoutStats;

    private CastManager castManager;
    private boolean isPaused;
    private boolean senderFullscreen;
    private boolean awaitingAccessibilitySettings;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();

    private float dX, dY, initX, initY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameRateUtils.applyCastingRefreshRate(this);
        setContentView(R.layout.activity_sender);

        // 检查音频权限
        if (!checkAudioPermission()) {
            Logger.w(TAG, "Audio permission not granted, casting without audio");
        }

        tvReceiverName = findViewById(R.id.tv_receiver_name);
        tvLatency = findViewById(R.id.tv_latency);
        tvBitrate = findViewById(R.id.tv_bitrate);
        tvFps = findViewById(R.id.tv_fps);
        btnPause = findViewById(R.id.btn_pause);
        btnStop = findViewById(R.id.btn_stop);
        btnSwitch = findViewById(R.id.btn_switch);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        btnAllowControl = findViewById(R.id.btn_allow_control);
        btnDim = findViewById(R.id.btn_dim_sender);
        layoutStats = findViewById(R.id.layout_stats);

        castManager = ((LocalCastApplication) getApplication()).getCastManager();
        if (castManager == null || castManager.getCurrentMode() != CastManager.CastMode.SENDING) {
            Toast.makeText(this, "投屏未启动", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 显示"正在连接…"状态
        tvReceiverName.setText("正在连接…");

        // 暂停/恢复
        btnPause.setOnClickListener(v -> {
            isPaused = !isPaused;
            if (isPaused) {
                castManager.pauseCasting();
                btnPause.setText("恢复");
            } else {
                castManager.resumeCasting();
                btnPause.setText("暂停");
            }
        });

        btnStop.setOnClickListener(v -> {
            try {
                castManager.stopCurrentMode();
            } catch (Exception e) {
                // 确保即使stopCurrentMode异常也能关闭
            }
            finish();
        });

        btnSwitch.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("结束当前投屏并切换方向？")
                .setMessage("切换方向需要断开当前连接。另一台手机也需要切换到发送投屏。")
                .setPositiveButton("结束并切换", (dialog, which) -> {
                    castManager.stopCurrentMode();
                    startActivity(new Intent(this, ReceiverActivity.class));
                    finish();
                })
                .setNegativeButton("取消", null).show());

        // Only change the receiver presentation. Recreating MediaProjection here
        // can break the stream, and COVER cuts off controls on a phone screen.
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        btnAllowControl.setOnClickListener(v -> toggleRemoteControl());
        btnDim.setOnClickListener(v -> toggleLocalDisplayDim());

        // 悬浮统计拖拽
        layoutStats.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = v.getX(); initY = v.getY();
                        dX = v.getX() - e.getRawX(); dY = v.getY() - e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        v.animate().x(e.getRawX() + dX).y(e.getRawY() + dY).setDuration(0).start();
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.animate().x(initX).y(initY).setDuration(300).start();
                        return true;
                }
                return false;
            }
        });

        // 回调
        castManager.setOnCastStateListener(new CastManager.OnCastStateListener() {
            @Override public void onModeChanged(CastManager.CastMode m) {
                if (m == CastManager.CastMode.IDLE) finish();
            }

            @Override
            public void onConnected(com.localcast.pro.core.DeviceInfo d) {
                runOnUiThread(() -> {
                    tvReceiverName.setText("投屏至 " + d.getDeviceName());
                    refreshControlButton();
                    Toast.makeText(SenderActivity.this,
                            "投屏已连接", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onDisconnected(String reason) {
                runOnUiThread(() -> {
                    RemoteControlService.restoreLocalDisplay();
                    Toast.makeText(SenderActivity.this,
                            "投屏断开: " + reason, Toast.LENGTH_LONG).show();
                    finish();
                });
            }

            @Override
            public void onSenderStats(int fps, int kbps, long ms) {
                runOnUiThread(() -> {
                    // The transport currently has no round-trip acknowledgement;
                    // presenting the placeholder 0ms as real latency is misleading.
                    tvLatency.setText(ms > 0 ? ms + "ms" : "--");
                    tvBitrate.setText(kbps < 100
                            ? kbps + " Kbps"
                            : String.format(Locale.getDefault(), "%.1f Mbps", kbps / 1000.0));
                    tvFps.setText(fps + " fps");
                    if (ms > 0) {
                        int c = ms <= 50 ? R.color.success : (ms <= 100 ? R.color.warning : R.color.md_error);
                        tvLatency.setTextColor(getColor(c));
                    }
                });
            }

            @Override public void onReceiverStats(int f, int b, long l) {}
            @Override public void onVideoSizeChanged(int w, int h) {}
            @Override public void onDisplaySizeChanged(int w, int h) {}
            @Override public void onDisplayModeChanged(int mode) {}

            @Override
            public void onError(String e) {
                runOnUiThread(() -> {
                    Toast.makeText(SenderActivity.this,
                            "投屏失败: " + e, Toast.LENGTH_LONG).show();
                    // Connection recovery reports progress through this callback.
                    // Keep the controller visible; terminal failures arrive via
                    // onDisconnected and close the activity there.
                    if (e != null && e.contains("自动重连")) {
                        tvReceiverName.setText("正在重连…");
                    }
                });
            }
        });

        // The TCP handshake can finish while this Activity is being created.
        // State listeners are not replayed, so render the current connection
        // snapshot rather than leaving a healthy session labelled “connecting”.
        com.localcast.pro.core.DeviceInfo connectedDevice =
                castManager.getConnectionManager().getRemoteDevice();
        if (castManager.getCurrentMode() == CastManager.CastMode.SENDING
                && connectedDevice != null) {
            tvReceiverName.setText("投屏至 " + connectedDevice.getDeviceName());
        }
        refreshControlButton();
        refreshDimButton();
    }

    @Override protected void onResume() {
        super.onResume();
        if (castManager != null) {
            refreshControlButton();
            refreshDimButton();
            if (awaitingAccessibilitySettings) {
                uiHandler.postDelayed(this::checkAccessibilityReturn, 700);
            }
        }
    }

    private void checkAccessibilityReturn() {
        if (!awaitingAccessibilitySettings || isFinishing() || isDestroyed()) return;
        if (RemoteControlService.isAvailable()) {
            awaitingAccessibilitySettings = false;
            refreshControlButton();
            if (castManager != null && !castManager.isRemoteControlAllowed()) {
                showSessionControlApproval();
            }
            return;
        }
        uiHandler.postDelayed(() -> {
            if (!awaitingAccessibilitySettings || isFinishing() || isDestroyed()) return;
            awaitingAccessibilitySettings = false;
            refreshControlButton();
            Toast.makeText(this,
                    "系统控制服务未保持开启。请检查系统无障碍开关；部分手机还需启用该服务的快捷方式或允许后台运行。",
                    Toast.LENGTH_LONG).show();
        }, 1700);
    }

    private void refreshControlButton() {
        btnAllowControl.setText(castManager.isRemoteControlAllowed()
                ? "停止远程控制"
                : RemoteControlService.isAvailable() ? "允许本次远程控制"
                : "开启系统控制服务");
    }

    private void refreshDimButton() {
        btnDim.setText(RemoteControlService.isLocalDisplayDimmed()
                ? "恢复主机亮度" : "主机极暗（实验）");
    }

    private void toggleLocalDisplayDim() {
        if (RemoteControlService.isLocalDisplayDimmed()) {
            RemoteControlService.restoreLocalDisplay();
            refreshDimButton();
            return;
        }
        if (!castManager.isRemoteControlAllowed()) {
            Toast.makeText(this, "请先允许本次远程控制，再开启主机极暗模式",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("主机极暗模式")
                .setMessage("将主设备亮度降到系统允许的最低值并保持采集，接收端仍可查看和控制。此功能不会锁屏，但也不是硬件断电；部分屏幕仍会微亮。接收端可随时点击“恢复主机亮度”。")
                .setPositiveButton("开启", (d, w) -> {
                    boolean enabled = RemoteControlService.enableLocalDisplayDim();
                    refreshDimButton();
                    if (!enabled) Toast.makeText(this,
                            "无法降低主设备亮度，请检查远程控制服务", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null).show();
    }

    private void toggleRemoteControl() {
        if (castManager.isRemoteControlAllowed()) {
            castManager.setRemoteControlAllowed(false);
            refreshControlButton();
            refreshDimButton();
            return;
        }
        if (castManager.getConnectionManager().getState()
                != com.localcast.pro.core.ConnectionManager.ConnectionState.CONNECTED) {
            Toast.makeText(this, "请先连接接收手机", Toast.LENGTH_LONG).show();
            return;
        }
        if (!RemoteControlService.isAvailable()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("开启本机远程控制服务")
                    .setMessage("请在系统无障碍设置中手动启用“局域投屏控制LocalCastCtrl远程控制”，返回后再次点击允许。本服务只在您批准的当前投屏会话中执行操作。")
                    .setPositiveButton("打开系统设置", (d, w) -> {
                        awaitingAccessibilitySettings = true;
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    })
                    .setNegativeButton("取消", null).show();
            return;
        }
        showSessionControlApproval();
    }

    private void showSessionControlApproval() {
        com.localcast.pro.core.DeviceInfo remote = castManager.getConnectionManager().getRemoteDevice();
        if (remote == null) {
            Toast.makeText(this, "连接已断开，请重新连接", Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("允许另一台手机操作本机？")
                .setMessage("当前连接：" + remote.getDeviceName()
                        + "。对方可点击、滑动、返回和进入主页。本次授权在停止投屏或断线后失效。请只与可信手机连接。")
                .setPositiveButton("允许本次控制", (d, w) -> {
                    castManager.setRemoteControlAllowed(true);
                    refreshControlButton();
                })
                .setNegativeButton("取消", null).show();
    }

    @Override
    @SuppressLint("MissingSuperCall") // Back intentionally backgrounds an active foreground cast.
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void toggleFullscreen() {
        boolean requestedFullscreen = !senderFullscreen;
        btnFullscreen.setEnabled(false);
        sendDisplayControlCommand(requestedFullscreen ? (byte) 1 : (byte) 2,
                requestedFullscreen);
    }

    private void sendDisplayControlCommand(byte commandType, boolean requestedFullscreen) {
        controlExecutor.execute(() -> {
            com.localcast.pro.core.StreamTransport transport =
                    castManager.getConnectionManager().getStreamTransport();
            boolean ready = transport != null && transport.isRunning();
            boolean success = ready && transport.sendFrame(
                    com.localcast.pro.core.StreamTransport.TYPE_CONTROL,
                    System.nanoTime(),
                    com.localcast.pro.core.StreamTransport.FLAG_NONE,
                    new byte[]{commandType},
                    1);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnFullscreen.setEnabled(true);
                if (success) {
                    senderFullscreen = requestedFullscreen;
                    btnFullscreen.setText(senderFullscreen
                            ? "退出接收端全屏" : "接收端全屏");
                }
                String message = !ready ? "传输层未就绪"
                        : success ? (commandType == 1 ? "接收端全屏，保留完整画面" : "已退出全屏")
                        : "发送失败，请检查连接";
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        controlExecutor.shutdownNow();
        super.onDestroy();
    }

    private boolean checkAudioPermission() {
        int permissionStatus = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        Logger.i(TAG, "Checking audio permission, current status: " +
                 (permissionStatus == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));

        if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
            Logger.i(TAG, "Requesting audio permission from user");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_AUDIO_PERMISSION);
            return false;
        }
        Logger.i(TAG, "Audio permission already granted");
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Logger.i(TAG, "✅ Audio permission GRANTED by user");
                Toast.makeText(this, "已授予录音权限,音频功能已启用", Toast.LENGTH_SHORT).show();
            } else {
                Logger.e(TAG, "❌ Audio permission DENIED by user");
                Toast.makeText(this, "未授予录音权限,音频功能不可用\n请在设置中手动开启", Toast.LENGTH_LONG).show();
            }
        }
    }
}
