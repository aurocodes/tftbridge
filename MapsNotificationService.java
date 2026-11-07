package com.example.tftbridge;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the ongoing Google Maps navigation notification and forwards
 * (maneuver, distance) to BridgeSocket.
 *
 * IMPORTANT: Google changes the notification layout between Maps versions and locales.
 * First run: watch logcat (tag TFT_MAPS) - every Maps notification's extras are dumped.
 * Then adjust parse() to match what you see.
 */
public class MapsNotificationService extends NotificationListenerService {

    private static final String TAG = "TFT_MAPS";
    private static final String MAPS_PKG = "com.google.android.apps.maps";

    // "300 m", "1.2 km", "0,5 mi", "500 ft", "200 yd"
    private static final Pattern DISTANCE = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s?(km|m|mi|ft|yd)\\b", Pattern.CASE_INSENSITIVE);

    private String lastSent = "";

    @Override
    public void onListenerConnected() {
        Log.i(TAG, "Listener connected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!MAPS_PKG.equals(sbn.getPackageName())) return;

        Notification n = sbn.getNotification();
        Bundle e = n.extras;

        String title = cs(e.getCharSequence(Notification.EXTRA_TITLE));
        String text  = cs(e.getCharSequence(Notification.EXTRA_TEXT));
        String sub   = cs(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String big   = cs(e.getCharSequence(Notification.EXTRA_BIG_TEXT));

        // Debug dump: remove once your parser is tuned.
        Log.d(TAG, "cat=" + n.category + " flags=" + n.flags
                + " title=[" + title + "] text=[" + text + "] sub=[" + sub + "] big=[" + big + "]");

        // Only the live navigation notification is ongoing; ignore everything else from Maps.
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return;

        String[] parsed = parse(title, text, sub);   // {maneuver, distance}
        if (parsed[0] == null && parsed[1] == null) return;

        String key = parsed[0] + "|" + parsed[1];
        if (key.equals(lastSent)) return;            // de-dupe: Maps re-posts every second
        lastSent = key;

        // Hand off to the network layer (see BridgeSocket.buildNavPacket for where headers go).
        BridgeSocket.get().sendNav(parsed[0], parsed[1], title, text);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (MAPS_PKG.equals(sbn.getPackageName())
                && (sbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            lastSent = "";
            BridgeSocket.get().sendNavEnded();
        }
    }

    /**
     * Heuristic parser. Finds the first distance token, treats the remaining text as the maneuver.
     * Typical layouts seen: title="300 m", text="Turn left onto X"  OR  title="Turn left", text="300 m · 5 min".
     */
    private String[] parse(String... fields) {
        String distance = null, maneuver = null;
        for (String s : fields) {
            if (s == null || s.trim().isEmpty()) continue;
            Matcher m = DISTANCE.matcher(s);
            if (distance == null && m.find()) {
                distance = m.group(0);
                String rest = s.replace(distance, "")
                        .replaceAll("^[\\s·•,\\-–]+|[\\s·•,\\-–]+$", "").trim();
                if (maneuver == null && !rest.isEmpty()) maneuver = rest;
            } else if (maneuver == null && !m.find(0)) {
                maneuver = s.trim();
            }
        }
        return new String[]{maneuver, distance};
    }

    private static String cs(CharSequence c) { return c == null ? null : c.toString(); }
}
