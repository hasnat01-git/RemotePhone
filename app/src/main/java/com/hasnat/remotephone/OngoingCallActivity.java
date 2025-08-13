package com.hasnat.remotephone;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.hasnat.remotephone.service.NetworkClientService;

public class OngoingCallActivity extends AppCompatActivity {

    private static final String TAG = "OngoingCallActivity";
    public static final String EXTRA_CONTACT_NAME = "com.hasnat.remotephone.CONTACT_NAME";
    public static final String EXTRA_PHONE_NUMBER = "com.hasnat.remotephone.PHONE_NUMBER";

    private TextView callInfoTextView;
    private Button muteButton, holdButton, speakerButton, endButton;
    private boolean isMuted = false;
    private boolean isOnHold = false;
    private boolean isSpeakerOn = false;

    private final BroadcastReceiver clientServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (NetworkClientService.ACTION_CLIENT_STATUS.equals(action)) {
                String status = intent.getStringExtra(NetworkClientService.EXTRA_STATUS_MESSAGE);
                if (status != null) {
                    Log.d(TAG, "Status from service: " + status);
                    Toast.makeText(OngoingCallActivity.this, status, Toast.LENGTH_SHORT).show();

                    // Check for a call ended status from the host and finish the activity
                    if (status.contains("Call ended.") || status.contains("disconnected")) {
                        finish();
                    }
                }
            } else if (NetworkClientService.ACTION_HOST_CALL_STARTED.equals(action)) {
                Log.d(TAG, "Host call started event received. Sending START_AUDIO_BRIDGE command.");
                sendCommand("START_AUDIO_BRIDGE");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ongoing_call);

        callInfoTextView = findViewById(R.id.callInfoTextView);
        muteButton = findViewById(R.id.muteButton);
        holdButton = findViewById(R.id.holdButton);
        speakerButton = findViewById(R.id.speakerButton);
        endButton = findViewById(R.id.endButton);

        String contactName = getIntent().getStringExtra(EXTRA_CONTACT_NAME);
        String phoneNumber = getIntent().getStringExtra(EXTRA_PHONE_NUMBER);

        if (contactName != null && !contactName.isEmpty() && !contactName.equals("Unknown")) {
            callInfoTextView.setText(contactName + "\n" + phoneNumber);
        } else {
            callInfoTextView.setText(phoneNumber);
        }

        muteButton.setOnClickListener(v -> {
            isMuted = !isMuted;
            sendCommand(isMuted ? "MUTE" : "UNMUTE");
            // The UI text should reflect the new state, so "Mute" becomes "Unmute" when the call is muted.
            muteButton.setText(isMuted ? "Unmute" : "Mute");
        });

        holdButton.setOnClickListener(v -> {
            isOnHold = !isOnHold;
            sendCommand(isOnHold ? "HOLD" : "UNHOLD"); // The command for resuming a call is "UNHOLD", not "RESUME"
            holdButton.setText(isOnHold ? "Resume" : "Hold");
        });

        speakerButton.setOnClickListener(v -> {
            isSpeakerOn = !isSpeakerOn;
            sendCommand(isSpeakerOn ? "SPEAKER_ON" : "SPEAKER_OFF");
            speakerButton.setText(isSpeakerOn ? "Speaker Off" : "Speaker On");
        });

        endButton.setOnClickListener(v -> {
            sendCommand("END_CALL");
            finish();
        });

        // Register the BroadcastReceiver for both client status updates and the new ACTION_HOST_CALL_STARTED event.
        IntentFilter filter = new IntentFilter(NetworkClientService.ACTION_CLIENT_STATUS);
        filter.addAction(NetworkClientService.ACTION_HOST_CALL_STARTED);
        LocalBroadcastManager.getInstance(this).registerReceiver(clientServiceReceiver, filter);
    }

    private void sendCommand(String command) {
        Intent intent = new Intent(NetworkClientService.ACTION_SEND_COMMAND);
        intent.putExtra(NetworkClientService.EXTRA_COMMAND, command);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(clientServiceReceiver);
    }
}