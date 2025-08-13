package com.hasnat.remotephone.service;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.List;

public class CallAccessibilityService extends AccessibilityService {

    private static final String TAG = "CallAccessibilityService";
    public static final String ACTION_CLIENT_COMMAND = "com.hasnat.remotephone.CLIENT_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    private static final String PACKAGE_TELECOM = "com.android.server.telecom";
    private static final String PACKAGE_DIALER = "com.android.dialer";

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra(EXTRA_COMMAND);
            if (command != null) {
                Log.d(TAG, "Received command from client: " + command);
                executeCommand(command);
            }
        }
    };

    private void executeCommand(String command) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.e(TAG, "Root node is null. Cannot execute command.");
            return;
        }

        // Find the appropriate button based on the command
        List<AccessibilityNodeInfo> buttons = null;
        if (command.equals("ANSWER")) {
            buttons = rootNode.findAccessibilityNodeInfosByText("Answer");
            if (buttons.isEmpty()) {
                // Fallback for different UI/languages
                buttons = rootNode.findAccessibilityNodeInfosByText("Accept");
            }
        } else if (command.equals("END_CALL")) {
            buttons = rootNode.findAccessibilityNodeInfosByText("End call");
            if (buttons.isEmpty()) {
                // Fallback for different UI/languages
                buttons = rootNode.findAccessibilityNodeInfosByText("Hang up");
            }
        }

        // Click the button if found
        if (buttons != null && !buttons.isEmpty()) {
            // Find the most relevant button, or just the first one
            AccessibilityNodeInfo buttonToClick = buttons.get(0);
            buttonToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.d(TAG, "Simulated '" + command + "' button click.");
        } else {
            Log.e(TAG, "Could not find a button to perform command: " + command);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The service should only react to events from the system telecom or dialer app
        if (event.getPackageName() == null ||
                (!event.getPackageName().toString().equals(PACKAGE_TELECOM) &&
                        !event.getPackageName().toString().equals(PACKAGE_DIALER))) {
            return;
        }

        // We can use this to detect call state changes and notify the client
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // This is a good place to listen for a call screen appearing
            Log.d(TAG, "Call UI active: " + event.getPackageName());
        }
    }

    @Override
    public void onInterrupt() {
        Log.e(TAG, "Accessibility Service was interrupted.");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility Service is connected!");
        // Register the local broadcast receiver to listen for commands
        LocalBroadcastManager.getInstance(this).registerReceiver(commandReceiver, new IntentFilter(ACTION_CLIENT_COMMAND));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Unregister the receiver when the service is destroyed
        LocalBroadcastManager.getInstance(this).unregisterReceiver(commandReceiver);
        Log.i(TAG, "Accessibility Service is destroyed!");
    }
}
