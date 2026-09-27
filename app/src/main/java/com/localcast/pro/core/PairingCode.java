package com.localcast.pro.core;

import com.localcast.pro.utils.NetworkUtils;

/** Small, strictly parsed QR payload used to verify the chosen receiver. */
public final class PairingCode {
    private static final String PREFIX = "localcast://pair/";
    public final String ip;
    public final String token;

    private PairingCode(String ip, String token) {
        this.ip = ip;
        this.token = token;
    }

    public static String create(String ip, String token) {
        if (!valid(ip, token)) throw new IllegalArgumentException("Invalid pairing endpoint");
        return PREFIX + ip + "/" + token;
    }

    public static PairingCode parse(String value) {
        if (value == null || !value.startsWith(PREFIX) || value.length() > 128) return null;
        String[] fields = value.substring(PREFIX.length()).split("/", -1);
        if (fields.length != 2 || !valid(fields[0], fields[1])) return null;
        return new PairingCode(fields[0], fields[1]);
    }

    private static boolean valid(String ip, String token) {
        if (!NetworkUtils.isValidIp(ip) || "0.0.0.0".equals(ip)
                || token == null || !token.matches("[0-9a-f]{32}")) return false;
        int first = Integer.parseInt(ip.substring(0, ip.indexOf('.')));
        return first > 0 && first < 224 && first != 127 && first != 169;
    }
}
