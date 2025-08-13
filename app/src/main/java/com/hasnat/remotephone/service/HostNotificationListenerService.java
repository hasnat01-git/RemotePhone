package com.hasnat.remotephone.service;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.content.Intent;
import android.content.Context;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class HostNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "HostNLService";
    public static final String ACTION_SEND_NOTIFICATION = "com.hasnat.remotephone.SEND_NOTIFICATION";
    public static final String EXTRA_NOTIFICATION_PACKAGE = "notification_package";
    public static final String EXTRA_NOTIFICATION_TITLE = "notification_title";
    public static final String EXTRA_NOTIFICATION_TEXT = "notification_text";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        // Extract notification data
        String packageName = sbn.getPackageName();
        String title = sbn.getNotification().extras.getString("android.title");
        String text = sbn.getNotification().extras.getString("android.text");

        Log.d(TAG, "Notification Posted: " + packageName + ", Title: " + title + ", Text: " + text);

        // Send the notification data to the NetworkServerService
        Intent intent = new Intent(ACTION_SEND_NOTIFICATION);
        intent.putExtra(EXTRA_NOTIFICATION_PACKAGE, packageName);
        intent.putExtra(EXTRA_NOTIFICATION_TITLE, title);
        intent.putExtra(EXTRA_NOTIFICATION_TEXT, text);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // You can add logic here to remove the notification on the client
        // For simplicity, we'll only handle posting for now.
    }
}
