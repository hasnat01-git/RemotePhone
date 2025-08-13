package com.hasnat.remotephone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hasnat.remotephone.service.NetworkClientService;
import com.hasnat.remotephone.service.NetworkServerService;
import com.hasnat.remotephone.utils.ContactsManager;
import com.hasnat.remotephone.utils.PermissionHelper;
import com.hasnat.remotephone.utils.ServiceManager;
import com.hasnat.remotephone.utils.WifiUtils;

import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private Button hostButton, clientButton, startServiceButton, stopServiceButton;
    private TextView statusTextView, ipTextView, appModeTextView;
    private EditText hostIpEditText;
    private String appMode = "None";
    private EditText dialpadEditText;
    private ImageButton backspaceButton, contactsButton, callButton;
    private Button callFromHostButton, callDirectlyButton;
    private LinearLayout clientCallButtonsLayout;

    private PermissionHelper permissionHelper;
    private ServiceManager serviceManager;
    private BroadcastReceiverManager broadcastReceiverManager;
    private ContactsManager contactsManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        setupDialpadListeners();
        setupDialpadPasteFunctionality();

        permissionHelper = new PermissionHelper(this);
        serviceManager = new ServiceManager(this);
        broadcastReceiverManager = new BroadcastReceiverManager();
        contactsManager = new ContactsManager(this);

        permissionHelper.checkAndRequestPermissions();
        permissionHelper.checkAndRequestSystemAlertPermission();

        hostButton.setOnClickListener(v -> setAppMode("Host"));
        clientButton.setOnClickListener(v -> setAppMode("Client"));
        startServiceButton.setOnClickListener(v -> startTheService());
        stopServiceButton.setOnClickListener(v -> stopTheService());

        contactsButton.setOnClickListener(v -> {
            Intent contactsIntent = new Intent(MainActivity.this, ContactsActivity.class);
            startActivity(contactsIntent);
        });

        callButton.setOnClickListener(v -> {
            String phoneNumber = dialpadEditText.getText().toString();
            if (!phoneNumber.isEmpty()) {
                contactsManager.dialCall(phoneNumber);
            } else {
                Toast.makeText(this, "Please enter a number to call.", Toast.LENGTH_SHORT).show();
            }
        });

        callFromHostButton.setOnClickListener(v -> {
            String phoneNumber = dialpadEditText.getText().toString();
            if (!phoneNumber.isEmpty()) {
                // Store the outgoing number before sending the command
                NetworkClientService.currentOutgoingNumber = phoneNumber;
                contactsManager.callFromHost(phoneNumber);
            } else {
                Toast.makeText(this, "Please enter a number to call.", Toast.LENGTH_SHORT).show();
            }
        });

        callDirectlyButton.setOnClickListener(v -> {
            String phoneNumber = dialpadEditText.getText().toString();
            if (!phoneNumber.isEmpty()) {
                contactsManager.callDirectlyFromClient(phoneNumber);
            } else {
                Toast.makeText(this, "Please enter a number to call.", Toast.LENGTH_SHORT).show();
            }
        });

        updateUIForMode("None");
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiverManager.dialContactReceiver, new IntentFilter(ContactsActivity.ACTION_DIAL_CONTACT));
    }

    private void initializeViews() {
        dialpadEditText = findViewById(R.id.dialpadEditText);
        hostButton = findViewById(R.id.hostButton);
        clientButton = findViewById(R.id.clientButton);
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);
        statusTextView = findViewById(R.id.statusTextView);
        ipTextView = findViewById(R.id.ipTextView);
        hostIpEditText = findViewById(R.id.hostIpEditText);
        appModeTextView = findViewById(R.id.appModeTextView);
        backspaceButton = findViewById(R.id.btn_backspace);
        contactsButton = findViewById(R.id.contactsButton);
        callButton = findViewById(R.id.callButton);
        clientCallButtonsLayout = findViewById(R.id.clientCallButtonsLayout);
        callFromHostButton = findViewById(R.id.callFromHostButton);
        callDirectlyButton = findViewById(R.id.callDirectlyButton);

        // Handle incoming call intents
        Intent intent = getIntent();
        if (intent != null && intent.getData() != null && (Intent.ACTION_DIAL.equals(intent.getAction()) || Intent.ACTION_CALL.equals(intent.getAction()))) {
            String phoneNumber = intent.getData().getSchemeSpecificPart();
            dialpadEditText.setText(phoneNumber);
        }
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionHelper.handlePermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        permissionHelper.handleActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        appMode = prefs.getString("app_mode", "None");
        updateUIForMode(appMode);
        broadcastReceiverManager.register(this, appMode);

        // This is the new, crucial check
        if (appMode.equals("Host")) {
            if (permissionHelper.isDefaultDialer()) {
                Toast.makeText(this, "App is set as default dialer.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "App NOT set as default dialer.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        broadcastReceiverManager.unregister(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null &&
                (Intent.ACTION_DIAL.equals(intent.getAction()) || Intent.ACTION_CALL.equals(intent.getAction()))) {
            String phoneNumber = intent.getData().getSchemeSpecificPart();
            dialpadEditText.setText(phoneNumber);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiverManager.dialContactReceiver);
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
            startServiceButton.setVisibility(serviceManager.isServiceRunning(NetworkServerService.class) ? View.GONE : View.VISIBLE);
            stopServiceButton.setVisibility(serviceManager.isServiceRunning(NetworkServerService.class) ? View.VISIBLE : View.GONE);
        } else if ("Client".equals(mode)) {
            hostIpEditText.setVisibility(View.VISIBLE);
            ipTextView.setVisibility(View.GONE);
            SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
            String savedIp = prefs.getString("host_ip", "");
            hostIpEditText.setText(savedIp);
            callButton.setVisibility(View.GONE);
            clientCallButtonsLayout.setVisibility(View.VISIBLE);
            startServiceButton.setVisibility(serviceManager.isServiceRunning(NetworkClientService.class) ? View.GONE : View.VISIBLE);
            stopServiceButton.setVisibility(serviceManager.isServiceRunning(NetworkClientService.class) ? View.VISIBLE : View.GONE);
        } else {
            callButton.setVisibility(View.GONE);
            clientCallButtonsLayout.setVisibility(View.GONE);
            startServiceButton.setVisibility(View.VISIBLE);
            stopServiceButton.setVisibility(View.VISIBLE);
        }
    }

    private void startTheService() {
        if (Objects.equals(appMode, "Host")) {
            if (!PermissionHelper.isNotificationServiceEnabled(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("Notification Access Required")
                        .setMessage("Please enable notification access for this app to mirror notifications.")
                        .setPositiveButton("Open Settings", (dialog, which) -> PermissionHelper.openNotificationSettings(this))
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            serviceManager.startService(appMode, null);
        } else if (Objects.equals(appMode, "Client")) {
            String hostIp = hostIpEditText.getText().toString();
            if (hostIp.isEmpty()) {
                Toast.makeText(this, "Please enter the host IP address", Toast.LENGTH_SHORT).show();
                return;
            }
            SharedPreferences.Editor editor = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit();
            editor.putString("host_ip", hostIp);
            editor.apply();
            serviceManager.startService(appMode, hostIp);
        }
    }

    private void stopTheService() {
        serviceManager.stopService(appMode);
    }

    // Inner class to manage all BroadcastReceivers
    private class BroadcastReceiverManager {
        final BroadcastReceiver hostStatusReceiver = new BroadcastReceiver() {
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

        final BroadcastReceiver clientConnectionReceiver = new BroadcastReceiver() {
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

        final BroadcastReceiver clientStatusReceiver = new BroadcastReceiver() {
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

        final BroadcastReceiver dialContactReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String phoneNumber = intent.getStringExtra(ContactsActivity.EXTRA_PHONE_NUMBER);
                if (phoneNumber != null && !phoneNumber.isEmpty()) {
                    dialpadEditText.setText(phoneNumber);
                }
            }
        };

        void register(Activity activity, String appMode) {
            if ("Host".equals(appMode)) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(NetworkServerService.ACTION_HOST_STATUS);
                filter.addAction(NetworkServerService.ACTION_CLIENT_COUNT_UPDATE);
                LocalBroadcastManager.getInstance(activity).registerReceiver(hostStatusReceiver, filter);
            } else if ("Client".equals(appMode)) {
                LocalBroadcastManager.getInstance(activity).registerReceiver(clientStatusReceiver, new IntentFilter(NetworkClientService.ACTION_CLIENT_STATUS));
                LocalBroadcastManager.getInstance(activity).registerReceiver(clientConnectionReceiver, new IntentFilter(NetworkClientService.ACTION_HOST_CONNECTION_UPDATE));
            }
        }

        void unregister(Activity activity) {
            LocalBroadcastManager.getInstance(activity).unregisterReceiver(hostStatusReceiver);
            LocalBroadcastManager.getInstance(activity).unregisterReceiver(clientStatusReceiver);
            LocalBroadcastManager.getInstance(activity).unregisterReceiver(clientConnectionReceiver);
        }
    }
}
