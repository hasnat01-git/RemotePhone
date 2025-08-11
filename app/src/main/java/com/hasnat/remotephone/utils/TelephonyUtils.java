package com.hasnat.remotephone.utils;

import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

public class TelephonyUtils {

    private static final String TAG = "TelephonyUtils";

    public static boolean endCall() {
        try {
            // Get the ITelephony class from the telephony manager service
            Method m = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
            IBinder b = (IBinder) m.invoke(null, "phone");
            Method m2 = Class.forName("com.android.internal.telephony.ITelephony$Stub").getMethod("asInterface", IBinder.class);
            Object telephonyService = m2.invoke(null, b);

            // Get the endCall method
            Method endCallMethod = telephonyService.getClass().getMethod("endCall");
            endCallMethod.invoke(telephonyService);
            Log.d(TAG, "Call ended successfully.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to end call: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static boolean answerCall() {
        try {
            // Get the ITelephony class from the telephony manager service
            Method m = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
            IBinder b = (IBinder) m.invoke(null, "phone");
            Method m2 = Class.forName("com.android.internal.telephony.ITelephony$Stub").getMethod("asInterface", IBinder.class);
            Object telephonyService = m2.invoke(null, b);

            // Get the answerRingingCall method
            Method answerCallMethod = telephonyService.getClass().getMethod("answerRingingCall");
            answerCallMethod.invoke(telephonyService);
            Log.d(TAG, "Call answered successfully.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to answer call: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}