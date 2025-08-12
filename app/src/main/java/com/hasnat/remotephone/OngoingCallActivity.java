package com.hasnat.remotephone;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.hasnat.remotephone.service.NetworkClientService;

public class OngoingCallActivity extends AppCompatActivity {

    private TextView callInfoTextView;
    private Button muteButton, holdButton, speakerButton, endButton;
    private boolean isMuted = false;
    private boolean isOnHold = false;
    private boolean isSpeakerOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ongoing_call);

        callInfoTextView = findViewById(R.id.callInfoTextView);
        muteButton = findViewById(R.id.muteButton);
        holdButton = findViewById(R.id.holdButton);
        speakerButton = findViewById(R.id.speakerButton);
        endButton = findViewById(R.id.endButton);

        String contactName = getIntent().getStringExtra("CONTACT_NAME");
        String phoneNumber = getIntent().getStringExtra("PHONE_NUMBER");

        if (contactName != null && !contactName.isEmpty()) {
            callInfoTextView.setText(contactName + "\n" + phoneNumber);
        } else {
            callInfoTextView.setText(phoneNumber);
        }

        muteButton.setOnClickListener(v -> {
            isMuted = !isMuted;
            sendCommand(isMuted ? "MUTE" : "UNMUTE");
            muteButton.setText(isMuted ? "Unmute" : "Mute");
        });

        holdButton.setOnClickListener(v -> {
            isOnHold = !isOnHold;
            sendCommand(isOnHold ? "HOLD" : "RESUME");
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

        LocalBroadcastManager.getInstance(this).registerReceiver(
                callStatusReceiver,
                new IntentFilter(NetworkClientService.ACTION_CLIENT_STATUS)
        );
    }

    private void sendCommand(String command) {
        Intent intent = new Intent(NetworkClientService.ACTION_SEND_COMMAND);
        intent.putExtra(NetworkClientService.EXTRA_COMMAND, command);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private final BroadcastReceiver callStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(NetworkClientService.EXTRA_STATUS_MESSAGE);
            if (status != null && status.contains("Call ended.")) {
                finish();
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(callStatusReceiver);
    }
}
