import java.io.*;
import java.net.*;
import java.util.*;

/*
 * Sender program for DS-FTP protocol.
 * Supports two reliable data transfer protocols:
 * 1. Stop-and-Wait
 * 2. Go-Back-N
 *
 * The sender reads a file, divides it into packets, and sends them
 * to the receiver using UDP sockets. Reliability is implemented
 * using acknowledgements (ACKs) and retransmissions on timeout.
 */

public class Sender {

    // Sequence numbers wrap around modulo 128
    private static final int MOD = 128;

    public static void main(String[] args) throws Exception {

        // Validate command-line arguments
        if (args.length < 5) {
            System.out.println("Usage: java Sender <IP> <RCV_DATA_PORT> <SENDER_ACK_PORT> <file> <timeout> [windowSize]");
            return;
        }

        // Receiver IP address
        InetAddress rcvIP = InetAddress.getByName(args[0]);

        // Receiver data port
        int rcvDataPort = Integer.parseInt(args[1]);

        // Sender port used to receive ACKs
        int senderAckPort = Integer.parseInt(args[2]);

        // File to send
        String inputFile = args[3];

        // Timeout value for waiting ACKs
        int timeout = Integer.parseInt(args[4]);

        // Optional window size → if provided we use Go-Back-N
        Integer windowSize = args.length == 6 ? Integer.parseInt(args[5]) : null;

        // Create UDP socket and configure timeout
        DatagramSocket socket = new DatagramSocket(senderAckPort);
        socket.setSoTimeout(timeout);

        // Start time for measuring transmission time
        long startTime = System.nanoTime();

        // ================= HANDSHAKE =================
        // Send Start-of-Transmission (SOT) packet to initiate transfer
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        System.out.println("Sender: Sending SOT");

        // Keep sending SOT until receiver acknowledges
        while (true) {
            sendPacket(socket, sot, rcvIP, rcvDataPort);
            try {
                DSPacket ack = receivePacket(socket);

                // Expect ACK with sequence number 0
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("Sender: ACK received for SOT");
                    break;
                }

            } catch (SocketTimeoutException e) {
                System.out.println("Sender: Timeout! Resending SOT");
            }
        }

        // Choose protocol depending on presence of window size
        if (windowSize == null)
            stopAndWait(socket, rcvIP, rcvDataPort, inputFile);
        else
            goBackN(socket, rcvIP, rcvDataPort, inputFile, windowSize);

        // Close socket after transfer
        socket.close();

        // Print total transmission time
        printTime(startTime);
    }

    // ================= STOP-AND-WAIT PROTOCOL =================
    /*
     * Sends one packet at a time.
     * Sender waits for ACK before sending the next packet.
     * If timeout occurs, the packet is retransmitted.
     */
    private static void stopAndWait(DatagramSocket socket,
                                    InetAddress ip,
                                    int port,
                                    String fileName) throws Exception {

        System.out.println("Sender: Starting Stop-and-Wait file transfer...");

        // Open file for reading
        FileInputStream fis = new FileInputStream(fileName);

        // Buffer for reading file chunks
        byte[] buffer = new byte[DSPacket.MAX_PAYLOAD_SIZE];

        // First data packet sequence number (0 was used by SOT)
        int seq = 1;

        // Track last data packet sequence number for EOT
        int lastDataSeq = 0;

        int read;

        // Read file chunk-by-chunk
        while ((read = fis.read(buffer)) != -1) {

            System.out.println("\nSender: Sending DATA packet SEQ = " + seq);

            // Copy only actual bytes read into payload
            byte[] payload = Arrays.copyOf(buffer, read);

            // Create DATA packet
            DSPacket packet = new DSPacket(DSPacket.TYPE_DATA, seq, payload);

            int timeoutCount = 0;
            boolean ackReceived = false;

            // Send packet for the first time
            sendPacket(socket, packet, ip, port);

            // Wait until correct ACK received
            while (!ackReceived) {

                try {

                    // Receive ACK packet
                    DSPacket ack = receivePacket(socket);

                    System.out.println("Sender: ACK received = " + ack.getSeqNum());

                    // Check if ACK matches expected sequence
                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == seq) {

                        System.out.println("Sender: Correct ACK received for SEQ = " + seq);

                        ackReceived = true;

                        // Update last sent sequence
                        lastDataSeq = seq;

                        // Increment sequence number with wrap-around
                        seq = (seq + 1) % MOD;
                    }
                    else {
                        // Ignore incorrect or duplicate ACKs
                        System.out.println("Sender: Wrong ACK ignored");
                    }

                }
                catch (SocketTimeoutException e) {

                    timeoutCount++;

                    System.out.println("Sender: Timeout #" + timeoutCount +
                            " for packet SEQ = " + seq);

                    // If 3 consecutive timeouts occur, abort transfer
                    if (timeoutCount >= 3) {
                        System.out.println("Unable to transfer file.");
                        System.exit(0);
                    }

                    // Retransmit the same packet
                    System.out.println("Sender: Retransmitting packet SEQ = " + seq);
                    sendPacket(socket, packet, ip, port);
                }
            }
        }

        // Close file
        fis.close();

        System.out.println("\nSender: File data transfer completed!");

        // ================= SEND EOT =================
        // End-of-Transmission packet
        DSPacket eot = new DSPacket(
                DSPacket.TYPE_EOT,
                (lastDataSeq + 1) % MOD,
                null);

        System.out.println("Sender: Sending EOT");

        sendPacket(socket, eot, ip, port);

        // Wait for final ACK
        waitForAck(socket);
    }

    // ================= GO-BACK-N PROTOCOL =================
    /*
     * Allows sending multiple packets within a window.
     * If timeout occurs, all packets in the window are retransmitted.
     */
    private static void goBackN(DatagramSocket socket,
                                InetAddress ip,
                                int port,
                                String fileName,
                                int windowSize) throws Exception {

        FileInputStream fis = new FileInputStream(fileName);
        byte[] buffer = new byte[DSPacket.MAX_PAYLOAD_SIZE];

        int base = 1;      // First unacknowledged packet
        int nextSeq = 1;   // Next sequence number to send
        int lastDataSeq = 0;
        boolean fileEnded = false;

        // Store packets currently in the window
        Map<Integer, DSPacket> window = new HashMap<>();

        while (!fileEnded || base != nextSeq) {

            // Fill window with new packets
            while (!fileEnded && ((nextSeq - base + MOD) % MOD) < windowSize) {

                int read = fis.read(buffer);

                if (read == -1) {
                    fileEnded = true;
                    break;
                }

                byte[] payload = Arrays.copyOf(buffer, read);

                DSPacket packet = new DSPacket(DSPacket.TYPE_DATA, nextSeq, payload);

                window.put(nextSeq, packet);

                sendPacket(socket, packet, ip, port);

                lastDataSeq = nextSeq;

                nextSeq = (nextSeq + 1) % MOD;
            }

            try {

                // Wait for ACK
                DSPacket ack = receivePacket(socket);

                System.out.println("Sender: ACK received = " + ack.getSeqNum());

                int ackSeq = ack.getSeqNum();

                // Slide window forward
                if (((ackSeq - base + MOD) % MOD) < windowSize) {
                    base = (ackSeq + 1) % MOD;
                }

            }
            catch (SocketTimeoutException e) {

                // Timeout → retransmit entire window
                System.out.println("Sender: Timeout! Retransmitting window from SEQ = " + base);

                int seq = base;

                while (seq != nextSeq) {
                    sendPacket(socket, window.get(seq), ip, port);
                    seq = (seq + 1) % MOD;
                }
            }
        }

        fis.close();

        // Send End-of-Transmission packet
        DSPacket eot = new DSPacket(
                DSPacket.TYPE_EOT,
                (lastDataSeq + 1) % MOD,
                null);

        System.out.println("Sender: Sending EOT");

        sendPacket(socket, eot, ip, port);

        waitForAck(socket);
    }

    // ================= HELPER METHODS =================

    // Send a DSPacket using UDP socket
    private static void sendPacket(DatagramSocket socket,
                                   DSPacket packet,
                                   InetAddress ip,
                                   int port) throws Exception {

        byte[] data = packet.toBytes();

        socket.send(new DatagramPacket(data, data.length, ip, port));

        // Print message for DATA packets
        if (packet.getType() == DSPacket.TYPE_DATA)
            System.out.println("Sender: Sent DATA SEQ = " + packet.getSeqNum());
    }

    // Receive a packet from socket and convert to DSPacket
    private static DSPacket receivePacket(DatagramSocket socket) throws Exception {

        byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE];

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);

        socket.receive(dp);

        return new DSPacket(dp.getData());
    }

    // Wait until an ACK is received (used after EOT)
    private static void waitForAck(DatagramSocket socket) throws Exception {

        while (true) {

            try {

                DSPacket ack = receivePacket(socket);

                if (ack.getType() == DSPacket.TYPE_ACK)
                    break;

            } catch (SocketTimeoutException e) {
                continue;
            }
        }
    }

    // Print total transmission time
    private static void printTime(long start) {

        double seconds = (System.nanoTime() - start) / 1e9;

        System.out.printf("Total Transmission Time: %.2f seconds\n", seconds);
    }
}