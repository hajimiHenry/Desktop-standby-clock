package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WakeOnLanSenderTest {
    @Test
    public void magicPacketHasHeaderAndSixteenMacCopies() {
        byte[] packet = WakeOnLanSender.buildMagicPacket("00:11:22:33:44:55");
        byte[] mac = {
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55
        };

        assertEquals(102, packet.length);
        for (int i = 0; i < 6; i++) {
            assertEquals((byte) 0xFF, packet[i]);
        }
        for (int repetition = 0; repetition < 16; repetition++) {
            for (int i = 0; i < mac.length; i++) {
                assertEquals(mac[i], packet[6 + repetition * 6 + i]);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidMacAddressIsRejected() {
        WakeOnLanSender.buildMagicPacket("not-a-mac");
    }
}
