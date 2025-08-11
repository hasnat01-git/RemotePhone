package com.hasnat.remotephone.service;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class NotificationListener extends NotificationListenerService {

    private static final String TAG = "NotificationListener";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Log.d(TAG, "Notification posted from: " + sbn.getPackageName());

        // Example: Log notification title/text (if available)
        if (sbn.getNotification().extras != null) {
            CharSequence title = sbn.getNotification().extras.getCharSequence("android.title");
            CharSequence text = sbn.getNotification().extras.getCharSequence("android.text");

            Log.d(TAG, "Title: " + title);
            Log.d(TAG, "Text: " + text);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        Log.d(TAG, "Notification removed from: " + sbn.getPackageName());
    }
}
