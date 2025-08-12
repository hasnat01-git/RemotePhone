package com.hasnat.remotephone.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.MainActivity;
import com.hasnat.remotephone.R;
import com.hasnat.remotephone.utils.WifiUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
    private boolean isStreaming = false;
    private Socket clientSocket;
    public static final String ACTION_HOST_STATUS = "com.hasnat.remotephone.HOST_STATUS";
    public static final String EXTRA_STATUS_MESSAGE = "status_message";
    public static final String ACTION_HANDLE_SMS = "com.hasnat.remotephone.service.ACTION_HANDLE_SMS";
    public static final String EXTRA_OTP_CODE = "otp_code";
    public static final String ACTION_CLIENT_COUNT_UPDATE = "com.hasnat.remotephone.CLIENT_COUNT_UPDATE";
    public static final String EXTRA_CLIENT_COUNT = "client_count";
    public static final String ACTION_VOIP_READY = "com.hasnat.remotephone.ACTION_VOIP_READY";
    private boolean wasInCall = false;
    private ServerSocket serverSocket;
    private Thread tcpServerThread;
    private String hostIpAddress;
    private ExecutorService clientExecutor;
    private PhoneStateListener phoneStateListener;
    private final List<PrintWriter> clientWriters = new ArrayList<>();
    private ExecutorService broadcastExecutor;
    private TelecomManager telecomManager;
    private TelephonyManager telephonyManager;
    public static boolean isHostAsModemMode = false;


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

    private String getContactName(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return "Unknown";
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted. Cannot look up contact name.");
            return "Unknown";
        }

        String contactName = "Unknown";
        Cursor cursor = null;

        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));

            cursor = context.getContentResolver().query(
                    uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null
            );

            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME);
                if (nameIndex != -1) {
                    contactName = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching contact name", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return contactName;
    }

    // Updated setupPhoneStateListener()
    private void setupPhoneStateListener() {
        if (telephonyManager != null) {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    switch (state) {
                        case TelephonyManager.CALL_STATE_RINGING:
                            wasInCall = true;
                            String number = (phoneNumber != null && !phoneNumber.trim().isEmpty()) ? phoneNumber : "Unknown";
                            // 💡 Get contact name for incoming call
                            String contactName = getContactName(NetworkServerService.this, number);
                            Log.d(TAG, "Incoming call detected from " + number + " (" + contactName + ")");

                            // 💡 Send both name and number to the client
                            broadcastExecutor.execute(() -> broadcastToClients("RINGING:" + number + "|" + (contactName != null ? contactName : "Unknown")));
                            updateNotification("Incoming Call", "From " + (contactName != null ? contactName : number));
                            sendHostStatus("Host: Incoming call from " + (contactName != null ? contactName : number));
                            break;

                        case TelephonyManager.CALL_STATE_OFFHOOK:
                            // This state is entered when a call is either outgoing or has been answered.
                            wasInCall = true;
                            Log.d(TAG, "Call is now OFFHOOK (outgoing or answered).");
                            // *** Add this crucial line to inform the client ***
                            broadcastExecutor.execute(() -> broadcastToClients("CALL_STARTED"));
                            break;

                        case TelephonyManager.CALL_STATE_IDLE:
                            if (wasInCall) {
                                Log.d(TAG, "Call ended");
                                broadcastExecutor.execute(() -> broadcastToClients("CALL_IDLE"));
                                String status = "Host: My IP is " + hostIpAddress + ". Waiting for client...";
                                updateNotification("Remote Phone Host", status);
                                sendHostStatus(status);
                                wasInCall = false;
                            }
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
        } else if ("MUTE".equals(command)) {
            setMute(true);
        } else if ("UNMUTE".equals(command)) {
            setMute(false);
        } else if ("HOLD".equals(command)) {
            setOnHold(true);
        } else if ("RESUME".equals(command)) {
            setOnHold(false);
        } else if ("SPEAKER_ON".equals(command)) {
            setSpeaker(true);
        } else if ("SPEAKER_OFF".equals(command)) {
            setSpeaker(false);
        } else if (command.startsWith("DIAL:")) {
            String phoneNumber = command.substring(5);
            dialWithTelecom(phoneNumber);
        } else if (command.startsWith("REMOTE_CALL:")) {
            String number = command.substring("REMOTE_CALL:".length());
            placeCall(number);
            startAudioBridge();
        }
    }

    private void setMute(boolean muted) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMicrophoneMute(muted);
            Log.d(TAG, "Microphone mute is now " + (muted ? "ON" : "OFF"));
        } else {
            Log.e(TAG, "AudioManager is null, cannot change mute status.");
        }
    }

    private void setSpeaker(boolean enabled) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(enabled);
            Log.d(TAG, "Speakerphone is now " + (enabled ? "ON" : "OFF"));
        } else {
            Log.e(TAG, "AudioManager is null, cannot change speaker status.");
        }
    }

    private void setOnHold(boolean onHold) {
        if (onHold) {
            Log.d(TAG, "Call put on hold.");
            // TODO: Implement logic to put the call on hold via Telecom API.
            // This usually involves finding the active call and calling connection.setOnHold().
        } else {
            Log.d(TAG, "Call resumed from hold.");
            // TODO: Implement logic to resume the call via Telecom API.
            // This usually involves finding the held call and calling connection.setActive().
        }
    }


    private void placeCall(String number) {
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + number));
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            startActivity(callIntent);
        } else {
            Log.e(TAG, "CALL_PHONE permission not granted");
        }
    }


    private void startAudioBridge() {
        isStreaming = true;

        new Thread(() -> {
            int bufferSize = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            AudioRecord recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);

            recorder.startRecording();
            byte[] buffer = new byte[bufferSize];

            try {
                OutputStream out = clientSocket.getOutputStream();
                while (isStreaming) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                recorder.stop();
                recorder.release();
            }
        }).start();

        new Thread(() -> {
            int bufferSize = AudioTrack.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            AudioTrack player = new AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    16000, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize, AudioTrack.MODE_STREAM);

            player.play();
            byte[] buffer = new byte[bufferSize];

            try {
                InputStream in = clientSocket.getInputStream();
                while (isStreaming) {
                    int read = in.read(buffer);
                    if (read > 0) {
                        player.write(buffer, 0, read);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                player.stop();
                player.release();
            }
        }).start();
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

    // New, correct method to place a call using the Telecom API
    private void dialWithTelecom(String phoneNumber) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (telecomManager != null) {
                Uri uri = Uri.fromParts("tel", phoneNumber, null);
                Bundle extras = new Bundle();

                // 💡 Get contact name and add it to the extras
                String contactName = getContactName(this, phoneNumber);
                extras.putString("CONTACT_NAME", contactName);

                PhoneAccountHandle phoneAccountHandle = new PhoneAccountHandle(
                        new ComponentName(this, MyConnectionService.class), "RemotePhone");

                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);

                Log.d(TAG, "Placing call via TelecomManager for number: " + phoneNumber);
                telecomManager.placeCall(uri, extras);
                sendHostStatus("Host: Placing call for " + (contactName != null ? contactName : phoneNumber));
            } else {
                Log.e(TAG, "TelecomManager is null, cannot place call.");
                sendHostStatus("Host: Error placing call.");
            }
        } else {
            Log.e(TAG, "CALL_PHONE permission not granted.");
            sendHostStatus("Host: Permission to call is missing.");
        }
    }
}