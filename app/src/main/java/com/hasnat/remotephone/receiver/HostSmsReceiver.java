package com.hasnat.remotephone.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

public class HostSmsReceiver extends BroadcastReceiver {

    private static final String TAG = "HostSmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            Log.d(TAG, "SMS Received");

            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus != null) {
                    for (Object pdu : pdus) {
                        SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) pdu);
                        String sender = smsMessage.getDisplayOriginatingAddress();
                        String messageBody = smsMessage.getDisplayMessageBody();

                        Log.d(TAG, "Sender: " + sender);
                        Log.d(TAG, "Message: " + messageBody);

                        // TODO: Implement your logic here to process the SMS.
                        // You can send this SMS data to the client device.
                    }
                }
            }
        }
    }
}