package com.hasnat.remotephone.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.service.CallService;

public class CallReceiver extends BroadcastReceiver {

    private static final String TAG = "CallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (intent.getAction().equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                Log.d(TAG, "Incoming call detected. Sending broadcast to CallService.");
                Intent callServiceIntent = new Intent(CallService.ACTION_INCOMING_CALL);
                callServiceIntent.putExtra(CallService.EXTRA_CALL_NUMBER, incomingNumber);
                LocalBroadcastManager.getInstance(context).sendBroadcast(callServiceIntent);
            }
        }
    }
}