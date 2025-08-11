package com.hasnat.remotephone.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.MainActivity;
import com.hasnat.remotephone.R;
import com.hasnat.remotephone.utils.WifiUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NetworkServerService extends Service {

    private static final String TAG = "NetworkServerService";
    private static final int TCP_SERVER_PORT = 8080;
    private static final String CHANNEL_ID = "NetworkServerServiceChannel";

    public static final String ACTION_HOST_STATUS = "com.hasnat.remotephone.HOST_STATUS";
    public static final String EXTRA_STATUS_MESSAGE = "status_message";
    public static final String ACTION_HANDLE_SMS = "com.hasnat.remotephone.service.ACTION_HANDLE_SMS";
    public static final String EXTRA_OTP_CODE = "otp_code";
    public static final String ACTION_CLIENT_COUNT_UPDATE = "com.hasnat.remotephone.CLIENT_COUNT_UPDATE";
    public static final String EXTRA_CLIENT_COUNT = "client_count";
    public static final String ACTION_VOIP_READY = "com.hasnat.remotephone.ACTION_VOIP_READY";

    private ServerSocket serverSocket;
    private Thread tcpServerThread;
    private String hostIpAddress;
    private ExecutorService clientExecutor;
    private PhoneStateListener phoneStateListener;
    private final List<PrintWriter> clientWriters = new ArrayList<>();
    private ExecutorService broadcastExecutor;
    private TelecomManager telecomManager;
    private TelephonyManager telephonyManager;

    private final BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HostNotificationListenerService.ACTION_SEND_NOTIFICATION.equals(intent.getAction())) {
                String packageName = intent.getStringExtra(HostNotificationListenerService.EXTRA_NOTIFICATION_PACKAGE);
                String title = intent.getStringExtra(HostNotificationListenerService.EXTRA_NOTIFICATION_TITLE);
                String text = intent.getStringExtra(HostNotificationListenerService.EXTRA_NOTIFICATION_TEXT);

                if (packageName != null) {
                    if (packageName.equals("android") && (title != null && (title.contains("Battery") || title.contains("Charging") || title.contains("Hotspot")))) {
                        Log.d(TAG, "Filtered out a system notification: " + title);
                        return;
                    }
                    if (packageName.equals("com.android.settings")) {
                        Log.d(TAG, "Filtered out a Settings notification.");
                        return;
                    }
                    if (packageName.equals("com.android.systemui")) {
                        Log.d(TAG, "Filtered out a System UI notification.");
                        return;
                    }
                }

                PackageManager pm = context.getPackageManager();
                String appName = "Unknown App";
                try {
                    appName = (String) pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "Could not find app name for package: " + packageName);
                }

                String command = "NOTIFICATION:" + appName + "|" + title + "|" + text;
                broadcastExecutor.execute(() -> broadcastToClients(command));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        clientExecutor = Executors.newCachedThreadPool();
        broadcastExecutor = Executors.newSingleThreadExecutor();
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

        hostIpAddress = WifiUtils.getLocalIpAddress();
        String initialStatus;
        if (hostIpAddress != null) {
            initialStatus = "Host: My IP is " + hostIpAddress + ". Waiting for client...";
        } else {
            initialStatus = "Host: Not connected to a network. Please enable hotspot.";
        }

        startForeground(1, createNotification("Remote Phone Host", initialStatus));
        sendHostStatus(initialStatus);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        }

        if (hostIpAddress != null) {
            tcpServerThread = new Thread(new TcpServerRunnable());
            tcpServerThread.start();
            setupPhoneStateListener();
        } else {
            Log.e(TAG, "Could not get host IP address. TCP server not started.");
            sendHostStatus("Host: Error - No local IP found.");
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(notificationReceiver, new IntentFilter(HostNotificationListenerService.ACTION_SEND_NOTIFICATION));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HANDLE_SMS.equals(intent.getAction())) {
            String sender = intent.getStringExtra("sender");
            String messageBody = intent.getStringExtra("message");
            handleIncomingSms(sender, messageBody);
        }
        return START_STICKY;
    }

    private void handleIncomingSms(String sender, String messageBody) {
        String otp = extractOtp(messageBody);
        if (otp != null) {
            String command = "OTP:" + otp;
            Log.d(TAG, "OTP received and forwarding: " + otp);
            broadcastExecutor.execute(() -> broadcastToClients(command));
            sendHostStatus("Host: OTP received from " + sender + " and forwarded.");
        }
    }

    private String extractOtp(String messageBody) {
        Pattern p = Pattern.compile("\\b\\d{4,6}\\b");
        Matcher m = p.matcher(messageBody);
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    private void setupPhoneStateListener() {
        if (telephonyManager != null) {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    switch (state) {
                        case TelephonyManager.CALL_STATE_RINGING:
                            String number = (phoneNumber != null && !phoneNumber.isEmpty()) ? phoneNumber : "Unknown";
                            Log.d(TAG, "Incoming call detected from " + number);
                            broadcastExecutor.execute(() -> broadcastToClients("RINGING:" + number));
                            updateNotification("Incoming Call", "From " + number);
                            sendHostStatus("Host: Incoming call from " + number);
                            break;
                        case TelephonyManager.CALL_STATE_IDLE:
                            Log.d(TAG, "Call ended or idle");
                            broadcastExecutor.execute(() -> broadcastToClients("CALL_IDLE"));
                            String status = "Host: My IP is " + hostIpAddress + ". Waiting for client...";
                            updateNotification("Remote Phone Host", status);
                            sendHostStatus(status);
                            stopVoIPServer();
                            break;
                    }
                }
            };
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        } else {
            Log.e(TAG, "TelephonyManager is null. This device may not have phone capabilities.");
            sendHostStatus("Host: Error - No phone capabilities.");
        }
    }

    private void broadcastToClients(String message) {
        synchronized (clientWriters) {
            List<PrintWriter> disconnectedClients = new ArrayList<>();
            for (PrintWriter writer : clientWriters) {
                if (writer.checkError()) {
                    disconnectedClients.add(writer);
                    continue;
                }
                writer.println(message);
            }
            clientWriters.removeAll(disconnectedClients);
        }
    }

    private void sendCommandToClient(String command) {
        broadcastExecutor.execute(() -> broadcastToClients(command));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (tcpServerThread != null) {
            tcpServerThread.interrupt();
        }
        clientExecutor.shutdownNow();
        broadcastExecutor.shutdownNow();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket", e);
        }
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver);
    }

    private void startVoIPServer() {
        Log.d(TAG, "Starting VoIP server session.");
        sendCommandToClient("STATUS:VoIP server started.");
    }

    private void stopVoIPServer() {
        Log.d(TAG, "Stopping VoIP server session.");
    }

    private void handleIncomingCommand(String command) {
        Log.d(TAG, "handleIncomingCommand: Processing command -> " + command);
        if ("ANSWER".equals(command)) {
            answerCall();
        } else if ("END_CALL".equals(command)) {
            endCall();
        } else if (command.startsWith("DIAL:")) {
            String phoneNumber = command.substring(5);
            dialNumber(phoneNumber);
        }
    }

    private void answerCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (telecomManager != null) {
                    Log.d(TAG, "Attempting to accept ringing call via TelecomManager.");
                    telecomManager.acceptRingingCall();
                    startVoIPServer();
                }
            } else {
                Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission.");
                sendCommandToClient("STATUS:Permission denied to answer call.");
            }
        } else {
            try {
                Intent answerIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                answerIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK));
                sendOrderedBroadcast(answerIntent, "android.permission.CALL_PRIVILEGED");

                answerIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                answerIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK));
                sendOrderedBroadcast(answerIntent, "android.permission.CALL_PRIVILEGED");
                startVoIPServer();
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException while answering call", e);
                sendCommandToClient("STATUS:Permission denied to answer call.");
            }
        }
    }

    private void endCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (telecomManager != null) {
                    Log.d(TAG, "Attempting to end current call via TelecomManager.");
                    telecomManager.endCall();
                    stopVoIPServer();
                }
            } else {
                Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission to end call.");
                sendCommandToClient("STATUS:Permission denied to end call.");
            }
        } else {
            try {
                Intent endCallIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                endCallIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENDCALL));
                sendOrderedBroadcast(endCallIntent, null);

                endCallIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                endCallIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENDCALL));
                sendOrderedBroadcast(endCallIntent, null);
                stopVoIPServer();
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException while ending call", e);
                sendCommandToClient("STATUS:Permission denied to end call.");
            }
        }
    }

    private void dialNumber(String phoneNumber) {
        Log.d(TAG, "dialNumber: Checking permissions for call.");
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CALL_PHONE permission not granted. Cannot dial from service.");
            sendHostStatus("Host: Permission to call is missing.");
            return;
        }

        try {
            Log.d(TAG, "Host is attempting to dial: " + phoneNumber);
            Intent dialIntent = new Intent(Intent.ACTION_CALL);
            dialIntent.setData(Uri.parse("tel:" + phoneNumber));
            dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(dialIntent);
            sendHostStatus("Host: Dialing " + phoneNumber);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while trying to dial.", e);
            sendHostStatus("Host: Security error while trying to call.");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification(String title, String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(1, createNotification(title, text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Remote Phone Server Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void sendHostStatus(String message) {
        Intent intent = new Intent(ACTION_HOST_STATUS);
        intent.putExtra(EXTRA_STATUS_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void sendClientCountUpdate() {
        Intent intent = new Intent(ACTION_CLIENT_COUNT_UPDATE);
        synchronized (clientWriters) {
            intent.putExtra(EXTRA_CLIENT_COUNT, clientWriters.size());
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    class TcpServerRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "TcpServerRunnable is starting...");
            try {
                serverSocket = new ServerSocket(TCP_SERVER_PORT);
                Log.d(TAG, "TCP Server started on port " + TCP_SERVER_PORT);
                while (!Thread.currentThread().isInterrupted()) {
                    Log.d(TAG, "Server socket waiting for a new client connection...");
                    Socket clientSocket = serverSocket.accept();
                    Log.d(TAG, "Client connected: " + clientSocket.getInetAddress());

                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                    synchronized (clientWriters) {
                        clientWriters.add(out);
                        sendClientCountUpdate();
                    }

                    updateNotification("Remote Phone Host", "Client connected from " + clientSocket.getInetAddress().getHostAddress());
                    sendHostStatus("Host: Client connected from " + clientSocket.getInetAddress().getHostAddress());

                    clientExecutor.execute(new ClientHandler(clientSocket, out));
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "TCP Server socket error", e);
                    sendHostStatus("Host: Server error - " + e.getMessage());
                }
            }
        }
    }

    class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final PrintWriter clientWriter;

        public ClientHandler(Socket socket, PrintWriter writer) {
            this.clientSocket = socket;
            this.clientWriter = writer;
        }

        @Override
        public void run() {
            Log.d(TAG, "ClientHandler thread started for client: " + clientSocket.getInetAddress());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                String command;
                Log.d(TAG, "ClientHandler is now listening for commands...");
                while ((command = reader.readLine()) != null) {
                    Log.d(TAG, "Received command from " + clientSocket.getInetAddress() + ": " + command);
                    handleIncomingCommand(command);
                }
                Log.d(TAG, "Client disconnected cleanly.");
            } catch (IOException e) {
                Log.e(TAG, "Client disconnected or I/O error: " + e.getMessage(), e);
            } finally {
                Log.d(TAG, "ClientHandler closing resources for client: " + clientSocket.getInetAddress());
                try {
                    clientSocket.close();
                    synchronized (clientWriters) {
                        if (clientWriter != null) {
                            clientWriters.remove(clientWriter);
                            sendClientCountUpdate();
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing client socket", e);
                }
            }
        }
    }
}
