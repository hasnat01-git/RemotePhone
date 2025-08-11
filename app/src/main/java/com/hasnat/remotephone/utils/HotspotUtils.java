// app/src/main/java/com/hasnat/remotephone/utils/HotspotUtils.java
package com.hasnat.remotephone.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class HotspotUtils {
    /**
     * Checks if the device is a Wi-Fi hotspot.
     * This uses a common (but non-public) method to check for tethering.
     *
     * @param context The application context.
     * @return True if Wi-Fi hotspot is active, false otherwise.
     */
    public static boolean isWifiApEnabled(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                // Using reflection to access a hidden method
                java.lang.reflect.Method method = cm.getClass().getDeclaredMethod("getTetheringApis");
                method.setAccessible(true);
                int[] tetheringApis = (int[]) method.invoke(cm);
                if (tetheringApis.length > 0) {
                    NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                    return networkInfo != null && networkInfo.isConnected() && networkInfo.getType() == ConnectivityManager.TYPE_WIFI;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}