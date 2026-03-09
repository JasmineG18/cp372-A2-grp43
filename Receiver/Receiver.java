import java.io.*;
import java.net.*;
import java.util.*;

/*
 * Receiver program for DS-FTP file transfer protocol.
 * 
 * Responsibilities:
 * - Establish connection with sender using SOT handshake
 * - Receive DATA packets and maintain correct order
 * - Simulate ACK loss using ChaosEngine
 * - Send ACKs for received packets
 * - Write received payloads to output file
 * - Terminate connection using EOT handshake
 */

public class Receiver {

    // Sequence numbers wrap around modulo 128
    private static final int MOD = 128;

    public static void main(String[] args) throws Exception {

        // Parse command line arguments
        InetAddress senderIP = InetAddress.getByName(args[0]);
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int RN = Integer.parseInt(args[4]); // Reliability parameter (ACK loss frequency)

        // Create UDP socket to receive data packets
        DatagramSocket socket = new DatagramSocket(rcvDataPort);

        // File output stream for writing received data
        FileOutputStream fos = new FileOutputStream(outputFile);

        // Counter used by ChaosEngine to simulate ACK loss
        int ackCount = 0;

        // Expected next sequence number
        int expectedSeq = 1;

        // Receiver window size (set to max to support both protocols)
        int windowSize = 128;

        // Buffer to store out-of-order packets
        Map<Integer, DSPacket> buffer = new HashMap<>();

        System.out.println("Receiver started. Waiting for SOT...");

        // ================= HANDSHAKE =================
        // Wait for Start-of-Transmission packet from sender
        while (true) {

            DSPacket packet = receivePacket(socket);

            if (packet.getType() == DSPacket.TYPE_SOT) {

                System.out.println("Receiver: SOT received");

                ackCount++;

                // Simulate ACK loss using ChaosEngine
                if (!ChaosEngine.shouldDrop(ackCount, RN)) {
                    System.out.println("Receiver: Sending ACK for SOT");
                    sendACK(socket, senderIP, senderAckPort, 0);
                } else {
                    System.out.println("Receiver: Simulating ACK loss (SOT)");
                }

                break;
            }
        }

        // ================= DATA PHASE =================
        // Receive data packets until EOT is received
        while (true) {

            DSPacket packet = receivePacket(socket);

            // Check for End-of-Transmission packet
            if (packet.getType() == DSPacket.TYPE_EOT) {

                System.out.println("Receiver: EOT received");

                ackCount++;

                // Send ACK for EOT (unless simulated loss)
                if (!ChaosEngine.shouldDrop(ackCount, RN)) {
                    System.out.println("Receiver: Sending ACK for EOT");
                    sendACK(socket, senderIP, senderAckPort,
                            packet.getSeqNum());
                } else {
                    System.out.println("Receiver: Simulating ACK loss (EOT)");
                }

                break;
            }

            // Process DATA packets
            if (packet.getType() == DSPacket.TYPE_DATA) {

                int seq = packet.getSeqNum();

                System.out.println("Receiver: Packet received SEQ = " + seq);

                // Calculate relative distance from expected sequence
                int diff = (seq - expectedSeq + MOD) % MOD;

                // Accept packet if it falls inside receiver window
                if (diff < windowSize) {

                    // Store packet if not already buffered
                    if (!buffer.containsKey(seq)) {
                        buffer.put(seq, packet);
                    }

                    // Deliver buffered packets in order
                    while (buffer.containsKey(expectedSeq)) {

                        DSPacket p = buffer.remove(expectedSeq);

                        // Write payload to output file
                        fos.write(p.getPayload());

                        // Move expected sequence forward
                        expectedSeq = (expectedSeq + 1) % MOD;
                    }
                }

                // ACK the last correctly received in-order packet
                int ackNum = (expectedSeq - 1 + MOD) % MOD;

                ackCount++;

                if (!ChaosEngine.shouldDrop(ackCount, RN)) {

                    System.out.println("Receiver: Sending ACK = " + ackNum);

                    sendACK(socket, senderIP, senderAckPort, ackNum);

                } else {

                    System.out.println("Receiver: Simulating ACK loss for SEQ = " + ackNum);
                }
            }
        }

        // Close file and socket after transfer completes
        fos.close();
        socket.close();

        System.out.println("Receiver: File transfer complete.");
    }

    /*
     * Sends an ACK packet to the sender with the specified sequence number.
     */
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

    /*
     * Receives a packet from the UDP socket and converts it into a DSPacket object.
     */
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