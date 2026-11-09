package io.gutapk.offline;

/*
 * The network a game sees in an offline build. A game that asks Android
 * whether it is online before it calls its server stops at "no network" with
 * the phone in flight mode, and never reaches the servers on loopback. The
 * offline build turns its calls to ConnectivityManager into calls to these,
 * which answer as FakeWifiConnection's hooks do: the real answer when the
 * phone is on Wi-Fi, a connected Wi-Fi network otherwise.
 *
 * https://github.com/lemonsqueeze/FakeWifiConnection/blob/master/FakeWifiConnection/src/com/lemonsqueeze/fakewificonnection/Main.java#L77-L161
 * https://github.com/lemonsqueeze/FakeWifiConnection/blob/master/FakeWifiConnection/src/com/lemonsqueeze/fakewificonnection/Main.java#L336-L406
 *
 * Not ported: hack_enabled (only an offline build calls these, the user chose it)
 * Not ported: dump_stack_trace, log, log_call (one line in Server.LOG when the answer is made up)
 * Not ported: afterHookedMethod (each hook's body is the static method of the same name here)
 * Not ported: getActiveNetworkInfoForUid, getProvisioningOrActiveNetworkInfo, getActiveNetworkInfoUnfiltered (hidden from apps, a game does not call them)
 * Not ported: createWifiSsid, createWifiInfo, IPInfo, getIPInfo, isIPv4Address, netmask_to_hex, InetAddress_to_hex, createDhcpInfo (WifiManager's answers, a game asks ConnectivityManager)
 * Not ported: hook_method, handleLoadPackage (the calls are rewritten in the dex, nothing is hooked)
 *
 * The constructor and setDetailedState are public since Android 11, and
 * present before. setIsAvailable is hidden: its field is set by reflection,
 * and left alone where that is refused.
 * https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/NetworkInfo.java#L167-L179
 *
 * Copyright (C) 2014 lemonsqueeze, GPL-3.0-or-later.
 */

import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.lang.reflect.Field;

public final class Net {

    private static volatile boolean logged;

    private Net() {
    }

    // getActiveNetworkInfo()
    public static NetworkInfo getActiveNetworkInfo(ConnectivityManager manager) {
        return doit_networkinfo(manager.getActiveNetworkInfo());
    }

    // getNetworkInfo(int)
    public static NetworkInfo getNetworkInfo(ConnectivityManager manager, int network_type) {
        NetworkInfo result = manager.getNetworkInfo(network_type);
        return network_type == ConnectivityManager.TYPE_WIFI ? doit_networkinfo(result) : result;
    }

    // getAllNetworkInfo()
    public static NetworkInfo[] getAllNetworkInfo(ConnectivityManager manager) {
        return doit_allnetworkinfo(manager.getAllNetworkInfo());
    }

    // isActiveNetworkMetered()
    public static boolean isActiveNetworkMetered(ConnectivityManager manager) {
        return false;
    }

    static NetworkInfo doit_networkinfo(NetworkInfo result) {
        // if we're already on wifi don't interfere.
        if (result != null && result.getType() == ConnectivityManager.TYPE_WIFI && result.isConnected()) {
            return result;
        }
        return getFakeNetworkInfo();
    }

    private static NetworkInfo[] push(NetworkInfo[] array, NetworkInfo item) {
        NetworkInfo[] longer = new NetworkInfo[array.length + 1];
        System.arraycopy(array, 0, longer, 0, array.length);
        longer[array.length] = item;
        return longer;
    }

    static NetworkInfo[] doit_allnetworkinfo(NetworkInfo[] networks) {
        if (networks == null) {
            networks = new NetworkInfo[0];
        }
        int i; // wifi networkinfo index
        boolean wifi_found = false;
        for (i = 0; i < networks.length; i++) {
            if (networks[i] != null && networks[i].getType() == ConnectivityManager.TYPE_WIFI) {
                wifi_found = true;
                break;
            }
        }

        // if we're already on wifi don't interfere.
        if (wifi_found && networks[i].isConnected()) {
            return networks;
        }

        if (wifi_found) {
            networks[i] = getFakeNetworkInfo();
        } else {
            networks = push(networks, getFakeNetworkInfo());
        }
        return networks;
    }

    static NetworkInfo getFakeNetworkInfo() {
        if (!logged) {
            logged = true;
            Server.LOG.info("No network: the game is told it is on Wi-Fi, its servers answer on loopback");
        }
        return createNetworkInfo(ConnectivityManager.TYPE_WIFI, true);
    }

    static NetworkInfo createNetworkInfo(int type, boolean connected) {
        NetworkInfo networkInfo = new NetworkInfo(type, 0, "WIFI", null);
        networkInfo.setDetailedState(connected ? NetworkInfo.DetailedState.CONNECTED : NetworkInfo.DetailedState.DISCONNECTED, null, null);
        try {
            Field available = NetworkInfo.class.getDeclaredField("mIsAvailable");
            available.setAccessible(true);
            available.setBoolean(networkInfo, connected);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // isAvailable stays false, isConnected is what games read.
        }
        return networkInfo;
    }
}
