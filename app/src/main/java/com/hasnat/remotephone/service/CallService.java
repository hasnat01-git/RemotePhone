package com.hasnat.remotephone.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.R;
import com.hasnat.remotephone.CallUIActivity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;

/**
 * A foreground service that runs on the host device to listen for incoming calls.
 * When a call is detected, it sends a network command to a client device
 * to trigger the remote call UI.
 */
public class CallService extends Service {

    private static final String TAG = "CallService";
    public static final String ACTION_INCOMING_CALL = "com.hasnat.remotephone.ACTION_INCOMING_CALL";
    public static final String EXTRA_CALL_NUMBER = "call_number";

    private static final String NOTIFICATION_CHANNEL_ID = "RemotePhoneChannel";
    // NOTE: This IP address is hardcoded and may need to be dynamically determined.
    // This assumes the host and client are on the same local network.
    private static final String CLIENT_IP_ADDRESS = "192.168.43.1"; // The client phone's IP
    private static final int CLIENT_PORT = 8081; // A new port for host-to-client messages

    /**
     * BroadcastReceiver to handle incoming call events.
     * It listens for an intent with ACTION_INCOMING_CALL.
     */
    private final BroadcastReceiver callEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_INCOMING_CALL.equals(action)) {
                String number = intent.getStringExtra(EXTRA_CALL_NUMBER);
                handleIncomingCall(number);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Register the broadcast receiver to listen for our custom intent
        LocalBroadcastManager.getInstance(this).registerReceiver(
                callEventReceiver, new IntentFilter(ACTION_INCOMING_CALL));
        // Start the service in the foreground to prevent it from being killed by the OS
        startForegroundServiceNotification();
    }

    /**
     * Handles the incoming call event by sending call information to the client.
     *
     * @param number The phone number of the incoming call.
     */
    private void handleIncomingCall(String number) {
        Log.d(TAG, "Incoming call detected, sending to clients: " + number);
        // Send the call info to the client over the network
        sendCallInfoToClient(number);
    }

    /**
     * Sends the incoming call details to the client device over a TCP socket.
     * This runs on a separate thread to avoid blocking the main UI thread.
     *
     * @param number The incoming phone number.
     */
    private void sendCallInfoToClient(String number) {
        new Thread(() -> {
            try (Socket socket = new Socket(CLIENT_IP_ADDRESS, CLIENT_PORT)) {
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                writer.write("INCOMING_CALL:" + number);
                writer.newLine();
                writer.flush();
                Log.d(TAG, "Call info sent to client.");
            } catch (IOException e) {
                Log.e(TAG, "Failed to send call info to client", e);
            }
        }).start();
    }

    /**
     * Creates and starts a foreground notification for the service.
     */
    private void startForegroundServiceNotification() {
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, CallUIActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("RemotePhone Host Service")
                .setContentText("Host is running and ready for remote call control.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(1, notification);
    }

    /**
     * Creates a notification channel for Android 8.0 (Oreo) and higher.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "RemotePhone Call Control Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(callEventReceiver);
        super.onDestroy();
    }
}
