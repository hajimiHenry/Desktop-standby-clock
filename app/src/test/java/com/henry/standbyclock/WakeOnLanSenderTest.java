package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 网络唤醒魔术包的构造测试。只测包体拼装，不真的发网络请求。 */
public final class WakeOnLanSenderTest {
    /**
     * 逐字节核对魔术包的标准格式：
     * 总长必须是 102 字节（6 + 6×16），前 6 个字节全是 0xFF，
     * 之后是 MAC 地址原样重复 16 遍。
     */
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

    /** MAC 地址格式不对必须直接抛异常，而不是默默发一个无效包出去。 */
    @Test(expected = IllegalArgumentException.class)
    public void invalidMacAddressIsRejected() {
        WakeOnLanSender.buildMagicPacket("not-a-mac");
    }
}
