package com.hasnat.remotephone;

import androidx.appcompat.app.AppCompatActivity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.hasnat.remotephone.service.NetworkClientService;

public class CallUIActivity extends AppCompatActivity {
    private TextView callStatusTextView;
    private Button buttonAnswer, buttonEndCall;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call_ui);

        callStatusTextView = findViewById(R.id.callStatusTextView);
        buttonAnswer = findViewById(R.id.buttonAnswer);
        buttonEndCall = findViewById(R.id.buttonEndCall);

        // Get the incoming number and name from the intent
        String incomingNumber = getIntent().getStringExtra(NetworkClientService.EXTRA_INCOMING_NUMBER);
        String incomingName = getIntent().getStringExtra(NetworkClientService.EXTRA_INCOMING_NAME);

        if (incomingName != null && !incomingName.equals("Unknown")) {
            callStatusTextView.setText("Incoming Call from: " + incomingName);
        } else {
            callStatusTextView.setText("Incoming Call from: " + incomingNumber);
        }

        // Set the initial state of the buttons for a ringing call.
        // Both buttons should be visible to give the user the option to answer or end.
        buttonAnswer.setVisibility(View.VISIBLE);
        buttonEndCall.setVisibility(View.VISIBLE);

        buttonAnswer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendCommand("ANSWER");
                // After answering, hide the 'Answer' button and update the UI state.
                buttonAnswer.setVisibility(View.GONE);
                callStatusTextView.setText("Call ongoing...");
            }
        });

        buttonEndCall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendCommand("END_CALL");
                finish(); // Close the activity immediately
            }
        });

        LocalBroadcastManager.getInstance(this).registerReceiver(callEndReceiver, new IntentFilter(NetworkClientService.ACTION_CLIENT_STATUS));
    }

    private void sendCommand(String command) {
        Intent intent = new Intent(NetworkClientService.ACTION_SEND_COMMAND);
        intent.putExtra(NetworkClientService.EXTRA_COMMAND, command);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private BroadcastReceiver callEndReceiver = new BroadcastReceiver() {
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