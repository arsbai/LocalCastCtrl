package com.localcast.pro.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * 网络工具类
 * 获取本机IP地址、判断网络状态、网络相关操作
 */
public class NetworkUtils {

    /**
     * 获取本机WiFi局域网IPv4地址
     */
    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                // 过滤回环和未启用的接口
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    // 只获取IPv4地址，排除IPv6
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String ip = address.getHostAddress();
                        // 过滤非局域网IP（通常是192.168.x.x 或 10.x.x.x 或 172.16-31.x.x）
                        if (isPrivateIp(ip)) {
                            return ip;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            Logger.e("NetworkUtils", "Failed to get local IP", e);
        }
        return "0.0.0.0";
    }

    /** Prefer the Wi-Fi network itself: the default network may be cellular or VPN. */
    public static String getLocalIpAddress(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties links = cm.getLinkProperties(network);
                if (links == null) continue;
                for (LinkAddress link : links.getLinkAddresses()) {
                    InetAddress address = link.getAddress();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) return address.getHostAddress();
                }
            }
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && isWifiConnected(context)) {
            WifiInfo info = wifi.getConnectionInfo();
            if (info != null && info.getIpAddress() != 0) return intToIp(info.getIpAddress());
        }
        // Some devices omit LinkProperties. Only inspect Wi-Fi interfaces here,
        // since a cellular/VPN address cannot be used by the other phone.
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                String name = nic.getName().toLowerCase(java.util.Locale.US);
                if (!nic.isUp() || !(name.startsWith("wlan") || name.startsWith("wifi")
                        || name.startsWith("swlan"))) continue;
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) return address.getHostAddress();
                }
            }
        } catch (SocketException e) {
            Logger.e("NetworkUtils", "Failed to inspect Wi-Fi interface", e);
        }
        return "0.0.0.0";
    }

    /**
     * 判断是否为私有局域网IP
     */
    private static boolean isPrivateIp(String ip) {
        if (ip == null) return false;
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);

            // 10.x.x.x
            if (first == 10) return true;
            // 172.16.x.x - 172.31.x.x
            if (first == 172 && second >= 16 && second <= 31) return true;
            // 192.168.x.x
            if (first == 192 && second == 168) return true;

            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 获取WiFi广播地址
     */
    public static String getBroadcastAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            if (dhcpInfo != null && dhcpInfo.ipAddress != 0 && dhcpInfo.netmask != 0) {
                return intToIp(dhcpInfo.ipAddress | ~dhcpInfo.netmask);
            }
        }
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties links = cm.getLinkProperties(network);
                if (links == null) continue;
                for (LinkAddress link : links.getLinkAddresses()) {
                    if (!(link.getAddress() instanceof Inet4Address)) continue;
                    int prefix = link.getPrefixLength();
                    if (prefix < 1 || prefix > 30) continue;
                    byte[] ip = link.getAddress().getAddress();
                    long value = ((ip[0] & 255L) << 24) | ((ip[1] & 255L) << 16)
                            | ((ip[2] & 255L) << 8) | (ip[3] & 255L);
                    long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
                    long broadcast = value | (~mask & 0xffffffffL);
                    return ((broadcast >> 24) & 255) + "." + ((broadcast >> 16) & 255)
                            + "." + ((broadcast >> 8) & 255) + "." + (broadcast & 255);
                }
            }
        }
        return "255.255.255.255";
    }

    /**
     * 获取网关地址
     */
    public static String getGatewayAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            if (dhcpInfo != null) {
                return intToIp(dhcpInfo.gateway);
            }
        }
        return null;
    }

    /**
     * 判断当前是否连接WiFi
     */
    public static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
            return false;
        } else {
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();
            return networkInfo != null && networkInfo.isConnected() &&
                    networkInfo.getType() == ConnectivityManager.TYPE_WIFI;
        }
    }

    /**
     * 获取WiFi SSID
     */
    public static String getWifiSSID(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                String ssid = wifiInfo.getSSID();
                // 去掉引号
                if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                return WifiManager.UNKNOWN_SSID.equals(ssid) ? null : ssid;
            }
        }
        return null;
    }

    /**
     * int IP 转 String
     */
    private static String intToIp(int ip) {
        return (ip & 0xFF) + "." +
                ((ip >> 8) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." +
                ((ip >> 24) & 0xFF);
    }

    /**
     * 验证IP地址格式
     */
    public static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String pattern = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$";
        return ip.matches(pattern);
    }

    /** Allow local QR endpoints only while Wi-Fi is connected. */
    public static boolean isOnSameWifi(Context context, String peerIp) {
        if (!isValidIp(peerIp) || !isWifiConnected(context)) return false;
        try {
            byte[] peer = InetAddress.getByName(peerIp).getAddress();
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                    LinkProperties props = cm.getLinkProperties(network);
                    if (props == null) continue;
                    for (LinkAddress link : props.getLinkAddresses()) {
                        if (!(link.getAddress() instanceof Inet4Address)) continue;
                        byte[] own = link.getAddress().getAddress();
                        int prefix = link.getPrefixLength();
                        if (prefix < 8 || prefix > 32) continue;
                        boolean match = true;
                        for (int bit = 0; bit < prefix; bit++) {
                            int mask = 0x80 >> (bit % 8);
                            if ((own[bit / 8] & mask) != (peer[bit / 8] & mask)) {
                                match = false;
                                break;
                            }
                        }
                        if (match) return true;
                    }
                }
            }
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = wifi != null ? wifi.getDhcpInfo() : null;
            if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                int peerLittleEndian = (peer[0] & 255) | ((peer[1] & 255) << 8)
                        | ((peer[2] & 255) << 16) | ((peer[3] & 255) << 24);
                if ((dhcp.ipAddress & dhcp.netmask) == (peerLittleEndian & dhcp.netmask))
                    return true;
            }
            // Routed private subnets can still belong to one Wi-Fi network.
            return isPrivateIp(peerIp);
        } catch (Exception ignored) {
            return false;
        }
    }
}
