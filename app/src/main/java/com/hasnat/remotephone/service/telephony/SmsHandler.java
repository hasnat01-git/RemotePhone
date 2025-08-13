package com.hasnat.remotephone.service.telephony;

import android.util.Log;

import com.hasnat.remotephone.service.BroadcastManager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmsHandler {
    private static final String TAG = "SmsHandler";
    private final BroadcastManager broadcastManager;

    public SmsHandler(BroadcastManager broadcastManager) {
        this.broadcastManager = broadcastManager;
    }

    public void handleIncomingSms(String sender, String messageBody) {
        String otp = extractOtp(messageBody);
        if (otp != null) {
            String command = "OTP:" + otp;
            Log.d(TAG, "OTP received and forwarding: " + otp);
            // This needs to be broadcast to clients, likely via the main service
            broadcastManager.sendHostStatus("Host: OTP received from " + sender + " and forwarded.");
        }
    }

    private String extractOtp(String messageBody) {
        Pattern p = Pattern.compile("\\b\\d{4,6}\\b");
        Matcher m = p.matcher(messageBody);
        if (m.find()) {
            return m.group();
        }
        return null;
    }
}