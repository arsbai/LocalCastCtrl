package com.localcast.pro.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PairingCodeTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test public void roundTripsReceiverCode() {
        PairingCode code = PairingCode.parse(PairingCode.create("192.168.1.12", TOKEN));
        assertEquals("192.168.1.12", code.ip);
        assertEquals(TOKEN, code.token);
    }

    @Test public void rejectsUnexpectedOrUnusableCodes() {
        assertNull(PairingCode.parse("https://example.com/?token=" + TOKEN));
        assertNull(PairingCode.parse("localcast://pair/0.0.0.0/" + TOKEN));
        assertNull(PairingCode.parse("localcast://pair/192.168.1.12/short"));
        assertNull(PairingCode.parse("localcast://pair/192.168.1.12/" + TOKEN + "/extra"));
    }
}
