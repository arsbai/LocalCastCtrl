package com.localcast.pro.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NetworkUtilsTest {

    @Test
    public void acceptsValidIpv4Addresses() {
        assertTrue(NetworkUtils.isValidIp("192.168.1.10"));
        assertTrue(NetworkUtils.isValidIp("10.0.0.1"));
        assertTrue(NetworkUtils.isValidIp("255.255.255.255"));
    }

    @Test
    public void rejectsMalformedOrOutOfRangeAddresses() {
        assertFalse(NetworkUtils.isValidIp(null));
        assertFalse(NetworkUtils.isValidIp(""));
        assertFalse(NetworkUtils.isValidIp("192.168.1"));
        assertFalse(NetworkUtils.isValidIp("192.168.1.256"));
        assertFalse(NetworkUtils.isValidIp("receiver.local"));
    }

    @Test
    public void hotspotClientMatchesHostSubnetButNotOtherPrivateSubnet() {
        byte[] host = { (byte) 192, (byte) 168, 43, 1 };
        byte[] client = { (byte) 192, (byte) 168, 43, 27 };
        byte[] elsewhere = { (byte) 192, (byte) 168, 44, 27 };
        assertTrue(NetworkUtils.sameSubnet(host, client, 24));
        assertFalse(NetworkUtils.sameSubnet(host, elsewhere, 24));
        assertFalse(NetworkUtils.sameSubnet(host, client, 32));
    }

    @Test
    public void sharedAddressSpaceCanBeOnTheSameWifiSubnet() {
        byte[] one = { 100, 64, 12, 34 };
        byte[] two = { 100, 64, 12, 99 };
        assertTrue(NetworkUtils.sameSubnet(one, two, 24));
        assertFalse(NetworkUtils.sameSubnet(one, two, 32));
    }
}
