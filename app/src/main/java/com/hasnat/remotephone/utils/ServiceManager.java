package com.hasnat.remotephone.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.Toast;

import com.hasnat.remotephone.service.NetworkClientService;
import com.hasnat.remotephone.service.NetworkServerService;

import java.util.Objects;

public class ServiceManager {
    private final Context context;

    public ServiceManager(Context context) {
        this.context = context;
    }

    public void startService(String appMode, String hostIp) {
        if (Objects.equals(appMode, "Host")) {
            Intent serverIntent = new Intent(context, NetworkServerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serverIntent);
            } else {
                context.startService(serverIntent);
            }
        } else if (Objects.equals(appMode, "Client")) {
            Intent clientIntent = new Intent(context, NetworkClientService.class);
            clientIntent.putExtra(NetworkClientService.EXTRA_HOST_IP, hostIp);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(clientIntent);
            } else {
                context.startService(clientIntent);
            }
        }
    }

    public void stopService(String appMode) {
        if (Objects.equals(appMode, "Host")) {
            Intent serverIntent = new Intent(context, NetworkServerService.class);
            context.stopService(serverIntent);
        } else if (Objects.equals(appMode, "Client")) {
            Intent clientIntent = new Intent(context, NetworkClientService.class);
            context.stopService(clientIntent);
        }
    }

    public boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }
}