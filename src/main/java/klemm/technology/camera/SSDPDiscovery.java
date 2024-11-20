package klemm.technology.camera;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.util.Enumeration;

public class SSDPDiscovery {

    public static void main(String[] args) {
        try {

            // Loop through all available network interfaces
            NetworkInterface ni          = null;
            InetAddress      bindAddress = null;

            // Get all network interfaces
            Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            while (networks.hasMoreElements()) {
                NetworkInterface networkInterface = networks.nextElement();
                if (networkInterface.isUp()) {
                    for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                        InetAddress address = interfaceAddress.getAddress();
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                            bindAddress = address;
                            ni = networkInterface;
                            break;
                        }
                    }
                    if (bindAddress != null) {
                        break;
                    }
                }
            }

            if (bindAddress == null) {
                throw new IllegalStateException("No valid non-loopback IPv4 address found.");
            }

            // Create DatagramSocket bound to a specific IPv4 address
            try (MulticastSocket socket = new MulticastSocket(new InetSocketAddress(bindAddress, 1900))) {
                
                socket.setReuseAddress(true);
                
//                MulticastSocket multicast = new MulticastSocket(1900);
                socket.joinGroup(new InetSocketAddress("239.255.255.250", 1900), ni);
                
                

                // SSDP discovery message
                String discoverMessage = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "ST: ssdp:all\r\n" + // Search for all devices with ssdp:all or device with upnp:rootdevice
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "\r\n";

                // Send the SSDP discovery message
                DatagramPacket packet = new DatagramPacket(
                        discoverMessage.getBytes(),
                        discoverMessage.length(),
                        InetAddress.getByName("239.255.255.250"),
                        1900);
                System.out.print("Sending now from: ");
                System.out.print(socket.getLocalAddress().getHostAddress());
                System.out.print(":");
                System.out.print(socket.getLocalPort());
                System.out.println();
                socket.send(packet);
                System.out.println("Sent -----------------------------");
                System.out.print(discoverMessage);
                System.out.println("----------------------------------");

                socket.setOption(StandardSocketOptions.SO_BROADCAST,     true);
                socket.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 5);
                socket.setSoTimeout(30000);

                // Buffer for receiving SSDP responses
                byte[]         buf            = new byte[2048];
                DatagramPacket responsePacket = new DatagramPacket(buf, buf.length);

                System.out.println("Listening for response");
                // Receive SSDP responses

                final long timeout  = 180000000000L;
                final long sentTime = System.nanoTime();
                while ((System.nanoTime() - sentTime) < timeout) {

                    socket.receive(responsePacket);
                    System.out.println("Received SSDP response:");
                    String response = new String(responsePacket.getData(), 0, responsePacket.getLength());
                    System.out.println("From ---------------");
                    System.out.println(responsePacket.getAddress().getHostAddress());
                    System.out.println(response);                    
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout occurred while waiting for response.");
            } catch (IOException e) {
                System.out.println("An error occurred while receiving the response.");
            }

            System.out.println("Stopping");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
