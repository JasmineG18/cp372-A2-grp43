import java.io.*;
import java.net.*;
import java.util.*;

/*
 * Sender program for the DS-FTP protocol.
 *
 * Responsibilities:
 * - Initiate connection using SOT handshake
 * - Read file and send DATA packets
 * - Implement Stop-and-Wait or Go-Back-N
 * - Handle retransmissions on timeout
 * - Send EOT when transmission completes
 * - Measure total transmission time
 */

public class Sender {

    // Sequence numbers wrap modulo 128
    private static final int MOD = 128;

    public static void main(String[] args) throws Exception {

        if (args.length < 5) {
            System.out.println("Invalid arguments");
            return;
        }

        // Parse command line arguments
        InetAddress rcvIP = InetAddress.getByName(args[0]);
        int rcvDataPort = Integer.parseInt(args[1]);
        int senderAckPort = Integer.parseInt(args[2]);
        String inputFile = args[3];
        int timeout = Integer.parseInt(args[4]);

        // If window size provided → use Go-Back-N
        Integer windowSize = null;
        if (args.length == 6) {
            windowSize = Integer.parseInt(args[5]);
        }

        // Create socket for sending and receiving ACKs
        DatagramSocket socket = new DatagramSocket(senderAckPort);

        // Configure timeout for ACK reception
        socket.setSoTimeout(timeout);

        // Start measuring transmission time
        long startTime = System.nanoTime();

        // ================= HANDSHAKE =================
        // Send Start-of-Transmission packet
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);

        System.out.println("Sender: Sending SOT");

        while (true) {

            sendPacket(socket, sot, rcvIP, rcvDataPort);

            try {

                DSPacket ack = receivePacket(socket);

                // Connection established when ACK for SOT received
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {

                    System.out.println("Sender: ACK received for SOT");
                    break;
                }

            } catch (SocketTimeoutException e) {

                // Retransmit SOT if ACK lost
                System.out.println("Sender: Timeout waiting for SOT ACK. Resending...");
            }
        }

        // Select protocol
        if (windowSize == null) {

            stopAndWait(socket, rcvIP, rcvDataPort, inputFile);

        } else {

            goBackN(socket, rcvIP, rcvDataPort, inputFile, windowSize);
        }

        socket.close();

        printTime(startTime);
    }

    /*
     * STOP-AND-WAIT PROTOCOL
     *
     * Only one packet may be outstanding at a time.
     * Sender waits for ACK before sending next packet.
     */
    private static void stopAndWait(DatagramSocket socket,
                                    InetAddress ip,
                                    int port,
                                    String fileName) throws Exception {

        FileInputStream fis = new FileInputStream(fileName);

        byte[] buffer = new byte[DSPacket.MAX_PAYLOAD_SIZE];

        int seq = 1;
        int timeoutCount = 0;
        int lastDataSeq = 0;

        int read;

        while ((read = fis.read(buffer)) != -1) {

            byte[] payload = Arrays.copyOf(buffer, read);

            DSPacket packet = new DSPacket(DSPacket.TYPE_DATA, seq, payload);

            while (true) {

                sendPacket(socket, packet, ip, port);

                try {

                    DSPacket ack = receivePacket(socket);

                    System.out.println("Sender: ACK received = " + ack.getSeqNum());

                    if (ack.getType() == DSPacket.TYPE_ACK) {

                        int ackSeq = ack.getSeqNum();

                        // Accept correct ACK or cumulative ACK
                        if (ackSeq == seq ||
                                ((ackSeq - seq + MOD) % MOD) < 5) {

                            lastDataSeq = seq;

                            seq = (seq + 1) % MOD;

                            timeoutCount = 0;

                            break;
                        }
                    }

                } catch (SocketTimeoutException e) {

                    System.out.println("Sender: Timeout! Retransmitting packet SEQ = " + seq);

                    timeoutCount++;

                    if (timeoutCount == 3) {
                        fail();
                    }
                }
            }
        }

        fis.close();

        // Send End-of-Transmission packet
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT,
                (lastDataSeq + 1) % MOD, null);

        System.out.println("Sender: Sending EOT");

        sendPacket(socket, eot, ip, port);

        waitForAck(socket);
    }

    /*
     * GO-BACK-N PROTOCOL
     *
     * Multiple packets may be sent without waiting for ACKs.
     * Sender maintains a sliding window.
     * On timeout → retransmit entire window.
     */
    private static void goBackN(DatagramSocket socket,
                                InetAddress ip,
                                int port,
                                String fileName,
                                int windowSize) throws Exception {

        FileInputStream fis = new FileInputStream(fileName);

        byte[] buffer = new byte[DSPacket.MAX_PAYLOAD_SIZE];

        int base = 1;
        int nextSeq = 1;

        int timeoutCount = 0;

        int lastDataSeq = 0;

        Map<Integer, DSPacket> window = new HashMap<>();

        boolean fileEnded = false;

        while (!fileEnded || base != nextSeq) {

            // Fill window with new packets
            while (!fileEnded &&
                    ((nextSeq - base + MOD) % MOD) < windowSize) {

                int read = fis.read(buffer);

                if (read == -1) {

                    fileEnded = true;
                    break;
                }

                byte[] payload = Arrays.copyOf(buffer, read);

                DSPacket packet =
                        new DSPacket(DSPacket.TYPE_DATA, nextSeq, payload);

                window.put(nextSeq, packet);

                // SEND packet immediately when created
                sendPacket(socket, packet, ip, port);

                lastDataSeq = nextSeq;

                nextSeq = (nextSeq + 1) % MOD;
            }

            try {

                DSPacket ack = receivePacket(socket);

                System.out.println("Sender: ACK received = " + ack.getSeqNum());

                if (ack.getType() == DSPacket.TYPE_ACK) {

                    int ackSeq = ack.getSeqNum();

                    // Slide window forward
                    if (((ackSeq - base + MOD) % MOD) < windowSize) {

                        base = (ackSeq + 1) % MOD;

                        timeoutCount = 0;
                    }
                }

            } catch (SocketTimeoutException e) {

                System.out.println("Sender: Timeout! Retransmitting window from SEQ = " + base);

                int seq = base;

                while (seq != nextSeq) {

                    DSPacket p = window.get(seq);

                    sendPacket(socket, p, ip, port);

                    seq = (seq + 1) % MOD;
                }

                timeoutCount++;

                if (timeoutCount == 3) {
                    fail();
                }
            }
        }

        fis.close();

        DSPacket eot =
                new DSPacket(DSPacket.TYPE_EOT,
                        (lastDataSeq + 1) % MOD, null);

        System.out.println("Sender: Sending EOT");

        sendPacket(socket, eot, ip, port);

        waitForAck(socket);
    }

    // ================= UTILITIES =================

    private static void sendPacket(DatagramSocket socket,
                                   DSPacket packet,
                                   InetAddress ip,
                                   int port) throws Exception {

        byte[] data = packet.toBytes();

        DatagramPacket dp =
                new DatagramPacket(data, data.length, ip, port);

        socket.send(dp);

        if (packet.getType() == DSPacket.TYPE_DATA) {

            System.out.println("Sender: Sent DATA packet SEQ = " + packet.getSeqNum());
        }
    }

    private static DSPacket receivePacket(DatagramSocket socket)
            throws Exception {

        byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE];

        DatagramPacket dp =
                new DatagramPacket(buffer, buffer.length);

        socket.receive(dp);

        return new DSPacket(dp.getData());
    }

    private static void waitForAck(DatagramSocket socket)
            throws Exception {

        while (true) {

            try {

                DSPacket ack = receivePacket(socket);

                System.out.println("Sender: ACK received = " + ack.getSeqNum());

                if (ack.getType() == DSPacket.TYPE_ACK) {
                    break;
                }

            } catch (SocketTimeoutException e) {
                continue;
            }
        }
    }

    private static void fail() {

        System.out.println("Unable to transfer file.");

        System.exit(0);
    }

    private static void printTime(long start) {

        double seconds = (System.nanoTime() - start) / 1e9;

        System.out.printf("Total Transmission Time: %.2f seconds\n", seconds);
    }
}