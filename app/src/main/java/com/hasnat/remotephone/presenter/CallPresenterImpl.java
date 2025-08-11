package com.hasnat.remotephone.presenter;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telecom.TelecomManager;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.contract.CallContract;
import com.hasnat.remotephone.service.NetworkClientService;

public class CallPresenterImpl implements CallContract.Presenter {

    private CallContract.View view;
    private Context context;

    public CallPresenterImpl(CallContract.View view, Context context) {
        this.view = view;
        this.context = context;
    }

    @Override
    public void handleIncomingCall(String number) {
        if (view != null) {
            view.showIncomingCall(number);
        }
    }

    @Override
    public void answerCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(NetworkClientService.ACTION_SEND_COMMAND);
            intent.putExtra(NetworkClientService.EXTRA_COMMAND, "ANSWER_CALL");
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        } else {
            if (view != null) {
                view.showError("Answering calls is not supported on this Android version.");
            }
        }
    }

    @Override
    public void endCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // Check for API 28 (Android 9.0)
            Intent intent = new Intent(NetworkClientService.ACTION_SEND_COMMAND);
            intent.putExtra(NetworkClientService.EXTRA_COMMAND, "END_CALL");
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        } else {
            if (view != null) {
                view.showError("Ending calls is not supported on this Android version.");
            }
        }
    }

    @Override
    public void rejectCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // Check for API 28 (Android 9.0)
            // Rejecting a call is often handled by ending it
            endCall();
        } else {
            if (view != null) {
                view.showError("Rejecting calls is not supported on this Android version.");
            }
        }
    }

    @Override
    public void detach() {
        view = null;
    }
}