package com.hasnat.remotephone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import com.hasnat.remotephone.service.NetworkServerService;
import com.hasnat.remotephone.service.NetworkClientService;

public class HostSmsReceiver extends BroadcastReceiver {

    public static final String ACTION_SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";
    private static final String TAG = "HostSmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "SMS Received broadcast");
        if (intent.getAction() != null && intent.getAction().equals(ACTION_SMS_RECEIVED)) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus != null) {
                    for (Object pdu : pdus) {
                        SmsMessage smsMessage;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            String format = bundle.getString("format");
                            smsMessage = SmsMessage.createFromPdu((byte[]) pdu, format);
                        } else {
                            smsMessage = SmsMessage.createFromPdu((byte[]) pdu);
                        }

                        String sender = smsMessage.getDisplayOriginatingAddress();
                        String messageBody = smsMessage.getMessageBody();

                        Log.d(TAG, "SMS from: " + sender + ", Body: " + messageBody);

                        // Pass the message to a service to handle OTP extraction and forwarding
                        Intent serviceIntent = new Intent(context, NetworkServerService.class);
                        serviceIntent.setAction(NetworkServerService.ACTION_HANDLE_SMS);
                        serviceIntent.putExtra("sender", sender);
                        serviceIntent.putExtra("message", messageBody);
                        context.startService(serviceIntent);
                    }
                }
            }
        }
    }
}