package com.hasnat.remotephone.utils;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hasnat.remotephone.service.MyConnectionService;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PermissionHelper {
    private final Activity activity;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 101;
    private static final int REQUEST_ROLE_DIALER = 1234;

    private static final String[] PERMISSIONS;
    static {
        Set<String> permissionSet = new HashSet<>(Arrays.asList(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS
        ));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissionSet.add(Manifest.permission.ANSWER_PHONE_CALLS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionSet.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        PERMISSIONS = permissionSet.toArray(new String[0]);
    }

    public PermissionHelper(Activity activity) {
        this.activity = activity;
    }

    public void checkAndRequestPermissions() {
        boolean allGranted = true;
        for (String permission : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(activity, PERMISSIONS, PERMISSION_REQUEST_CODE);
        } else {
            registerPhoneAccount();
            requestDefaultDialerRole();
        }
    }

    public void handlePermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(activity, "All permissions granted.", Toast.LENGTH_SHORT).show();
                registerPhoneAccount();
                requestDefaultDialerRole();
            } else {
                Toast.makeText(activity, "Required permissions not granted. App may not function correctly.", Toast.LENGTH_LONG).show();
            }
        }
    }

    public void checkAndRequestSystemAlertPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle("Permission Required")
                    .setMessage("To display incoming calls, you need to grant permission to 'Display over other apps'.")
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(activity)) {
                Toast.makeText(activity, "Permission granted. Incoming calls will now be displayed.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(activity, "Permission denied. Incoming call display may not work.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_ROLE_DIALER) {
            if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(activity, "App set as default dialer", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(activity, "App NOT set as default dialer", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static boolean isNotificationServiceEnabled(Context context) {
        String pkgName = context.getPackageName();
        final String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void openNotificationSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        context.startActivity(intent);
    }

    private void registerPhoneAccount() {
        TelecomManager telecomManager = (TelecomManager) activity.getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager != null) {
            PhoneAccountHandle handle = new PhoneAccountHandle(new ComponentName(activity, MyConnectionService.class), "RemotePhone");
            PhoneAccount.Builder builder = new PhoneAccount.Builder(handle, "RemotePhone");
            builder.setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER);
            builder.setShortDescription("RemotePhone Dialer");
            telecomManager.registerPhoneAccount(builder.build());
        }
    }

    public boolean isDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            TelecomManager telecomManager = (TelecomManager) activity.getSystemService(Context.TELECOM_SERVICE);
            if (telecomManager != null && !TextUtils.isEmpty(telecomManager.getDefaultDialerPackage())) {
                return telecomManager.getDefaultDialerPackage().equals(activity.getPackageName());
            }
        }
        return false;
    }

    private void requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = activity.getSystemService(RoleManager.class);
            if (roleManager != null && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                Intent roleRequestIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                activity.startActivityForResult(roleRequestIntent, REQUEST_ROLE_DIALER);
            }
        } else {
            TelecomManager telecomManager = (TelecomManager) activity.getSystemService(Context.TELECOM_SERVICE);
            if (telecomManager != null && !activity.getPackageName().equals(telecomManager.getDefaultDialerPackage())) {
                Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
                intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.getPackageName());
                activity.startActivityForResult(intent, REQUEST_ROLE_DIALER);
            }
        }
    }
}