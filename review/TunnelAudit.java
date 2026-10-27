import com.svcb.tsbridge.net.TcpTunnelServer;
import java.net.*;
import java.io.*;

public class TunnelAudit {
    public static void main(String[] args) throws Exception {
        try (DatagramSocket target = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            target.setSoTimeout(1000);
            TcpTunnelServer.start(0, "127.0.0.1", target.getLocalPort());
            var field = TcpTunnelServer.class.getDeclaredField("serverSocket");
            field.setAccessible(true);
            int port = ((ServerSocket)field.get(null)).getLocalPort();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                DataInputStream in = new DataInputStream(client.getInputStream());
                client.setSoTimeout(1000);
                out.writeInt(1);out.writeByte(11);out.flush();
                DatagramPacket packet = new DatagramPacket(new byte[100], 100);
                target.receive(packet);
                TcpTunnelServer.stop();
                out.writeInt(1);out.writeByte(22);out.flush();
                target.receive(packet);
                target.send(new DatagramPacket(new byte[]{33}, 1, packet.getAddress(), packet.getPort()));
                System.out.println("tunnel marked stopped -> " + !TcpTunnelServer.isRunning());
                System.out.println("after stop, existing tunnel still forwards both ways -> " + (packet.getData()[0] == 22 && in.readInt() == 1 && in.readByte() == 33));
            } finally { TcpTunnelServer.stop(); }
        }
    }
}
