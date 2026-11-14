package com.joz.tvrelay;

import java.net.InetAddress;
import java.net.NetworkInterface;

final class NetUtil {
    /** True for this device's own addresses, so it never finds or plays from itself. */
    static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress()) return true;
        try {
            return NetworkInterface.getByInetAddress(a) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private NetUtil() {}
}
