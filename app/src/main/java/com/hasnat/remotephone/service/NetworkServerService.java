package com.hasnat.remotephone.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;
import android.telephony.TelephonyManager;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.service.network.AudioServer;
import com.hasnat.remotephone.service.network.TcpServer;
import com.hasnat.remotephone.service.telephony.PhoneCallManager;
import com.hasnat.remotephone.service.telephony.SmsHandler;
import com.hasnat.remotephone.utils.WifiUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetworkServerService extends Service {
    public static final String ACTION_BROADCAST_TO_CLIENTS = "com.hasnat.remotephone.BROADCAST_TO_CLIENTS";
    public static final String EXTRA_CLIENT_MESSAGE = "client_message";
    private static final String TAG = "NetworkServerService";
    public static final String ACTION_HANDLE_SMS = "com.hasnat.remotephone.service.ACTION_HANDLE_SMS";
    public static final String ACTION_HOST_STATUS = "com.hasnat.remotephone.HOST_STATUS";
    public static final String EXTRA_STATUS_MESSAGE = "status_message";
    public static final String ACTION_CLIENT_COUNT_UPDATE = "com.hasnat.remotephone.CLIENT_COUNT_UPDATE";
    public static final String EXTRA_CLIENT_COUNT = "client_count";
    public static boolean isHostAsModemMode = false;

    private NotificationHelper notificationHelper;
    private BroadcastManager broadcastManager;
    private PhoneCallManager phoneCallManager;
    private SmsHandler smsHandler;
    private TcpServer tcpServer;
    private AudioServer audioServer;

    private ExecutorService broadcastExecutor;

    // This BroadcastReceiver listens for internal broadcasts from PhoneCallManager
    // and forwards them to all connected TCP clients.
    private final BroadcastReceiver clientBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_BROADCAST_TO_CLIENTS.equals(intent.getAction())) {
                String command = intent.getStringExtra(EXTRA_CLIENT_MESSAGE);
                if (command != null) {
                    Log.d(TAG, "Received internal broadcast to send to clients: " + command);
                    // Use a thread to send the command to all clients
                    broadcastExecutor.execute(() -> {
                        tcpServer.broadcastToClients(command);
                        if (command.equals("CALL_IDLE")) {
                            audioServer.stopAudioBridge();
                        }
                        if (command.equals("CALL_STARTED")) {
                            // This broadcast is for outgoing calls, handled in OngoingCallActivity
                        }
                    });
                }
            }
        }
    };

    private final BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HostNotificationListenerService.ACTION_SEND_NOTIFICATION.equals(intent.getAction())) {
                String packageName = intent.getStringExtra(HostNotificationListenerService.EXTRA_NOTIFICATION_PACKAGE);
                String title = intent.getStringExtra(HostNotificationListenerService.EXTRA_NOTIFICATION_TITLE);
                String text = intent.getStringExtra(HostNotificationListenerService.EXTRA_NOTIFICATION_TEXT);

                String command = "NOTIFICATION:" + packageName + "|" + title + "|" + text;
                broadcastExecutor.execute(() -> tcpServer.broadcastToClients(command));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        String hostIpAddress = WifiUtils.getLocalIpAddress();
        String initialStatus;
        if (hostIpAddress != null) {
            initialStatus = "Host: My IP is " + hostIpAddress + ". Waiting for client...";
        } else {
            initialStatus = "Host: Not connected to a network. Please enable hotspot.";
        }

        notificationHelper = new NotificationHelper(this);
        broadcastManager = new BroadcastManager(this);
        // Correctly initialize PhoneCallManager with the 3-argument constructor
        phoneCallManager = new PhoneCallManager(this, notificationHelper, broadcastManager);
        smsHandler = new SmsHandler(broadcastManager);
        tcpServer = new TcpServer(this, new CommandListener(), broadcastManager, notificationHelper);
        audioServer = new AudioServer(this);

        broadcastExecutor = Executors.newSingleThreadExecutor();

        notificationHelper.createNotificationChannel();
        startForeground(1, notificationHelper.createNotification("Remote Phone Host", initialStatus));
        broadcastManager.sendHostStatus(initialStatus);

        if (hostIpAddress != null) {
            tcpServer.startServer();
            audioServer.startServer();
            phoneCallManager.setupPhoneStateListener();
        } else {
            Log.e(TAG, "Could not get host IP address. Servers not started.");
            broadcastManager.sendHostStatus("Host: Error - No local IP found.");
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(notificationReceiver, new IntentFilter(HostNotificationListenerService.ACTION_SEND_NOTIFICATION));
        // Register the new receiver to forward messages from internal components to clients
        LocalBroadcastManager.getInstance(this).registerReceiver(clientBroadcastReceiver, new IntentFilter(ACTION_BROADCAST_TO_CLIENTS));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HANDLE_SMS.equals(intent.getAction())) {
            String sender = intent.getStringExtra("sender");
            String messageBody = intent.getStringExtra("message");
            smsHandler.handleIncomingSms(sender, messageBody);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        tcpServer.stopServer();
        audioServer.stopServer();
        phoneCallManager.stopPhoneStateListener();
        broadcastExecutor.shutdownNow();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver);
        // Unregister the client broadcast receiver on destroy
        LocalBroadcastManager.getInstance(this).unregisterReceiver(clientBroadcastReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class CommandListener implements TcpServer.IncomingCommandListener {
        @Override
        public void onCommandReceived(String command) {
            Log.d(TAG, "Command received from client: " + command);
            if ("ANSWER".equals(command)) {
                phoneCallManager.answerCall();
            } else if ("END_CALL".equals(command)) {
                phoneCallManager.endCall();
                audioServer.stopAudioBridge();
            } else if ("MUTE".equals(command)) {
                phoneCallManager.setMute(true);
            } else if ("UNMUTE".equals(command)) {
                phoneCallManager.setMute(false);
            } else if ("HOLD".equals(command)) {
                phoneCallManager.setOnHold(true);
            } else if ("RESUME".equals(command)) {
                phoneCallManager.setOnHold(false);
            } else if ("SPEAKER_ON".equals(command)) {
                phoneCallManager.setSpeaker(true);
            } else if ("SPEAKER_OFF".equals(command)) {
                phoneCallManager.setSpeaker(false);
            } else if (command.startsWith("DIAL:")) {
                String phoneNumber = command.substring(5);
                Log.d(TAG, "Client requested to dial: " + phoneNumber);

                phoneCallManager.dialWithTelecom(phoneNumber);
                broadcastManager.sendHostStatus("Host: Dialing " + phoneNumber + "...");
            } else if (command.startsWith("REMOTE_CALL:")) {
                String number = command.substring("REMOTE_CALL:".length());
                phoneCallManager.placeCall(number);
            } else if ("AUDIO_READY".equals(command)) {
                Log.d(TAG, "Client is ready for audio bridge. Starting host-side streaming.");
                audioServer.startAudioBridge();
                tcpServer.broadcastToClients("START_AUDIO_BRIDGE");
            }
        }
    }
}
