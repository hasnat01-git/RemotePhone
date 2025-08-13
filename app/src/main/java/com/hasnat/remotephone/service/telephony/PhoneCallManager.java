package com.hasnat.remotephone.service.telephony;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hasnat.remotephone.service.BroadcastManager;
import com.hasnat.remotephone.service.NotificationHelper;
import com.hasnat.remotephone.utils.ContactHelper;

import android.content.ComponentName;

/**
 * Manages phone call-related functionalities on the host device, including detecting
 * call states, answering/ending calls, and broadcasting information to the client.
 */
public class PhoneCallManager {
    private static final String TAG = "PhoneCallManager";
    private final Context context;
    private final NotificationHelper notificationHelper;
    private final BroadcastManager broadcastManager;
    private TelephonyManager telephonyManager;
    private TelecomManager telecomManager;
    private PhoneStateListener phoneStateListener;
    private boolean wasInCall = false;

    // Temporary storage for outgoing call information
    private String tempOutgoingNumber;
    private String tempOutgoingName;

    // Temporary storage for incoming call information
    private String tempIncomingNumber;
    private String tempIncomingName;

    public PhoneCallManager(Context context, NotificationHelper notificationHelper, BroadcastManager broadcastManager) {
        this.context = context;
        this.notificationHelper = notificationHelper;
        this.broadcastManager = broadcastManager;
        this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        }
    }

    public void setupPhoneStateListener() {
        if (telephonyManager != null) {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    switch (state) {
                        case TelephonyManager.CALL_STATE_RINGING:
                            wasInCall = true;
                            String number = (phoneNumber != null && !phoneNumber.trim().isEmpty()) ? phoneNumber : "Unknown";
                            String contactName = ContactHelper.getContactName(context, number);
                            Log.d(TAG, "Incoming call detected from " + number + " (" + contactName + ")");
                            notificationHelper.updateNotification("Incoming Call", "From " + (contactName != null ? contactName : number));
                            broadcastManager.sendHostStatus("Host: Incoming call from " + (contactName != null ? contactName : number));

                            // Store incoming call details temporarily
                            tempIncomingNumber = number;
                            tempIncomingName = contactName;

                            // Broadcast the incoming call with the name and number
                            broadcastManager.broadcastToClients("RINGING:" + number + "|" + (contactName != null ? contactName : "Unknown"));
                            break;

                        case TelephonyManager.CALL_STATE_OFFHOOK:
                            wasInCall = true;
                            Log.d(TAG, "Call is now OFFHOOK (outgoing or answered).");

                            // Check if this is an outgoing or an answered incoming call
                            if (tempOutgoingNumber != null) {
                                // This is an outgoing call, broadcast the number and name from temporary storage
                                String outgoingName = tempOutgoingName != null ? tempOutgoingName : "Unknown";
                                Log.d(TAG, "Outgoing call started. Broadcasting to client: " + tempOutgoingNumber + " (" + outgoingName + ")");
                                broadcastManager.broadcastToClients("CALL_STARTED:" + tempOutgoingNumber + "|" + outgoingName);
                                tempOutgoingNumber = null;
                                tempOutgoingName = null;
                            } else if (tempIncomingNumber != null) {
                                // This is an answered incoming call, broadcast the stored number and name
                                Log.d(TAG, "Incoming call was answered. Broadcasting to client: " + tempIncomingNumber + " (" + tempIncomingName + ")");
                                broadcastManager.broadcastToClients("CALL_STARTED:" + tempIncomingNumber + "|" + tempIncomingName);
                                tempIncomingNumber = null;
                                tempIncomingName = null;
                            }
                            // No need for a generic "CALL_STARTED" message anymore.
                            break;

                        case TelephonyManager.CALL_STATE_IDLE:
                            if (wasInCall) {
                                Log.d(TAG, "Call ended");
                                broadcastManager.broadcastToClients("CALL_IDLE");
                                wasInCall = false;
                            }
                            // Clear all temporary call information
                            tempOutgoingNumber = null;
                            tempOutgoingName = null;
                            tempIncomingNumber = null;
                            tempIncomingName = null;
                            break;
                    }
                }
            };
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        } else {
            Log.e(TAG, "TelephonyManager is null. This device may not have phone capabilities.");
            broadcastManager.sendHostStatus("Host: Error - No phone capabilities.");
        }
    }


    public void stopPhoneStateListener() {
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
    }

    public void setMute(boolean muted) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMicrophoneMute(muted);
            Log.d(TAG, "Microphone mute is now " + (muted ? "ON" : "OFF"));
        } else {
            Log.e(TAG, "AudioManager is null, cannot change mute status.");
        }
    }

    public void setSpeaker(boolean enabled) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(enabled);
            Log.d(TAG, "Speakerphone is now " + (enabled ? "ON" : "OFF"));
        } else {
            Log.e(TAG, "AudioManager is null, cannot change speaker status.");
        }
    }

    public void setOnHold(boolean onHold) {
        if (onHold) {
            Log.d(TAG, "Call put on hold.");
        } else {
            Log.d(TAG, "Call resumed from hold.");
        }
    }

    public void placeCall(String number) {
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + number));
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            context.startActivity(callIntent);
        } else {
            Log.e(TAG, "CALL_PHONE permission not granted");
        }
    }

    public void dialWithTelecom(String phoneNumber) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            if (telecomManager != null) {
                Uri uri = Uri.fromParts("tel", phoneNumber, null);
                Bundle extras = new Bundle();
                String contactName = ContactHelper.getContactName(context, phoneNumber);
                extras.putString("CONTACT_NAME", contactName);
                PhoneAccountHandle phoneAccountHandle = new PhoneAccountHandle(
                        new ComponentName(context, com.hasnat.remotephone.service.MyConnectionService.class), "RemotePhone");
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
                Log.d(TAG, "Placing call via TelecomManager for number: " + phoneNumber);

                // Store the number and name temporarily for the call state change listener
                tempOutgoingNumber = phoneNumber;
                tempOutgoingName = contactName;

                telecomManager.placeCall(uri, extras);
                broadcastManager.sendHostStatus("Host: Placing call for " + (contactName != null ? contactName : phoneNumber));
            } else {
                Log.e(TAG, "TelecomManager is null, cannot place call.");
                broadcastManager.sendHostStatus("Host: Error placing call.");
            }
        } else {
            Log.e(TAG, "CALL_PHONE permission not granted.");
            broadcastManager.sendHostStatus("Host: Permission to call is missing.");
        }
    }
    public void answerCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (telecomManager != null) {
                    Log.d(TAG, "Attempting to accept ringing call via TelecomManager.");
                    telecomManager.acceptRingingCall();
                }
            } else {
                Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission.");
                // TODO: Add a broadcast to the client here.
            }
        } else {
            try {
                Intent answerIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                answerIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK));
                context.sendOrderedBroadcast(answerIntent, "android.permission.CALL_PRIVILEGED");
                answerIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                answerIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK));
                context.sendOrderedBroadcast(answerIntent, "android.permission.CALL_PRIVILEGED");
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException while answering call", e);
                // TODO: Add a broadcast to the client here.
            }
        }
    }

    public void endCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (telecomManager != null) {
                    Log.d(TAG, "Attempting to end current call via TelecomManager.");
                    telecomManager.endCall();
                }
            } else {
                Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission to end call.");
                // TODO: Add a broadcast to the client here.
            }
        } else {
            try {
                Intent endCallIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                endCallIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENDCALL));
                context.sendOrderedBroadcast(endCallIntent, null);
                endCallIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                endCallIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENDCALL));
                context.sendOrderedBroadcast(endCallIntent, null);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException while ending call", e);
                // TODO: Add a broadcast to the client here.
            }
        }
    }
}
