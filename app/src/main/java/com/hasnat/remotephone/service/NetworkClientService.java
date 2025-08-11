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
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.hasnat.remotephone.MainActivity;
import com.hasnat.remotephone.R;
import com.hasnat.remotephone.IncomingCallActivity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class NetworkClientService extends Service {
    public static final String EXTRA_INCOMING_NAME = "";
    private static final String TAG = "NetworkClientService";
    private static final int TCP_SERVER_PORT = 8080;
    private static final String CHANNEL_ID = "NetworkClientServiceChannel";
    public static final String ACTION_CLIENT_STATUS = "com.hasnat.remotephone.CLIENT_STATUS";
    public static final String EXTRA_STATUS_MESSAGE = "status_message";
    public static final String ACTION_SEND_COMMAND = "com.hasnat.remotephone.ACTION_SEND_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_INCOMING_NUMBER = "incoming_number";
    public static final String EXTRA_HOST_IP = "host_ip";
    public static final String ACTION_HOST_CONNECTION_UPDATE = "com.hasnat.remotephone.ACTION_HOST_CONNECTION_UPDATE";
    public static final String EXTRA_CONNECTED_HOST_IP = "connected_host_ip";
    private ExecutorService clientExecutor;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private Thread clientThread;
    private String serverIpAddress;
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
        startForeground(2, createNotification("Remote Phone Client", "Client service is running."));
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
    }
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
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
            manager.notify(2, createNotification(title, text));
        }
    }
    private void sendClientStatus(String message) {
        Intent intent = new Intent(ACTION_CLIENT_STATUS);
        intent.putExtra(EXTRA_STATUS_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    private void sendHostConnectionUpdate(String hostIp) {
        Intent intent = new Intent(ACTION_HOST_CONNECTION_UPDATE);
        intent.putExtra(EXTRA_CONNECTED_HOST_IP, hostIp);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    private void sendCommand(String command) {
        clientExecutor.execute(() -> {
            if (writer != null) {
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
    private void disconnect() {
        try {
            if (socket != null) {
                socket.close();
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
            reader = null;
            writer = null;
            sendClientStatus("Client: Disconnected from server.");
            updateNotification("Remote Phone Client", "Disconnected.");
            sendHostConnectionUpdate(null);
        }
    }
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
    private void handleServerMessage(String message) {
        if (message.startsWith("RINGING:")) {
            String incomingNumber = message.substring("RINGING:".length());
            Log.d(TAG, "Incoming call detected: " + incomingNumber);
            Intent callIntent = new Intent(this, IncomingCallActivity.class);
            callIntent.putExtra(EXTRA_INCOMING_NUMBER, incomingNumber);
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(callIntent);
        } else if (message.equals("CALL_IDLE")) {
            Log.d(TAG, "Call ended on host.");
            sendClientStatus("Client: Call ended.");
        }
    }
}