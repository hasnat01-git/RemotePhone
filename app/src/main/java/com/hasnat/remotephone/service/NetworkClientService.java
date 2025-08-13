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
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.MainActivity;
import com.hasnat.remotephone.OngoingCallActivity;
import com.hasnat.remotephone.R;
import com.hasnat.remotephone.IncomingCallActivity;
import com.hasnat.remotephone.utils.WifiUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Service to connect to a Remote Phone Host and manage call signaling and audio streaming.
 * It handles connecting to the host, sending commands, and processing messages from the host.
 */
public class NetworkClientService extends Service {

    public static final String ACTION_HOST_CALL_STARTED = "" ;
    private static final String TAG = "NetworkClientService";
    private static final int TCP_SERVER_PORT = 8080;
    private static final String CHANNEL_ID = "NetworkClientServiceChannel";
    private static final int NOTIFICATION_ID = 2;
    public static String currentOutgoingNumber;

    public static final String ACTION_CLIENT_STATUS = "com.hasnat.remotephone.CLIENT_STATUS";
    public static final String EXTRA_STATUS_MESSAGE = "status_message";
    public static final String ACTION_SEND_COMMAND = "com.hasnat.remotephone.ACTION_SEND_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_INCOMING_NUMBER = "incoming_number";
    public static final String EXTRA_INCOMING_NAME = "incoming_name"; // Updated constant
    public static final String EXTRA_HOST_IP = "host_ip";
    public static final String ACTION_HOST_CONNECTION_UPDATE = "com.hasnat.remotephone.ACTION_HOST_CONNECTION_UPDATE";
    public static final String EXTRA_CONNECTED_HOST_IP = "connected_host_ip";
    public static String lastDialedNumber;
    public static String lastDialedName;
    public static String lastIncomingNumber;
    public static String lastIncomingName;

    private ExecutorService clientExecutor;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private Thread clientThread;
    private String serverIpAddress;
    private volatile boolean isStreaming = false;

    // Static variables to hold the current call information, for both incoming and outgoing
    public static String currentCallNumber;
    public static String currentCallName;

    private static final int AUDIO_SERVER_PORT = 8081;
    private static final int AUDIO_CONNECTION_RETRY_COUNT = 5;
    private static final long AUDIO_CONNECTION_RETRY_DELAY_MS = 1000;

    private Socket audioSocket;
    private ExecutorService audioStreamingExecutor;
    private Future<?> clientMicStreamFuture;
    private Future<?> hostMicStreamFuture;

    /**
     * BroadcastReceiver to listen for commands from the UI.
     */
    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_SEND_COMMAND.equals(intent.getAction())) {
                String command = intent.getStringExtra(EXTRA_COMMAND);
                if (command != null) {
                    Log.d(TAG, "Received command from UI: " + command);
                    sendCommand(command);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        clientExecutor = Executors.newSingleThreadExecutor();
        audioStreamingExecutor = Executors.newFixedThreadPool(2);
        startForeground(NOTIFICATION_ID, createNotification("Remote Phone Client", "Client service is running."));
        LocalBroadcastManager.getInstance(this).registerReceiver(commandReceiver, new IntentFilter(ACTION_SEND_COMMAND));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_HOST_IP)) {
            serverIpAddress = intent.getStringExtra(EXTRA_HOST_IP);
            if (serverIpAddress != null) {
                if (clientThread == null || !clientThread.isAlive()) {
                    clientThread = new Thread(new TcpClientRunnable(serverIpAddress));
                    clientThread.start();
                    updateNotification("Remote Phone Client", "Connecting to " + serverIpAddress);
                    sendClientStatus("Client: Attempting to connect to " + serverIpAddress);
                }
            } else {
                updateNotification("Remote Phone Client", "Error: No server IP provided.");
                sendClientStatus("Client: Error - No server IP provided.");
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(commandReceiver);
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
        }
        if (audioStreamingExecutor != null) {
            audioStreamingExecutor.shutdownNow();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Creates a notification channel for Android O and above.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Remote Phone Client Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    /**
     * Creates a persistent notification for the foreground service.
     */
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

    /**
     * Updates the foreground service notification.
     */
    private void updateNotification(String title, String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(title, text));
        }
    }

    /**
     * Sends a status message via LocalBroadcastManager.
     */
    private void sendClientStatus(String message) {
        Intent intent = new Intent(ACTION_CLIENT_STATUS);
        intent.putExtra(EXTRA_STATUS_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /**
     * Sends a host connection update via LocalBroadcastManager.
     */
    private void sendHostConnectionUpdate(String hostIp) {
        Intent intent = new Intent(ACTION_HOST_CONNECTION_UPDATE);
        intent.putExtra(EXTRA_CONNECTED_HOST_IP, hostIp);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /**
     * Sends a command to the connected host.
     * @param command The command to send.
     */
    private void sendCommand(String command) {
        clientExecutor.execute(() -> {
            if (writer != null && !socket.isClosed() && socket.isConnected()) {
                writer.println(command);
                if (writer.checkError()) {
                    Log.e(TAG, "Error sending command to server.");
                    sendClientStatus("Client: Error sending command.");
                }
            } else {
                Log.e(TAG, "Client not connected. Cannot send command.");
                sendClientStatus("Client: Not connected to a server.");
            }
        });
    }

    /**
     * Disconnects from the host and cleans up resources.
     */
    private void disconnect() {
        stopAudioBridge();
        try {
            if (socket != null) {
                socket.close();
            }
            if (audioSocket != null) {
                audioSocket.close();
            }
            if (reader != null) {
                reader.close();
            }
            if (writer != null) {
                writer.close();
            }
            if (clientThread != null) {
                clientThread.interrupt();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing client resources", e);
        } finally {
            socket = null;
            audioSocket = null; // Also set audioSocket to null
            reader = null;
            writer = null;
            sendClientStatus("Client: Disconnected from server.");
            updateNotification("Remote Phone Client", "Disconnected.");
            sendHostConnectionUpdate(null);
        }
    }

    /**
     * Runnable for managing the TCP client connection in a separate thread.
     */
    class TcpClientRunnable implements Runnable {
        private final String ipAddress;

        public TcpClientRunnable(String ipAddress) {
            this.ipAddress = ipAddress;
        }

        @Override
        public void run() {
            Log.d(TAG, "TcpClientRunnable is starting...");
            try {
                socket = new Socket(ipAddress, TCP_SERVER_PORT);
                writer = new PrintWriter(socket.getOutputStream(), true);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                Log.d(TAG, "Connected to server: " + ipAddress);
                updateNotification("Remote Phone Client", "Connected to " + ipAddress);
                sendClientStatus("Client: Connected to " + ipAddress);
                sendHostConnectionUpdate(ipAddress);

                String serverMessage;
                while (!Thread.currentThread().isInterrupted() && (serverMessage = reader.readLine()) != null) {
                    Log.d(TAG, "Received message from server: " + serverMessage);
                    handleServerMessage(serverMessage);
                }
                Log.d(TAG, "Disconnected cleanly from server.");
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "TCP Client socket error", e);
                    sendClientStatus("Client: Connection error - " + e.getMessage());
                }
            } finally {
                disconnect();
            }
        }
    }

    /**
     * Handles incoming messages from the host.
     * @param message The message string received from the host.
     */
    private void handleServerMessage(@NonNull String message) {
        // We've replaced the old "CALL_STARTED" message with a new,
        // more detailed one. Both answered incoming calls and outgoing calls
        // will now be handled by the "CALL_STARTED:" block.
        if (message.startsWith("RINGING:")) {
            // Host sends "RINGING:phoneNumber|contactName"
            String[] parts = message.substring("RINGING:".length()).split("\\|", 2);
            String incomingNumber = parts[0];
            String contactName = parts.length > 1 ? parts[1] : "Unknown";

            // Store call information for later use in OngoingCallActivity
            currentCallNumber = incomingNumber;
            currentCallName = contactName;

            Log.d(TAG, "Incoming call detected: " + currentCallNumber + " (" + currentCallName + ")");
            Intent callIntent = new Intent(this, IncomingCallActivity.class);
            callIntent.putExtra(EXTRA_INCOMING_NUMBER, currentCallNumber);
            callIntent.putExtra(EXTRA_INCOMING_NAME, currentCallName);
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(callIntent);

        } else if (message.startsWith("CALL_STARTED:")) {
            // This block now handles BOTH outgoing calls and answered incoming calls
            // from the host, ensuring the name is always displayed.
            String[] parts = message.substring("CALL_STARTED:".length()).split("\\|", 2);
            String number = parts[0];
            String contactName = parts.length > 1 ? parts[1] : "Unknown";

            Log.d(TAG, "Call started: " + number + " (" + contactName + ")");

            // Store call information for OngoingCallActivity
            currentCallNumber = number;
            currentCallName = contactName;

            Intent ongoingCallIntent = new Intent(this, OngoingCallActivity.class);
            ongoingCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ongoingCallIntent.putExtra(OngoingCallActivity.EXTRA_PHONE_NUMBER, currentCallNumber);
            ongoingCallIntent.putExtra(OngoingCallActivity.EXTRA_CONTACT_NAME, currentCallName);
            startActivity(ongoingCallIntent);

            // Client initiates the audio socket connection to the host.
            startAudioConnectionToServer();

        } else if (message.equals("CALL_IDLE")) {
            Log.d(TAG, "Call ended on host. Stopping audio bridge.");
            stopAudioBridge();
            sendClientStatus("Client: Call ended.");
        } else if (message.equals("START_AUDIO_BRIDGE")) {
            Log.d(TAG, "Host requested to start audio bridge. Beginning streaming.");
            // This is the final step of the handshake.
            startAudioBridgeToHost();
            sendClientStatus("Client: Audio bridge started.");
        } else if (message.startsWith("OTP:")) {
            String otpCode = message.substring("OTP:".length());
            Log.d(TAG, "OTP received from host: " + otpCode);
            sendClientStatus("Client: OTP received.");
        } else if (message.startsWith("NOTIFICATION:")) {
            String notificationContent = message.substring("NOTIFICATION:".length());
            Log.d(TAG, "Notification received from host: " + notificationContent);
            sendClientStatus("Client: Notification received.");
        } else {
            sendClientStatus("Client: " + message);
        }
    }



    // Add this new method to NetworkClientService to manage the audio connection.
    private void startAudioConnectionToServer() {
        clientExecutor.execute(() -> {
            boolean connected = false;
            for (int i = 0; i < AUDIO_CONNECTION_RETRY_COUNT; i++) {
                try {
                    // Establish the audio socket connection on the specified port.
                    audioSocket = new Socket(serverIpAddress, AUDIO_SERVER_PORT);
                    Log.d(TAG, "Audio socket connected to host on attempt " + (i + 1));
                    connected = true;
                    break;
                } catch (IOException e) {
                    Log.w(TAG, "Failed to connect audio socket to host on attempt " + (i + 1) + ". Retrying...", e);
                    try {
                        Thread.sleep(AUDIO_CONNECTION_RETRY_DELAY_MS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (connected) {
                // Send a command to the host to let it know the audio connection is ready.
                sendCommand("AUDIO_READY");
            } else {
                Log.e(TAG, "Failed to connect audio socket to host after " + AUDIO_CONNECTION_RETRY_COUNT + " attempts.");
                sendClientStatus("Client: Audio connection failed after multiple attempts.");
                // We'll keep the main connection alive, but the audio bridge won't start.
            }
        });
    }

    /**
     * Starts the bidirectional audio bridge with the host.
     */
    private void startAudioBridgeToHost() {
        if (audioSocket == null || audioSocket.isClosed() || !audioSocket.isConnected()) {
            Log.e(TAG, "Cannot start audio bridge: audio socket is not connected.");
            return;
        }

        isStreaming = true;
        Log.d(TAG, "Starting bidirectional audio bridge.");

        // Client microphone -> Host speaker (OUTGOING STREAM)
        clientMicStreamFuture = audioStreamingExecutor.submit(this::streamClientMicToHost);

        // Host microphone -> Client speaker (INCOMING STREAM)
        hostMicStreamFuture = audioStreamingExecutor.submit(this::streamHostMicToClient);
    }

    /**
     * Streams audio from the client's microphone to the host's speaker.
     */
    private void streamClientMicToHost() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted. Cannot stream mic to host.");
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        try (OutputStream out = audioSocket.getOutputStream()) {
            recorder.startRecording();
            byte[] buffer = new byte[bufferSize];
            Log.d(TAG, "Client to host audio streaming started.");
            while (isStreaming && !Thread.currentThread().isInterrupted()) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error in client mic streaming thread", e);
        } finally {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
            recorder.release();
            Log.d(TAG, "Client to host audio streaming stopped.");
        }
    }

    /**
     * Streams audio from the host's microphone to the client's speaker.
     */
    private void streamHostMicToClient() {
        int bufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack player = new AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                16000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize, AudioTrack.MODE_STREAM);

        try (InputStream in = audioSocket.getInputStream()) {
            player.play();
            byte[] buffer = new byte[bufferSize];
            Log.d(TAG, "Host to client audio streaming started.");
            while (isStreaming && !Thread.currentThread().isInterrupted()) {
                int read = in.read(buffer);
                if (read > 0) {
                    player.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error in host mic streaming thread", e);
        } finally {
            if (player.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                player.stop();
            }
            player.release();
            Log.d(TAG, "Host to client audio streaming stopped.");
        }
    }

    /**
     * Stops the audio streaming threads by setting the streaming flag to false.
     */
    private void stopAudioBridge() {
        isStreaming = false;
        Log.d(TAG, "Stopping audio bridge.");
        if (clientMicStreamFuture != null) {
            clientMicStreamFuture.cancel(true);
        }
        if (hostMicStreamFuture != null) {
            hostMicStreamFuture.cancel(true);
        }
    }
}
