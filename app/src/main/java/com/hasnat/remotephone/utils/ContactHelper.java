package com.hasnat.remotephone.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * A helper class to handle contact-related operations.
 * This class provides a method to look up a contact's name from their phone number.
 */
public class ContactHelper {

    private static final String TAG = "ContactHelper";

    /**
     * Retrieves the contact name associated with a given phone number.
     * This method normalizes the phone number for more reliable lookup.
     * @param context The application context.
     * @param phoneNumber The phone number to look up.
     * @return The contact's display name, or "Unknown" if not found or permission is denied.
     */
    public static String getContactName(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return "Unknown";
        }

        // Ensure READ_CONTACTS permission is granted.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted. Cannot look up contact name.");
            return "Unknown";
        }

        String contactName = "Unknown";
        Cursor cursor = null;
        try {
            // Normalize the phone number to remove formatting and improve lookup reliability.
            String normalizedNumber = PhoneNumberUtils.normalizeNumber(phoneNumber);
            if (normalizedNumber == null || normalizedNumber.isEmpty()) {
                return "Unknown";
            }

            // Use ContactsContract.PhoneLookup, which is the most reliable way to look up a contact by number.
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalizedNumber));
            String[] projection = new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME};

            cursor = context.getContentResolver().query(
                    uri,
                    projection,
                    null,
                    null,
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME);
                if (nameIndex != -1) {
                    contactName = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching contact name", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return contactName;
    }
}
