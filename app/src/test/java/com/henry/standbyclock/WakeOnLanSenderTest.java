package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WakeOnLanSenderTest {
    @Test
    public void magicPacketHasHeaderAndSixteenMacCopies() {
        byte[] packet = WakeOnLanSender.buildMagicPacket("8C:32:23:49:15:3F");
        byte[] mac = {
                (byte) 0x8C, 0x32, 0x23, 0x49, 0x15, 0x3F
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
