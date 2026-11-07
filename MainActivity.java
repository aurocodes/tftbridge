package com.example.tftbridge;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal launcher. Flow:
 *   1. runtime permissions (POST_NOTIFICATIONS, BLUETOOTH_CONNECT)
 *   2. Notification-listener access (opens system settings, user toggles it)
 *   3. MediaProjection consent dialog
 *   4. start BridgeService with the consent token, then finish()
 *
 * NOTE: extends android.app.Activity's ComponentActivity via AppCompat/AndroidX.
 * If you don't want AndroidX, replace the launchers with requestPermissions()/startActivityForResult().
 */
public class MainActivity extends androidx.activity.ComponentActivity {

    private boolean permsAsked = false;
    private boolean sentToListenerSettings = false;
    private boolean projectionRequested = false;

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> advance());

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent svc = new Intent(this, BridgeService.class);
                    svc.putExtra(BridgeService.EXTRA_RESULT_CODE, result.getResultCode());
                    svc.putExtra(BridgeService.EXTRA_RESULT_DATA, result.getData());
                    startForegroundService(svc);
                    finish(); // UI is done; the service keeps running.
                } else {
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No layout on purpose.
    }

    @Override
    protected void onResume() {
        super.onResume();
        advance(); // also re-runs when the user returns from the Notification Access settings page
    }

    /** Idempotent state machine: each call moves to the next missing step. */
    private void advance() {
        if (projectionRequested) return;

        // Step 1: runtime permissions
        if (!permsAsked) {
            permsAsked = true;
            List<String> need = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (!need.isEmpty()) {
                permLauncher.launch(need.toArray(new String[0]));
                return;
            }
        }

        // Step 2: notification listener access
        if (!isListenerEnabled()) {
            if (!sentToListenerSettings) {
                sentToListenerSettings = true;
                Toast.makeText(this, "Enable \"TFT Bridge Maps Listener\"", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
            return;
        }

        // Step 3: MediaProjection. On Android 14+ pick "Entire screen", NOT "A single app",
        // otherwise you only capture this app's own window (blank).
        projectionRequested = true;
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projectionLauncher.launch(mpm.createScreenCaptureIntent());
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        ComponentName mine = new ComponentName(this, MapsNotificationService.class);
        for (String s : flat.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (cn != null && cn.equals(mine)) return true;
        }
        return false;
    }
}
