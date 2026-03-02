import java.io.*;
import java.net.*;
import java.util.*;

public class Receiver {

    private static final int MOD = 128;

    public static void main(String[] args) throws Exception {

        InetAddress senderIP = InetAddress.getByName(args[0]);
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int RN = Integer.parseInt(args[4]);

        DatagramSocket socket = new DatagramSocket(rcvDataPort);
        FileOutputStream fos = new FileOutputStream(outputFile);

        int ackCount = 0;
        int expectedSeq = 1;
        int windowSize = 128;

        Map<Integer, DSPacket> buffer = new HashMap<>();

        // ================= HANDSHAKE =================
        while (true) {
            DSPacket packet = receivePacket(socket);

            if (packet.getType() == DSPacket.TYPE_SOT) {
                ackCount++;
                if (!ChaosEngine.shouldDrop(ackCount, RN)) {
                    sendACK(socket, senderIP,
                            senderAckPort, 0);
                }
                break;
            }
        }

        // ================= DATA PHASE =================
        while (true) {

            DSPacket packet = receivePacket(socket);

            if (packet.getType() == DSPacket.TYPE_EOT) {

                ackCount++;
                if (!ChaosEngine.shouldDrop(ackCount, RN)) {
                    sendACK(socket, senderIP,
                            senderAckPort,
                            packet.getSeqNum());
                }
                break;
            }

            if (packet.getType() == DSPacket.TYPE_DATA) {

                int seq = packet.getSeqNum();
                int diff = (seq - expectedSeq + MOD) % MOD;

                if (diff < windowSize) {

                    if (!buffer.containsKey(seq)) {
                        buffer.put(seq, packet);
                    }

                    while (buffer.containsKey(expectedSeq)) {
                        DSPacket p = buffer.remove(expectedSeq);
                        fos.write(p.getPayload());
                        expectedSeq = (expectedSeq + 1) % MOD;
                    }
                }

                int ackNum =
                        (expectedSeq - 1 + MOD) % MOD;

                ackCount++;
                if (!ChaosEngine.shouldDrop(ackCount, RN)) {
                    sendACK(socket, senderIP,
                            senderAckPort, ackNum);
                }
            }
        }

        fos.close();
        socket.close();
    }

    private static void sendACK(DatagramSocket socket,
                                InetAddress ip,
                                int port,
                                int seq) throws Exception {

        DSPacket ack =
                new DSPacket(DSPacket.TYPE_ACK, seq, null);

        byte[] data = ack.toBytes();
        DatagramPacket dp =
                new DatagramPacket(data, data.length, ip, port);
        socket.send(dp);
    }

    private static DSPacket receivePacket(DatagramSocket socket)
            throws Exception {

        byte[] buffer =
                new byte[DSPacket.MAX_PACKET_SIZE];

        DatagramPacket dp =
                new DatagramPacket(buffer, buffer.length);

        socket.receive(dp);
        return new DSPacket(dp.getData());
    }
}