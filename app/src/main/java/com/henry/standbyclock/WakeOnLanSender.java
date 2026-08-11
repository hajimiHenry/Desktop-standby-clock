package com.henry.standbyclock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Sends a standard Wake-on-LAN magic packet to a directed LAN broadcast address. */
final class WakeOnLanSender {
    private static final int MAC_BYTES = 6;
    private static final int MAC_REPETITIONS = 16;
    private static final int SEND_REPETITIONS = 3;

    private WakeOnLanSender() {
    }

    static void send(String macAddress, String broadcastHost, int port) throws IOException {
        byte[] payload = buildMagicPacket(macAddress);
        InetAddress destination = InetAddress.getByName(broadcastHost);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            DatagramPacket packet = new DatagramPacket(
                    payload, payload.length, destination, port);
            for (int i = 0; i < SEND_REPETITIONS; i++) {
                socket.send(packet);
            }
        }
    }

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
            bytes[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
