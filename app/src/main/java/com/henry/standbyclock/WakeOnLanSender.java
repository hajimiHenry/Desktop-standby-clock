package com.henry.standbyclock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * 发送标准的网络唤醒（Wake-on-LAN）魔术包，用来远程开机局域网里的台式机。
 *
 * <p>魔术包的格式是固定的：6 个字节的 0xFF 开头，后面把目标网卡的 MAC 地址
 * 连续重复 16 遍，共 6 + 96 = 102 字节。网卡在关机状态下仍保持低功耗监听，
 * 收到含自己 MAC 的这种包就触发开机。包内容不加密也无需认证——WOL 就是这么设计的，
 * 靠"外网进不来局域网广播"来保证安全。
 */
final class WakeOnLanSender {
    /** MAC 地址长度，固定 6 字节。 */
    private static final int MAC_BYTES = 6;
    /** 协议规定 MAC 在包体里重复 16 次。 */
    private static final int MAC_REPETITIONS = 16;
    /** 连发 3 次。UDP 不保证送达，重发几次提高成功率；重复唤醒无副作用。 */
    private static final int SEND_REPETITIONS = 3;

    private WakeOnLanSender() {
    }

    /**
     * 往指定的广播地址发魔术包。
     *
     * @param broadcastHost 一般填网段的定向广播地址（如 192.168.1.255）。
     *                      必须用广播是因为目标机器关着机，没有 IP 也不在 ARP 表里，
     *                      只能靠广播让整个网段的网卡都收到。
     */
    static void send(String macAddress, String broadcastHost, int port) throws IOException {
        byte[] payload = buildMagicPacket(macAddress);
        InetAddress destination = InetAddress.getByName(broadcastHost);
        try (DatagramSocket socket = new DatagramSocket()) {
            // 不打开这个开关，系统会拒绝往广播地址发包。
            socket.setBroadcast(true);
            DatagramPacket packet = new DatagramPacket(
                    payload, payload.length, destination, port);
            for (int i = 0; i < SEND_REPETITIONS; i++) {
                socket.send(packet);
            }
        }
    }

    /** 按协议拼出 102 字节的魔术包：6 个 0xFF + MAC × 16。 */
    static byte[] buildMagicPacket(String macAddress) {
        byte[] mac = parseMacAddress(macAddress);
        byte[] packet = new byte[MAC_BYTES + MAC_BYTES * MAC_REPETITIONS];
        for (int i = 0; i < MAC_BYTES; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int repetition = 0; repetition < MAC_REPETITIONS; repetition++) {
            System.arraycopy(mac, 0, packet, MAC_BYTES + repetition * MAC_BYTES, MAC_BYTES);
        }
        return packet;
    }

    /**
     * 把 "AA:BB:CC:DD:EE:FF" 或 "AA-BB-..." 这样的字符串解析成 6 个字节。
     * 格式不对就抛 IllegalArgumentException——配置写错了要早点暴露，
     * 而不是默默发一个没人认识的无效包。
     */
    private static byte[] parseMacAddress(String macAddress) {
        if (macAddress == null) {
            throw new IllegalArgumentException("MAC address is required");
        }
        String compact = macAddress.replace(":", "").replace("-", "");
        if (!compact.matches("[0-9A-Fa-f]{12}")) {
            throw new IllegalArgumentException("Invalid MAC address: " + macAddress);
        }
        byte[] bytes = new byte[MAC_BYTES];
        for (int i = 0; i < MAC_BYTES; i++) {
            // 每两个十六进制字符转成一个字节。先按 int 解析再强转，
            // 因为 Byte.parseByte 处理不了 0x80 以上的值（会当成溢出）。
            bytes[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
