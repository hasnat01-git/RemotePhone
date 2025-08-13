package com.hasnat.remotephone.service;

import android.content.Context;
import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class BroadcastManager {
    private final Context context;

    public BroadcastManager(Context context) {
        this.context = context;
    }

    public void sendHostStatus(String message) {
        Intent intent = new Intent(NetworkServerService.ACTION_HOST_STATUS);
        intent.putExtra(NetworkServerService.EXTRA_STATUS_MESSAGE, message);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    public void sendClientCountUpdate(int count) {
        Intent intent = new Intent(NetworkServerService.ACTION_CLIENT_COUNT_UPDATE);
        intent.putExtra(NetworkServerService.EXTRA_CLIENT_COUNT, count);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    public void broadcastToClients(String message) {
        Intent intent = new Intent(NetworkServerService.ACTION_BROADCAST_TO_CLIENTS);
        intent.putExtra(NetworkServerService.EXTRA_CLIENT_MESSAGE, message);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }
}