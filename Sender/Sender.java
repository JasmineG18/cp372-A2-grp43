import java.io.*;
import java.net.*;
import java.util.*;

public class Sender {

    // Sequence numbers wrap around using modulo 128
    private static final int MOD = 128;

    public static void main(String[] args) throws Exception {

        // Check if required arguments are provided
        if (args.length < 5) {
            System.out.println("Invalid arguments");
            return;
        }

        // ================= ARGUMENTS =================
        // args[0] = Receiver IP
        // args[1] = Receiver data port (where packets are sent)
        // args[2] = Sender ACK port (where ACKs are received)
        // args[3] = Input file to send
        // args[4] = Timeout value (milliseconds)
        // args[5] = Window size (optional, used for Go-Back-N)

        InetAddress rcvIP = InetAddress.getByName(args[0]);
        int rcvDataPort = Integer.parseInt(args[1]);
        int senderAckPort = Integer.parseInt(args[2]);
        String inputFile = args[3];
        int timeout = Integer.parseInt(args[4]);

        // If a window size is provided → use Go-Back-N
        Integer windowSize = null;
        if (args.length == 6) {
            windowSize = Integer.parseInt(args[5]);
        }

        // Create socket for sending packets and receiving ACKs
        DatagramSocket socket = new DatagramSocket(senderAckPort);

        // Set timeout for receiving ACKs
        socket.setSoTimeout(timeout);

        // Start transmission timer
        long startTime = System.nanoTime();


        // ================= HANDSHAKE =================
        // Send Start Of Transmission (SOT) packet
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);

        while (true) {

            // Send SOT packet
            sendPacket(socket, sot, rcvIP, rcvDataPort);

            try {

                // Wait for ACK
                DSPacket ack = receivePacket(socket);

                // If ACK for sequence 0 is received → handshake complete
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    break;
                }

            } catch (SocketTimeoutException e) {

                // If timeout occurs → resend SOT
                continue;
            }
        }

        // File that will be transmitted
        File file = new File(inputFile);


        // ================= EMPTY FILE CASE =================
        // If file has no content, immediately send EOT
        if (file.length() == 0) {

            DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, 1, null);

            sendPacket(socket, eot, rcvIP, rcvDataPort);

            // Wait for final ACK
            waitForAck(socket);

            printTime(startTime);
            socket.close();
            return;
        }


        // ================= SELECT PROTOCOL =================
        // If window size is not provided → Stop-and-Wait
        if (windowSize == null) {
            stopAndWait(socket, rcvIP, rcvDataPort, inputFile);

        // Otherwise → Go-Back-N sliding window protocol
        } else {
            goBackN(socket, rcvIP, rcvDataPort, inputFile, windowSize);
        }

        socket.close();
        printTime(startTime);
    }


    // ================= STOP AND WAIT =================
    // Sends one packet at a time and waits for its ACK

    private static void stopAndWait(DatagramSocket socket,
                                    InetAddress ip,
                                    int port,
                                    String fileName) throws Exception {

        FileInputStream fis = new FileInputStream(fileName);

        // Buffer used to read file data
        byte[] buffer = new byte[DSPacket.MAX_PAYLOAD_SIZE];

        int seq = 1;
        int timeoutCount = 0;
        int lastDataSeq = 0;

        int read;

        // Read file chunk by chunk
        while ((read = fis.read(buffer)) != -1) {

            byte[] payload = Arrays.copyOf(buffer, read);

            DSPacket packet = new DSPacket(DSPacket.TYPE_DATA, seq, payload);

            // Keep sending packet until correct ACK is received
            while (true) {

                sendPacket(socket, packet, ip, port);

                try {

                    DSPacket ack = receivePacket(socket);

                    // If ACK matches packet sequence → move to next packet
                    if (ack.getType() == DSPacket.TYPE_ACK &&
                        ack.getSeqNum() == seq) {

                        lastDataSeq = seq;

                        seq = (seq + 1) % MOD;

                        timeoutCount = 0;

                        break;
                    }

                } catch (SocketTimeoutException e) {

                    // If ACK not received in time → retransmit
                    timeoutCount++;

                    if (timeoutCount == 3) {
                        fail();
                    }
                }
            }
        }

        fis.close();

        // Send End Of Transmission packet
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT,
                (lastDataSeq + 1) % MOD, null);

        sendPacket(socket, eot, ip, port);

        waitForAck(socket);
    }


    // ================= GO BACK N =================
    // Sliding window protocol allowing multiple packets in flight

    private static void goBackN(DatagramSocket socket,
                                InetAddress ip,
                                int port,
                                String fileName,
                                int windowSize) throws Exception {

        FileInputStream fis = new FileInputStream(fileName);

        byte[] buffer = new byte[DSPacket.MAX_PAYLOAD_SIZE];

        int base = 1;        // Oldest unacknowledged packet
        int nextSeq = 1;     // Next sequence number to send
        int timeoutCount = 0;
        int lastDataSeq = 0;

        // Stores packets currently in the window
        Map<Integer, DSPacket> window = new HashMap<>();

        boolean fileEnded = false;

        // Continue until file is sent and all packets are ACKed
        while (!fileEnded || base != nextSeq) {

            // ================= FILL WINDOW =================
            // Add packets to the window while space exists
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

                lastDataSeq = nextSeq;

                nextSeq = (nextSeq + 1) % MOD;
            }


            // ================= SEND PACKETS =================
            // Prepare packets currently in the window
            List<DSPacket> toSend = new ArrayList<>();

            int seq = base;

            while (seq != nextSeq) {
                toSend.add(window.get(seq));
                seq = (seq + 1) % MOD;
            }

            // Send packets in groups of 4 using ChaosEngine permutation
            for (int i = 0; i < toSend.size(); i += 4) {

                List<DSPacket> group =
                        toSend.subList(i, Math.min(i + 4, toSend.size()));

                List<DSPacket> permuted =
                        ChaosEngine.permutePackets(new ArrayList<>(group));

                for (DSPacket p : permuted) {
                    sendPacket(socket, p, ip, port);
                }
            }


            // ================= RECEIVE ACK =================
            try {

                DSPacket ack = receivePacket(socket);

                if (ack.getType() == DSPacket.TYPE_ACK) {

                    int ackSeq = ack.getSeqNum();

                    // Slide window forward if ACK is within range
                    if (((ackSeq - base + MOD) % MOD) < windowSize) {

                        base = (ackSeq + 1) % MOD;

                        timeoutCount = 0;
                    }
                }

            } catch (SocketTimeoutException e) {

                // Timeout → retransmission required
                timeoutCount++;

                if (timeoutCount == 3) {
                    fail();
                }
            }
        }

        fis.close();


        // Send End Of Transmission packet
        DSPacket eot =
                new DSPacket(DSPacket.TYPE_EOT,
                        (lastDataSeq + 1) % MOD, null);

        sendPacket(socket, eot, ip, port);

        waitForAck(socket);
    }


    // ================= UTILITIES =================

    // Sends a packet using UDP
    private static void sendPacket(DatagramSocket socket,
                                   DSPacket packet,
                                   InetAddress ip,
                                   int port) throws Exception {

        byte[] data = packet.toBytes();

        DatagramPacket dp =
                new DatagramPacket(data, data.length, ip, port);

        socket.send(dp);
    }


    // Receives a packet and converts it into a DSPacket object
    private static DSPacket receivePacket(DatagramSocket socket)
            throws Exception {

        byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE];

        DatagramPacket dp =
                new DatagramPacket(buffer, buffer.length);

        socket.receive(dp);

        return new DSPacket(dp.getData());
    }


    // Waits until an ACK packet is received
    private static void waitForAck(DatagramSocket socket)
            throws Exception {

        while (true) {
            try {

                DSPacket ack = receivePacket(socket);

                if (ack.getType() == DSPacket.TYPE_ACK) {
                    break;
                }

            } catch (SocketTimeoutException e) {

                // Keep waiting if timeout occurs
                continue;
            }
        }
    }


    // Called when transmission repeatedly fails
    private static void fail() {
        System.out.println("Unable to transfer file.");
        System.exit(0);
    }


    // Prints total transmission time
    private static void printTime(long start) {

        double seconds = (System.nanoTime() - start) / 1e9;

        System.out.printf("Total Transmission Time: %.2f seconds\n",
                seconds);
    }
}