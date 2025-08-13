package com.hasnat.remotephone;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.hasnat.remotephone.service.NetworkClientService;

public class IncomingCallActivity extends AppCompatActivity {
    private static final String TAG = "IncomingCallActivity";

    private TextView callerTextView;
    private Button answerButton;
    private Button endButton;

    // A receiver to handle the final call status
    private final BroadcastReceiver callEndReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(NetworkClientService.EXTRA_STATUS_MESSAGE);
            if (status != null && status.contains("Call ended.")) {
                Log.d(TAG, "Received 'Call ended.' status, finishing activity.");
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Add flags to make the activity visible on the lock screen and turn on the screen
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_incoming_call);

        callerTextView = findViewById(R.id.callerTextView);
        answerButton = findViewById(R.id.answerButton);
        endButton = findViewById(R.id.endButton);

        // Use the public constant for the extra key to retrieve the name correctly
        String incomingNumber = getIntent().getStringExtra(NetworkClientService.EXTRA_INCOMING_NUMBER);
        String incomingName = getIntent().getStringExtra(NetworkClientService.EXTRA_INCOMING_NAME);

        // Display caller information, preferring the name if available
        if (incomingName != null && !incomingName.isEmpty() && !"Unknown".equals(incomingName)) {
            callerTextView.setText("Incoming Call from: " + incomingName + "\n" + incomingNumber);
        } else {
            callerTextView.setText("Incoming Call from: " + incomingNumber);
        }


        answerButton.setOnClickListener(v -> {
            // Only send the "ANSWER" command.
            // The service will handle starting the audio bridge and launching the next activity
            // when it receives the "CALL_STARTED" message from the host.
            Log.d(TAG, "Answer button clicked, sending ANSWER command to service.");
            sendCommand("ANSWER");
            // The activity will finish once the call is truly ended, as handled by the receiver.
        });

        endButton.setOnClickListener(v -> {
            // Send the "END_CALL" command and finish the activity immediately.
            Log.d(TAG, "End button clicked, sending END_CALL command.");
            sendCommand("END_CALL");
            finish();
        });

        LocalBroadcastManager.getInstance(this).registerReceiver(
                callEndReceiver,
                new IntentFilter(NetworkClientService.ACTION_CLIENT_STATUS)
        );
    }

    /**
     * Helper method to send a command to the NetworkClientService via a local broadcast.
     * @param command The command string to be sent.
     */
    private void sendCommand(String command) {
        Intent intent = new Intent(NetworkClientService.ACTION_SEND_COMMAND);
        intent.putExtra(NetworkClientService.EXTRA_COMMAND, command);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(callEndReceiver);
    }
}
