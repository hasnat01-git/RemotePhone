// app/src/main/java/com/hasnat/remotephone/utils/WifiUtils.java
package com.hasnat.remotephone.utils;

import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class WifiUtils {

    /**
     * Gets the current local IP address of the device. This works for both
     * Wi-Fi client connections and Wi-Fi hotspot connections.
     *
     * @return The local IP address as a String, or null if no local IP is found.
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                // Ignore loopback and inactive interfaces
                if (!intf.isLoopback() && intf.isUp()) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        // We are only interested in IPv4 site-local addresses (e.g., 192.168.x.x)
                        if (!addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                            // Returns the first valid local IP found
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.e("WifiUtils", "Error getting local IP address", ex);
        }
        return null;
    }
}