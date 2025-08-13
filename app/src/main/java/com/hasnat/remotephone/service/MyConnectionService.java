package com.hasnat.remotephone.service;

import android.content.Intent;
import android.net.Uri;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.IncomingCallActivity;
import com.hasnat.remotephone.OngoingCallActivity;
import com.hasnat.remotephone.utils.ContactHelper;

public class MyConnectionService extends ConnectionService {

    private static final String TAG = "MyConnectionService";
    private BroadcastManager broadcastManager;

    @Override
    public void onCreate() {
        super.onCreate();
        broadcastManager = new BroadcastManager(this);
    }

    private void sendCommand(String command) {
        broadcastManager.broadcastToClients(command);
    }

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle connectionManagerPhoneAccount,
                                                 ConnectionRequest request) {
        String number = request.getAddress().getSchemeSpecificPart();
        String name = ContactHelper.getContactName(this, number);

        Log.d(TAG, "Creating outgoing connection to: " + number + " (" + name + ")");

        MyConnection connection = new MyConnection();
        connection.setAddress(request.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
        connection.setDialing();

        // The host's dialWithTelecom already sends this command.
        // This part is correctly commented out.
        // broadcastManager.broadcastToClients("OUTGOING_CALL:" + number);

        // We can set the client's current call info here for the subsequent CALL_STARTED command
        // This is a more reliable approach than relying on a separate message.
        // NOTE: This assumes the NetworkClientService is also on the host. This is a common
        // point of confusion in client-server architecture. If MyConnectionService is on the host
        // it should communicate with NetworkServerService, not NetworkClientService.
        // Your code uses `broadcastManager.broadcastToClients`, which is correct for the host.
        // The client must then use the data sent with the `OUTGOING_CALL` command.

        return connection;
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle phoneAccount,
                                                 ConnectionRequest request) {
        String number = request.getAddress().getSchemeSpecificPart();
        String name = ContactHelper.getContactName(this, number);

        Log.d(TAG, "Creating incoming connection from: " + number + " (" + name + ")");

        MyConnection connection = new MyConnection();
        connection.setAddress(request.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
        connection.setRinging();

        // This command should be sent by the TelephonyManager when a call is detected,
        // not from onCreateIncomingConnection. Your PhoneCallManager handles this.
        // broadcastManager.broadcastToClients("RINGING:" + number + "|" + (name != null ? name : "Unknown"));

        return connection;
    }

    public class MyConnection extends Connection {
        @Override
        public void onAnswer() {
            Log.d(TAG, "Call answered");
            setActive();
            // The client's OngoingCallActivity needs to be launched when the call is answered.
            // This command is the trigger for the client to start the activity and audio bridge.
            sendCommand("CALL_STARTED");
        }

        @Override
        public void onReject() {
            Log.d(TAG, "Call rejected");
            setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
            destroy();
            sendCommand("CALL_IDLE");
        }

        @Override
        public void onDisconnect() {
            Log.d(TAG, "Call disconnected");
            setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            destroy();
            sendCommand("CALL_IDLE");
        }

        @Override
        public void onHold() {
            Log.d(TAG, "Call put on hold");
            setOnHold();
            sendCommand("HOLD");
        }

        @Override
        public void onUnhold() {
            Log.d(TAG, "Call resumed from hold");
            setActive();
            sendCommand("UNHOLD");
        }
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle phoneAccountHandle, ConnectionRequest request) {
        super.onCreateIncomingConnectionFailed(phoneAccountHandle, request);
        Log.e(TAG, "Incoming connection failed for: " + request.getAddress());
        sendCommand("CALL_IDLE");
    }

    @Override
    public void onCreateOutgoingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request);
        Log.e(TAG, "Outgoing connection failed for: " + request.getAddress());
        sendCommand("CALL_IDLE");
    }
}