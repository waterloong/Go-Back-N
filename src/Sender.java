import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by William on 2017-02-25.
 */
public class Sender {

    private PrintWriter seqNumWriter = new PrintWriter("seqnum.log");
    private PrintWriter ackWriter = new PrintWriter("ack.log");

    public static final int WINDOW_SIZE = 10;
    public static final int TIME_OUT = 1000; // magic number
    private int numberOfPackets;
    private volatile int base = 0;
    private volatile int nextSeqNum = 0;
    private Timer timer = new Timer(true); // we want to stop timer once other threads exit

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
            while (nextSeqNum < this.numberOfPackets) {
                while (nextSeqNum >= base + WINDOW_SIZE) {
                    wait();
                }
                sendPacket(nextSeqNum);
                if (base == nextSeqNum) {
                    stopTimer();
                    startTimer();
                    nextSeqNum ++;
                }
            }
        }
    }

    private void stopTimer() {
        this.timer.cancel();
        this.timer.purge();
        this.timer = new Timer(true);
    }

    private void startTimer() {
        this.timer.schedule(new TimerTask() {
            @Override
            public void run() {
                Sender.this.startTimer();
                System.out.println("time out");
                for (int i = base; i < nextSeqNum; i ++) {
                    try {
                        Sender.this.sendPacket(i);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }, TIME_OUT);
    }

    private void sendPacket(int index) throws IOException {
        byte[] udpData = packets[index].getUDPdata();
        DatagramPacket datagramPacket = new DatagramPacket(udpData, udpData.length, address, portForData);
        this.dataDatagramSocket.send(datagramPacket);
        seqNumWriter.println(index);
        System.out.println("Sending " + index);
    }

    public void waitForAck() throws Exception {
        while (true) {
            byte[] data = new byte[512];
            DatagramPacket datagramPacket = new DatagramPacket(data, 512);
            this.ackDatagramSocket.receive(datagramPacket);
            Packet ackPacket = Packet.parseUDPdata(datagramPacket.getData());
            synchronized (this) {
                base = ackPacket.getSeqNum();
                System.out.println("Confirmed: " + base);
                ackWriter.println(base);
                if (base == this.numberOfPackets - 1) {
                    break;
                } else if (base == nextSeqNum) {
                    stopTimer();
                    startTimer();
                } else {
                    stopTimer();
                }
                // to let thread sending data know that new "blue" seq number are available
                notify();
            }
        }
        byte[] eotData = Packet.createEOT(numberOfPackets).getUDPdata();
        this.dataDatagramSocket.send(new DatagramPacket(eotData, eotData.length, this.address, this.portForData));
        seqNumWriter.println(numberOfPackets);
        seqNumWriter.close();
        ackWriter.close();
        System.out.println("Sending EOT");
        dataDatagramSocket.close();
        ackDatagramSocket.close();
        System.exit(0);
    }

    public Packet[] createPackets(byte[] fileContent) throws Exception {
        int numberOfWholePackets = fileContent.length / Packet.maxDataLength;
        // length of last packet might be smaller
        int smallPacketLength =  fileContent.length % Packet.maxDataLength;
        this.numberOfPackets = numberOfWholePackets + (smallPacketLength > 0 ? 1 : 0);
        Packet[] packets = new Packet[numberOfPackets];
        int offset = 0;
        int i = 0;
        for (; i < numberOfWholePackets; i ++) {
            String data = new String(Arrays.copyOfRange(fileContent, offset, offset + Packet.maxDataLength));
            packets[i] = Packet.createPacket(i, data);
            offset += Packet.maxDataLength;
        }
        // handle the last small packet
        if (smallPacketLength > 0) {
            packets[i] = Packet.createPacket(i, new String(Arrays.copyOfRange(fileContent, offset, fileContent.length)));
        }
        return packets;
    }

    public static byte[] readFile(String fileName) throws IOException {
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
