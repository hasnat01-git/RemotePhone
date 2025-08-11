package com.hasnat.remotephone;

import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.Manifest;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.service.NetworkClientService;
import com.hasnat.remotephone.service.NetworkServerService;
import com.hasnat.remotephone.utils.WifiUtils;

import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 101; // New request code for overlay permission
    private static final String[] PERMISSIONS;

    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PERMISSIONS = new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ANSWER_PHONE_CALLS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS
            };
        } else {
            PERMISSIONS = new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS
            };
        }
    }

    private Button hostButton, clientButton, startServiceButton, stopServiceButton;
    private TextView statusTextView, ipTextView, appModeTextView;
    private EditText hostIpEditText;
    private String appMode = "None";

    private EditText dialpadEditText;
    private ImageButton backspaceButton, contactsButton, callButton;
    private Button callFromHostButton, callDirectlyButton;
    private LinearLayout clientCallButtonsLayout;

    private final BroadcastReceiver hostStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NetworkServerService.ACTION_HOST_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(NetworkServerService.EXTRA_STATUS_MESSAGE);
                if (status != null) {
                    statusTextView.setText(status);
                }
            } else if (NetworkServerService.ACTION_CLIENT_COUNT_UPDATE.equals(intent.getAction())) {
                int clientCount = intent.getIntExtra(NetworkServerService.EXTRA_CLIENT_COUNT, 0);
                statusTextView.setText("Status: " + clientCount + " client(s) connected.");
            }
        }
    };

    private final BroadcastReceiver clientConnectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NetworkClientService.ACTION_HOST_CONNECTION_UPDATE.equals(intent.getAction())) {
                String connectedIp = intent.getStringExtra(NetworkClientService.EXTRA_CONNECTED_HOST_IP);
                if (connectedIp != null) {
                    statusTextView.setText("Client: Connected to host at " + connectedIp);
                    startServiceButton.setVisibility(View.GONE);
                    stopServiceButton.setVisibility(View.VISIBLE);
                } else {
                    statusTextView.setText("Client: Disconnected");
                    startServiceButton.setVisibility(View.VISIBLE);
                    stopServiceButton.setVisibility(View.GONE);
                }
            }
        }
    };

    private final BroadcastReceiver clientStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NetworkClientService.ACTION_CLIENT_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(NetworkClientService.EXTRA_STATUS_MESSAGE);
                if (status != null) {
                    statusTextView.setText(status);
                }
            }
        }
    };

    private final BroadcastReceiver dialContactReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String phoneNumber = intent.getStringExtra(ContactsActivity.EXTRA_PHONE_NUMBER);
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                dialpadEditText.setText(phoneNumber);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hostButton = findViewById(R.id.hostButton);
        clientButton = findViewById(R.id.clientButton);
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);
        statusTextView = findViewById(R.id.statusTextView);
        ipTextView = findViewById(R.id.ipTextView);
        hostIpEditText = findViewById(R.id.hostIpEditText);
        appModeTextView = findViewById(R.id.appModeTextView);

        dialpadEditText = findViewById(R.id.dialpadEditText);
        backspaceButton = findViewById(R.id.btn_backspace);
        contactsButton = findViewById(R.id.contactsButton);
        callButton = findViewById(R.id.callButton);

        clientCallButtonsLayout = findViewById(R.id.clientCallButtonsLayout);
        callFromHostButton = findViewById(R.id.callFromHostButton);
        callDirectlyButton = findViewById(R.id.callDirectlyButton);

        setupDialpadListeners();
        setupDialpadPasteFunctionality();

        checkAndRequestPermissions();
        checkAndRequestSystemAlertPermission(); // Call the new method to request overlay permission

        hostButton.setOnClickListener(v -> setAppMode("Host"));
        clientButton.setOnClickListener(v -> setAppMode("Client"));
        startServiceButton.setOnClickListener(v -> startTheService());
        stopServiceButton.setOnClickListener(v -> stopTheService());
        contactsButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ContactsActivity.class);
            startActivity(intent);
        });

        // Listener for the generic call button (Host Mode only)
        callButton.setOnClickListener(v -> {
            String phoneNumber = dialpadEditText.getText().toString();
            if (!phoneNumber.isEmpty()) {
                dialCall(phoneNumber);
            } else {
                Toast.makeText(this, "Please enter a number to call.", Toast.LENGTH_SHORT).show();
            }
        });

        // Listeners for the client-specific call buttons
        callFromHostButton.setOnClickListener(v -> {
            String phoneNumber = dialpadEditText.getText().toString();
            if (!phoneNumber.isEmpty()) {
                callFromHost(phoneNumber);
            } else {
                Toast.makeText(this, "Please enter a number to call.", Toast.LENGTH_SHORT).show();
            }
        });
        callDirectlyButton.setOnClickListener(v -> {
            String phoneNumber = dialpadEditText.getText().toString();
            if (!phoneNumber.isEmpty()) {
                callDirectlyFromClient(phoneNumber);
            } else {
                Toast.makeText(this, "Please enter a number to call.", Toast.LENGTH_SHORT).show();
            }
        });

        updateUIForMode("None");
        LocalBroadcastManager.getInstance(this).registerReceiver(dialContactReceiver, new IntentFilter(ContactsActivity.ACTION_DIAL_CONTACT));
    }

    private void setupDialpadListeners() {
        int[] numberButtonIds = {R.id.btn_0, R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4, R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9, R.id.btn_asterisk, R.id.btn_hash};
        for (int id : numberButtonIds) {
            findViewById(id).setOnClickListener(v -> {
                Button b = (Button) v;
                dialpadEditText.append(b.getText());
            });
        }

        backspaceButton.setOnClickListener(v -> {
            String currentText = dialpadEditText.getText().toString();
            if (!currentText.isEmpty()) {
                dialpadEditText.setText(currentText.substring(0, currentText.length() - 1));
            }
        });
    }

    private void setupDialpadPasteFunctionality() {
        registerForContextMenu(dialpadEditText);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if (v.getId() == R.id.dialpadEditText) {
            getMenuInflater().inflate(R.menu.dialpad_context_menu, menu);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_item_paste) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData.Item clipItem = clipboard.getPrimaryClip().getItemAt(0);
                String pasteText = clipItem.getText().toString();
                if (pasteText != null) {
                    dialpadEditText.append(pasteText);
                }
            }
            return true;
        }
        return super.onContextItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        appMode = prefs.getString("app_mode", "None");
        updateUIForMode(appMode);

        if (Objects.equals(appMode, "Host")) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(NetworkServerService.ACTION_HOST_STATUS);
            filter.addAction(NetworkServerService.ACTION_CLIENT_COUNT_UPDATE);
            LocalBroadcastManager.getInstance(this).registerReceiver(hostStatusReceiver, filter);
        } else if (Objects.equals(appMode, "Client")) {
            LocalBroadcastManager.getInstance(this).registerReceiver(clientStatusReceiver, new IntentFilter(NetworkClientService.ACTION_CLIENT_STATUS));
            LocalBroadcastManager.getInstance(this).registerReceiver(clientConnectionReceiver, new IntentFilter(NetworkClientService.ACTION_HOST_CONNECTION_UPDATE));
            if (isServiceRunning(NetworkClientService.class)) {
                String savedIp = prefs.getString("host_ip", "Unknown IP");
                statusTextView.setText("Client: Connected to host at " + savedIp);
                startServiceButton.setVisibility(View.GONE);
                stopServiceButton.setVisibility(View.VISIBLE);
            } else {
                statusTextView.setText("Client: Disconnected");
                startServiceButton.setVisibility(View.VISIBLE);
                stopServiceButton.setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(hostStatusReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(clientStatusReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(clientConnectionReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dialContactReceiver);
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        boolean allGranted = true;
        for (String permission : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Required permissions not granted. App may not function correctly.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
    }

    // New method to check and request the SYSTEM_ALERT_WINDOW permission
    private void checkAndRequestSystemAlertPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("To display incoming calls, you need to grant permission to 'Display over other apps'.")
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    // Override onActivityResult to handle the result from the settings screen
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permission granted. Incoming calls will now be displayed.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permission denied. Incoming call display may not work.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void setAppMode(String mode) {
        appMode = mode;
        updateUIForMode(mode);
        SharedPreferences.Editor editor = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit();
        editor.putString("app_mode", mode);
        editor.apply();
    }

    private void updateUIForMode(String mode) {
        appModeTextView.setText("App Mode: " + mode);

        if ("Host".equals(mode)) {
            hostIpEditText.setVisibility(View.GONE);
            ipTextView.setVisibility(View.VISIBLE);
            String ip = WifiUtils.getLocalIpAddress();
            ipTextView.setText("Your IP: " + (ip != null ? ip : "Not Connected"));
            callButton.setVisibility(View.VISIBLE);
            clientCallButtonsLayout.setVisibility(View.GONE);
        } else if ("Client".equals(mode)) {
            hostIpEditText.setVisibility(View.VISIBLE);
            ipTextView.setVisibility(View.GONE);
            SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
            String savedIp = prefs.getString("host_ip", "");
            hostIpEditText.setText(savedIp);
            callButton.setVisibility(View.GONE);
            clientCallButtonsLayout.setVisibility(View.VISIBLE);
        } else { // "None" mode
            callButton.setVisibility(View.GONE);
            clientCallButtonsLayout.setVisibility(View.GONE);
        }
        startServiceButton.setVisibility(View.VISIBLE);
        stopServiceButton.setVisibility(View.VISIBLE);
    }

    // Original dialCall method, now for Host Mode only
    private void dialCall(String phoneNumber) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Intent dialIntent = new Intent(Intent.ACTION_CALL);
            dialIntent.setData(Uri.parse("tel:" + phoneNumber));
            try {
                startActivity(dialIntent);
                Log.d("HostCallDebug", "Successfully started call activity for number: " + phoneNumber);
            } catch (SecurityException e) {
                Log.e("HostCallDebug", "SecurityException: The app lacks the necessary CALL_PHONE permission to make the call.", e);
                Toast.makeText(this, "Permission not granted for call. Please check app settings.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Log.e("HostCallDebug", "An unexpected error occurred while trying to make a call.", e);
                Toast.makeText(this, "Error: Could not place call.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "CALL_PHONE permission is required to make calls. Please enable it in Settings.", Toast.LENGTH_LONG).show();
        }
    }

    private void callFromHost(String phoneNumber) {
        Log.d("ClientCallDebug", "Sending DIAL command to Host for number: " + phoneNumber);
        Intent intent = new Intent(NetworkClientService.ACTION_SEND_COMMAND);
        intent.putExtra(NetworkClientService.EXTRA_COMMAND, "DIAL:" + phoneNumber);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Toast.makeText(this, "Calling " + phoneNumber + " on host...", Toast.LENGTH_SHORT).show();
    }

    private void callDirectlyFromClient(String phoneNumber) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Intent dialIntent = new Intent(Intent.ACTION_CALL);
            dialIntent.setData(Uri.parse("tel:" + phoneNumber));
            try {
                startActivity(dialIntent);
                Toast.makeText(this, "Calling " + phoneNumber + " from this device...", Toast.LENGTH_SHORT).show();
            } catch (SecurityException e) {
                Log.e("ClientCallDebug", "SecurityException: Lacks CALL_PHONE permission for direct call.", e);
                Toast.makeText(this, "Permission not granted for direct call.", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "CALL_PHONE permission is needed to call directly. Please enable it in Settings.", Toast.LENGTH_LONG).show();
        }
    }

    private void startTheService() {
        if (Objects.equals(appMode, "Host")) {
            if (!isNotificationServiceEnabled()) {
                new AlertDialog.Builder(this)
                        .setTitle("Notification Access Required")
                        .setMessage("Please enable notification access for this app to mirror notifications.")
                        .setPositiveButton("Open Settings", (dialog, which) -> openNotificationSettings())
                        .setNegativeButton("Cancel", null)
                        .show();
            }
            Intent serverIntent = new Intent(this, NetworkServerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serverIntent);
            } else {
                startService(serverIntent);
            }
        } else if (Objects.equals(appMode, "Client")) {
            String hostIp = hostIpEditText.getText().toString();
            if (hostIp.isEmpty()) {
                Toast.makeText(this, "Please enter the host IP address", Toast.LENGTH_SHORT).show();
                return;
            }
            SharedPreferences.Editor editor = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit();
            editor.putString("host_ip", hostIp);
            editor.apply();

            Intent clientIntent = new Intent(this, NetworkClientService.class);
            clientIntent.putExtra(NetworkClientService.EXTRA_HOST_IP, hostIp);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(clientIntent);
            } else {
                startService(clientIntent);
            }
        }
    }

    private void stopTheService() {
        if (Objects.equals(appMode, "Host")) {
            Intent serverIntent = new Intent(this, NetworkServerService.class);
            stopService(serverIntent);
        } else if (Objects.equals(appMode, "Client")) {
            Intent clientIntent = new Intent(this, NetworkClientService.class);
            stopService(clientIntent);
        }
    }

    private boolean isNotificationServiceEnabled(){
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
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

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        startActivity(intent);
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
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