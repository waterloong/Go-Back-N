import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Created by William on 2017-02-26.
 */
public class Receiver {

    public static final int SEQ_NUM_MODULO = Packet.SEQ_NUM_MODULO;
    private PrintWriter logWriter = new PrintWriter("arrival.log");
    private FileOutputStream fileWriter;
    private final InetAddress address;
    private final int portForAck;
    private int expectedSeqNum = 0;
    private final DatagramSocket ackDatagramSocket;
    private DatagramSocket dataDatagramSocket;

    public Receiver(InetAddress address, int portForAck, int portForData, String fileName) throws Exception {
        this.fileWriter = new FileOutputStream(fileName);
        this.dataDatagramSocket = new DatagramSocket(portForData);
        this.address = address;
        this.portForAck = portForAck;
        this.ackDatagramSocket = new DatagramSocket(0);

        while (true) {
            byte[] rawData = new byte[512];
            DatagramPacket datagramPacket = new DatagramPacket(rawData, 512);
            dataDatagramSocket.receive(datagramPacket);
            Packet packet = Packet.parseUDPdata(datagramPacket.getData());
            int seqNum = packet.getSeqNum();
            logWriter.println(seqNum);
            System.out.println("received: " + seqNum);

            // EOT Packet, we are done
            if (packet.getType() == 2) {
                logWriter.close();
                fileWriter.close();
                System.out.println("EOT received");
                sendEot((expectedSeqNum + 1) % SEQ_NUM_MODULO);
                break;
            }
            if (expectedSeqNum == seqNum) {
                expectedSeqNum = (expectedSeqNum + 1) % Packet.SEQ_NUM_MODULO;
                sendAck(seqNum);
                fileWriter.write(packet.getData());
            } else if (expectedSeqNum > 0) { // send last correct ack iff it exists
                //  // expectedSeqNum - 1 but also work for case of 0
                sendAck((expectedSeqNum + 31) % Packet.SEQ_NUM_MODULO);
            }
        }

    }

    private void sendEot(int seqNum) throws Exception {
        Packet packet = Packet.createEOT(seqNum);
        byte[] data = packet.getUDPdata();
        DatagramPacket datagramPacket = new DatagramPacket(data, data.length, address, portForAck);
        ackDatagramSocket.send(datagramPacket);
        System.out.println("sent eot: " + seqNum);
    }

    private void sendAck(int seqNum) throws Exception {
        Packet packet = Packet.createACK(seqNum);
        byte[] data = packet.getUDPdata();
        DatagramPacket datagramPacket = new DatagramPacket(data, data.length, address, portForAck);
        ackDatagramSocket.send(datagramPacket);
        System.out.println("sent ack: " + seqNum);
    }

    /**
     * @param args <hostname for the network emulator>,
     *             <UDP port number used by the link emulator to receive ACKs from the receiver>,
     *             <UDP port number used by the receiver to receive data from the emulator>,
     *             and <name of the file into which the received data is written>
     */
    public static void main(String[] args) throws Exception {
        try {
            if (args.length != 4) {
                throw new IllegalArgumentException("Incorrect number of arguments");
            }
            InetAddress address = InetAddress.getByName(args[0]);
            int portForAck = Integer.parseInt(args[1]);
            int portForData = Integer.parseInt(args[2]);
            String fileName = args[3];
            new Receiver(address, portForAck, portForData, fileName);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
