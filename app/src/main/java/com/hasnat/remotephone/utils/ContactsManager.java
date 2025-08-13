package com.hasnat.remotephone.utils;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.service.NetworkClientService;
import com.hasnat.remotephone.service.NetworkServerService;

public class ContactsManager {
    private final Context context;

    public ContactsManager(Context context) {
        this.context = context;
    }

    public void dialCall(String phoneNumber) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            String cleanedNumber = phoneNumber.replaceAll("[^\\d+]", "");
            // Bug fix: Use cleanedNumber instead of phoneNumber
            Intent dialIntent = new Intent(Intent.ACTION_CALL);
            dialIntent.setData(Uri.parse("tel:" + cleanedNumber));
            try {
                context.startActivity(dialIntent);
                Log.d("HostCallDebug", "Successfully started call activity for number: " + cleanedNumber);
            } catch (SecurityException e) {
                Log.e("HostCallDebug", "SecurityException: The app lacks the necessary CALL_PHONE permission to make the call.", e);
                Toast.makeText(context, "Permission not granted for call. Please check app settings.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Log.e("HostCallDebug", "An unexpected error occurred while trying to make a call.", e);
                Toast.makeText(context, "Error: Could not place call.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(context, "CALL_PHONE permission is required to make calls. Please enable it in Settings.", Toast.LENGTH_LONG).show();
        }
    }

    public void callFromHost(String phoneNumber) {
        Log.d("ClientCallDebug", "Sending DIAL command to Host for number: " + phoneNumber);
        String contactName = getContactName(context, phoneNumber);
        NetworkClientService.lastDialedNumber = phoneNumber;
        NetworkClientService.lastDialedName = contactName;
        Intent intent = new Intent(NetworkClientService.ACTION_SEND_COMMAND);
        intent.putExtra(NetworkClientService.EXTRA_COMMAND, "DIAL:" + phoneNumber);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        Toast.makeText(context, "Calling " + (contactName != null ? contactName : phoneNumber) + " on host...", Toast.LENGTH_SHORT).show();
    }

    public void callDirectlyFromClient(String phoneNumber) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Intent dialIntent = new Intent(Intent.ACTION_CALL);
            dialIntent.setData(Uri.parse("tel:" + phoneNumber));
            try {
                context.startActivity(dialIntent);
                Toast.makeText(context, "Calling " + phoneNumber + " from this device...", Toast.LENGTH_SHORT).show();
            } catch (SecurityException e) {
                Log.e("ClientCallDebug", "SecurityException: Lacks CALL_PHONE permission for direct call.", e);
                Toast.makeText(context, "Permission not granted for direct call.", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(context, "CALL_PHONE permission is needed to call directly. Please enable it in Settings.", Toast.LENGTH_LONG).show();
        }
    }

    private String getContactName(Context context, String phoneNumber) {
        // Implementation for getContactName remains the same
        return ContactHelper.getContactName(context, phoneNumber);
    }
}