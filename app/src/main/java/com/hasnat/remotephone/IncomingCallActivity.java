package com.hasnat.remotephone;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.hasnat.remotephone.service.NetworkClientService;

public class IncomingCallActivity extends AppCompatActivity {
    private TextView callerTextView;
    private Button answerButton;
    private Button endButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        String incomingNumber = getIntent().getStringExtra(NetworkClientService.EXTRA_INCOMING_NUMBER);
        String incomingName = getIntent().getStringExtra("EXTRA_INCOMING_NAME");

        if (incomingName != null && !incomingName.isEmpty() && !"Unknown".equals(incomingName)) {
            callerTextView.setText("Incoming Call from: " + incomingName + "\n" + incomingNumber);
        } else {
            callerTextView.setText("Incoming Call from: " + incomingNumber);
        }

        answerButton.setOnClickListener(v -> {
            sendCommand("ANSWER");

            Intent callScreen = new Intent(this, OngoingCallActivity.class);
            // Correctly pass the contact name, ensuring it's not null.
            callScreen.putExtra("CONTACT_NAME", (incomingName != null && !incomingName.isEmpty() && !"Unknown".equals(incomingName)) ? incomingName : "");
            callScreen.putExtra("PHONE_NUMBER", incomingNumber);
            startActivity(callScreen);

            finish();
        });

        endButton.setOnClickListener(v -> {
            sendCommand("END_CALL");
            finish();
        });

        LocalBroadcastManager.getInstance(this).registerReceiver(
                callEndReceiver,
                new IntentFilter(NetworkClientService.ACTION_CLIENT_STATUS)
        );
    }

    private void sendCommand(String command) {
        Intent intent = new Intent(NetworkClientService.ACTION_SEND_COMMAND);
        intent.putExtra(NetworkClientService.EXTRA_COMMAND, command);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private final BroadcastReceiver callEndReceiver = new BroadcastReceiver() {
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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(callEndReceiver);
    }
}
