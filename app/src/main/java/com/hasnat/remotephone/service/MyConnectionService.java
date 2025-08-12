package com.hasnat.remotephone.service;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import com.hasnat.remotephone.IncomingCallActivity;
import com.hasnat.remotephone.OngoingCallActivity;
import com.hasnat.remotephone.service.NetworkClientService;

public class MyConnectionService extends ConnectionService {

    private static final String TAG = "MyConnectionService";

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle connectionManagerPhoneAccount,
                                                 ConnectionRequest request) {
        Uri handle = request.getAddress();
        String number = handle != null ? handle.getSchemeSpecificPart() : "Unknown";
        Log.d(TAG, "Creating outgoing connection to: " + number);

        MyConnection connection = new MyConnection();
        connection.setAddress(handle, TelecomManager.PRESENTATION_ALLOWED);
        connection.setDialing();

        // Launch OngoingCallActivity for outgoing calls
        Intent intent = new Intent(this, OngoingCallActivity.class);
        intent.putExtra("CONTACT_NAME", "");
        intent.putExtra("PHONE_NUMBER", number);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        return connection;
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle phoneAccount,
                                                 ConnectionRequest request) {
        Uri handle = request.getAddress();
        String number = handle != null ? handle.getSchemeSpecificPart() : "Unknown";
        Log.d(TAG, "Creating incoming connection from: " + number);

        MyConnection connection = new MyConnection();
        connection.setAddress(handle, TelecomManager.PRESENTATION_ALLOWED);
        connection.setRinging();

        // Launch IncomingCallActivity for incoming calls
        Intent intent = new Intent(this, IncomingCallActivity.class);
        intent.putExtra(NetworkClientService.EXTRA_INCOMING_NUMBER, number);
        intent.putExtra("EXTRA_INCOMING_NAME", "Unknown");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        return connection;
    }

    @Override
    public void onConnectionServiceFocusGained() {
        super.onConnectionServiceFocusGained();
        Log.d(TAG, "Connection service focus gained");
    }

    @Override
    public void onConnectionServiceFocusLost() {
        super.onConnectionServiceFocusLost();
        Log.d(TAG, "Connection service focus lost");
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle phoneAccountHandle, ConnectionRequest request) {
        super.onCreateIncomingConnectionFailed(phoneAccountHandle, request);
        Log.e(TAG, "Incoming connection failed for: " + request.getAddress());
    }

    @Override
    public void onCreateOutgoingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request);
        Log.e(TAG, "Outgoing connection failed for: " + request.getAddress());
    }

    public static class MyConnection extends Connection {
        @Override
        public void onAnswer() {
            Log.d(TAG, "Call answered");
            setActive();
            // TODO: Start audio stream for answering
        }

        @Override
        public void onReject() {
            Log.d(TAG, "Call rejected");
            setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
            destroy();
        }

        @Override
        public void onDisconnect() {
            Log.d(TAG, "Call disconnected");
            setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            destroy();
        }

        @Override
        public void onHold() {
            Log.d(TAG, "Call put on hold");
            setOnHold();
            // TODO: Pause audio stream
        }

        @Override
        public void onUnhold() {
            Log.d(TAG, "Call resumed from hold");
            setActive();
            // TODO: Resume audio stream
        }
    }


}
