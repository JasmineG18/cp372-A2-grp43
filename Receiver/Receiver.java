import java.io.*;
import java.net.*;
import java.util.*;

public class Receiver {

    // Maximum sequence number range (modulo arithmetic)
    private static final int MOD = 128;

    public static void main(String[] args) throws Exception {

        // ================= ARGUMENTS =================
        // args[0] = Sender IP address
        // args[1] = Port where sender receives ACKs
        // args[2] = Port where receiver listens for data
        // args[3] = Output file name
        // args[4] = RN (drop frequency for ACK simulation)

        InetAddress senderIP = InetAddress.getByName(args[0]);
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int RN = Integer.parseInt(args[4]);

        // Socket used to receive packets from the sender
        DatagramSocket socket = new DatagramSocket(rcvDataPort);

        // File where received data will be written
        FileOutputStream fos = new FileOutputStream(outputFile);

        // Counter used to simulate ACK dropping
        int ackCount = 0;

        // Next sequence number expected by the receiver
        int expectedSeq = 1;

        // Receiver window size
        int windowSize = 128;

        // Buffer to temporarily store out-of-order packets
        Map<Integer, DSPacket> buffer = new HashMap<>();


        // ================= HANDSHAKE =================
        // Wait for the Start Of Transmission (SOT) packet
        while (true) {

            DSPacket packet = receivePacket(socket);

            // If SOT packet arrives
            if (packet.getType() == DSPacket.TYPE_SOT) {

                ackCount++;

                // Simulate possible ACK loss using ChaosEngine
                if (!ChaosEngine.shouldDrop(ackCount, RN)) {

                    // Send ACK with sequence number 0
                    sendACK(socket, senderIP, senderAckPort, 0);
                }

                // Exit handshake loop and start data phase
                break;
            }
        }


        // ================= DATA PHASE =================
        // Continue receiving packets until End Of Transmission
        while (true) {

            DSPacket packet = receivePacket(socket);

            // If End Of Transmission packet received
            if (packet.getType() == DSPacket.TYPE_EOT) {

                ackCount++;

                // Send final ACK unless it is intentionally dropped
                if (!ChaosEngine.shouldDrop(ackCount, RN)) {

                    sendACK(socket, senderIP,
                            senderAckPort,
                            packet.getSeqNum());
                }

                // End receiving
                break;
            }


            // If the packet is a DATA packet
            if (packet.getType() == DSPacket.TYPE_DATA) {

                int seq = packet.getSeqNum();

                // Calculate how far the packet is from expected sequence
                int diff = (seq - expectedSeq + MOD) % MOD;

                // Check if the packet falls inside the receiver window
                if (diff < windowSize) {

                    // Store packet if it has not already been received
                    if (!buffer.containsKey(seq)) {
                        buffer.put(seq, packet);
                    }

                    // Deliver packets to the file in order
                    while (buffer.containsKey(expectedSeq)) {

                        DSPacket p = buffer.remove(expectedSeq);

                        // Write payload data to output file
                        fos.write(p.getPayload());

                        // Move expected sequence forward
                        expectedSeq = (expectedSeq + 1) % MOD;
                    }
                }

                // ACK number is the last correctly received packet
                int ackNum = (expectedSeq - 1 + MOD) % MOD;

                ackCount++;

                // Send ACK unless ChaosEngine decides to drop it
                if (!ChaosEngine.shouldDrop(ackCount, RN)) {

                    sendACK(socket, senderIP,
                            senderAckPort, ackNum);
                }
            }
        }

        // Close file and socket after transmission ends
        fos.close();
        socket.close();
    }


    // ================= SEND ACK FUNCTION =================
    // Creates and sends an ACK packet to the sender
    private static void sendACK(DatagramSocket socket,
                                InetAddress ip,
                                int port,
                                int seq) throws Exception {

        // Create ACK packet with given sequence number
        DSPacket ack =
                new DSPacket(DSPacket.TYPE_ACK, seq, null);

        // Convert packet to byte array
        byte[] data = ack.toBytes();

        // Wrap bytes into UDP datagram
        DatagramPacket dp =
                new DatagramPacket(data, data.length, ip, port);

        // Send packet
        socket.send(dp);
    }


    // ================= RECEIVE PACKET FUNCTION =================
    // Receives a UDP packet and converts it into a DSPacket object
    private static DSPacket receivePacket(DatagramSocket socket)
            throws Exception {

        // Buffer large enough for the maximum packet size
        byte[] buffer =
                new byte[DSPacket.MAX_PACKET_SIZE];

        DatagramPacket dp =
                new DatagramPacket(buffer, buffer.length);

        // Wait for incoming packet
        socket.receive(dp);

        // Convert received bytes into a DSPacket object
        return new DSPacket(dp.getData());
    }
}