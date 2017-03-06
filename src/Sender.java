import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by William on 2017-02-25.
 */
public class Sender {

    public static final int SEQ_NUM_MODULO = Packet.SEQ_NUM_MODULO;
    private PrintWriter seqNumWriter = new PrintWriter("seqnum.log");
    private PrintWriter ackWriter = new PrintWriter("ack.log");

    private static final int WINDOW_SIZE = 10;
    private static final int TIME_OUT = 1000; // magic number
    private int numberOfPackets;
    private volatile int base = 0;
    private volatile int nextSeqNum = 0;
    private volatile int cycles = 0;
    private Timer timer = new Timer(); // cannot be daemon because it creates new timer

    private InetAddress address;
    private int portForData;

    private DatagramSocket ackDatagramSocket;
    private DatagramSocket dataDatagramSocket;
    private Packet[] packets;

    public Sender(InetAddress address, int portForData, int portForAck, String fileName) throws Exception {
        this.dataDatagramSocket = new DatagramSocket(0);
        this.address = address;
        this.portForData = portForData;
        this.ackDatagramSocket = new DatagramSocket(portForAck);
        this.packets = createPackets(readFile(fileName));

        // listen for ACKs
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    waitForAck();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

        this.startTimer();
        // send the data
        synchronized (this) {
            while (nextSeqNum + cycles * SEQ_NUM_MODULO < this.numberOfPackets) {
                //
                while (nextSeqNum >= base + WINDOW_SIZE || nextSeqNum >= SEQ_NUM_MODULO) {
                    wait();
                }
                sendPacket(nextSeqNum);
                if (base == nextSeqNum) {
                    stopTimer();
                    startTimer();
                }
                nextSeqNum ++;
            }
        }
    }

    private void stopTimer() {
        this.timer.cancel();
        this.timer.purge();
        this.timer = new Timer();
    }

    private void startTimer() {
        this.timer.schedule(new TimerTask() {
            @Override
            public void run() {
                System.out.println("time out");
                for (int i = base; i < nextSeqNum; i ++) {
                    try {
                        Sender.this.sendPacket(i);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                Sender.this.timer = new Timer();
                Sender.this.startTimer();
            }
        }, TIME_OUT);
    }

    private void sendPacket(int index) throws IOException {
        byte[] udpData = packets[index + cycles * 32].getUDPdata();
        DatagramPacket datagramPacket = new DatagramPacket(udpData, udpData.length, address, portForData);
        this.dataDatagramSocket.send(datagramPacket);
        seqNumWriter.println(index + cycles * 32);
        System.out.println("Sending " + index);
    }

    private void waitForAck() throws Exception {
        while (true) {
            byte[] data = new byte[512];
            DatagramPacket datagramPacket = new DatagramPacket(data, 512);
            this.ackDatagramSocket.receive(datagramPacket);
            Packet ackPacket = Packet.parseUDPdata(datagramPacket.getData());
            synchronized (this) {
                if (ackPacket.getSeqNum() >= base && base + WINDOW_SIZE > ackPacket.getSeqNum()) {// discard duplicates
                    base = ackPacket.getSeqNum() + 1;
                    if (ackPacket.getSeqNum() == SEQ_NUM_MODULO - 1) {
                        this.cycles ++;
                        base = 0;
                        nextSeqNum = 0;
                    }
                    System.out.println("Confirmed: " + ackPacket.getSeqNum());
                    ackWriter.println(ackPacket.getSeqNum());
                    if (base + cycles * SEQ_NUM_MODULO == this.numberOfPackets) {
                        break;
                    } else if (base == nextSeqNum) {
                        stopTimer();
                    } else {
                        stopTimer();
                        startTimer();
                    }
                    // to let thread sending data know that new "blue" seq numbers are available
                    notify();
                }
            }
        }
        System.out.println("Sending EOT");

        byte[] eotData = Packet.createEOT(numberOfPackets % SEQ_NUM_MODULO).getUDPdata();
        this.dataDatagramSocket.send(new DatagramPacket(eotData, eotData.length, this.address, this.portForData));
        seqNumWriter.println(numberOfPackets);
        seqNumWriter.close();
        ackWriter.close();
        dataDatagramSocket.close();
        byte[] data = new byte[512];
        DatagramPacket datagramPacket = new DatagramPacket(data, 512);
        this.ackDatagramSocket.receive(datagramPacket);
        while (Packet.parseUDPdata(datagramPacket.getData()).getType() != 2) {
            data = new byte[512];
            datagramPacket = new DatagramPacket(data, 512);
            this.ackDatagramSocket.receive(datagramPacket);
        }
        ackDatagramSocket.close();
        System.exit(0);
    }

    private Packet[] createPackets(byte[] fileContent) throws Exception {
        int numberOfWholePackets = fileContent.length / Packet.MAX_DATA_LENGTH;
        // length of last Packet might be smaller
        int smallPacketLength =  fileContent.length % Packet.MAX_DATA_LENGTH;
        this.numberOfPackets = numberOfWholePackets + (smallPacketLength > 0 ? 1 : 0);
        Packet[] packets = new Packet[numberOfPackets];
        int offset = 0;
        int i = 0;
        for (; i < numberOfWholePackets; i ++) {
            String data = new String(Arrays.copyOfRange(fileContent, offset, offset + Packet.MAX_DATA_LENGTH), StandardCharsets.UTF_8);
            packets[i] = Packet.createPacket(i, data);
            offset += Packet.MAX_DATA_LENGTH;
        }
        // handle the last small Packet
        if (smallPacketLength > 0) {
            packets[i] = Packet.createPacket(i, new String(Arrays.copyOfRange(fileContent, offset, fileContent.length)));
        }
        return packets;
    }

    private static byte[] readFile(String fileName) throws IOException {
        File file = new File(fileName);
        FileInputStream fileInputStream = new FileInputStream(file);
        int byteLength = (int) file.length();
        byte[] fileContent = new byte[byteLength];
        fileInputStream.read(fileContent,0, byteLength);
        return fileContent;
    }

    /**
     *
     * @param args <host address of the network emulator>,
     *             <UDP port number used by the emulator to receive data from the sender>,
     *             <UDP port number used by the sender to receive ACKs from the emulator>,
     *             and <name of the file to be transferred> in the given order.
     *
     */
    public static void main(String[] args) throws Exception {
        try {
            if (args.length != 4) {
                throw new IllegalArgumentException("Incorrect number of arguments");
            }
            InetAddress address = InetAddress.getByName(args[0]);
            int portForData = Integer.parseInt(args[1]);
            int portForAck = Integer.parseInt(args[2]);
            String fileName = args[3];
            new Sender(address, portForData, portForAck, fileName);
        } catch (IllegalArgumentException e) {
            // arguments are not following specification
            e.printStackTrace();
            System.exit(1);
        }

    }
}
