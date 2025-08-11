package com.hasnat.remotephone.service;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import java.util.List;

public class CallAccessibilityService extends AccessibilityService {

    private static final String TAG = "CallAccessibilityService";
    public static final String ACTION_CLIENT_COMMAND = "com.hasnat.remotephone.CLIENT_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    private static final String PACKAGE_DIALER = "com.android.server.telecom";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null && event.getPackageName().toString().contains("telecom")) {
                // The dialer app is in the foreground, indicating a call is happening.
                Log.d(TAG, "Dialer app is active, listening for UI changes.");
                // We'll need to send a command from here to trigger the UI on the client device
                // This is a placeholder for that logic.
                // sendCallStatusToClient("RINGING:");
            }
        }

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        // This is a simplified way to find buttons, actual implementation might need
        // to be more robust for different Android versions and device manufacturers.
        List<AccessibilityNodeInfo> answerButtons = rootNode.findAccessibilityNodeInfosByText("Answer");
        List<AccessibilityNodeInfo> endCallButtons = rootNode.findAccessibilityNodeInfosByText("End");

        Intent intent = new Intent(ACTION_CLIENT_COMMAND);
        String receivedCommand = intent.getStringExtra(EXTRA_COMMAND);

        if (receivedCommand != null) {
            if (receivedCommand.equals("ANSWER") && !answerButtons.isEmpty()) {
                answerButtons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "Simulated 'Answer' button click.");
            } else if (receivedCommand.equals("END_CALL") && !endCallButtons.isEmpty()) {
                endCallButtons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "Simulated 'End Call' button click.");
            }
        }

        // This is where you would process incoming commands from your client
        // to find and click the "Answer" or "End" buttons.
        // The commands would need to be broadcasted to this service.
    }

    @Override
    public void onInterrupt() {
        Log.e(TAG, "Accessibility Service was interrupted.");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility Service is connected!");
    }
}