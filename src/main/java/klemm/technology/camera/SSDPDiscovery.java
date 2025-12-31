package klemm.technology.camera;


import java.io.BufferedReader;  // For reading the OUI text file line by line
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.util.Enumeration;
import java.util.HashMap;      // For storing the OUI to manufacturer mapping
import java.util.Map;          // For the Map interface

import org.bytedeco.javacv.FFmpegFrameGrabber;

/*
 * TODO this is a sample only
 * TODO restructure into a more dynamic structure
 */

public class SSDPDiscovery {

    private static final String OUI_FILE = "/oui.txt"; // Path to downloaded OUI file
    private static final Map<String, String> ouiMap          = new HashMap<>();

    private static final Map<String,String>  knownIPMac      = new HashMap<>();
    private static final Map<String,Boolean> knownIPIsCamera = new HashMap<>();

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

            int catchAll = 1000;

            while (catchAll-- > 0) {
                if (System.in.available() > 0) {
                    System.out.println("Detected key presses, so honouring the socket timeout");
                    break; // Exit the loop when a key is pressed
                }

                System.out.println("Restarting socket");

                // Create DatagramSocket bound to a specific IPv4 address
                try (MulticastSocket socket = new MulticastSocket(new InetSocketAddress(bindAddress, 1900))) {
                    
                    socket.setReuseAddress(true);                    
                    socket.joinGroup(new InetSocketAddress("239.255.255.250", 1900), ni);                                     

                    // SSDP discovery message
                    final String discoverMessage = 
                            "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "ST: ssdp:all\r\n" + // Search for all devices with ssdp:all or device with upnp:rootdevice
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "\r\n";

                    // Send the SSDP discovery message
                    final DatagramPacket packet = new DatagramPacket(
                            discoverMessage.getBytes(),
                            discoverMessage.length(),
                            InetAddress.getByName("239.255.255.250"),
                            1900);

                    System.out.print("Sending now from: ");
                    System.out.print(socket.getLocalAddress().getHostAddress());
                    System.out.print(":");
                    System.out.print(socket.getLocalPort());

                    socket.send(packet);

                    System.out.println(" Sent -----------------------------");

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
                        System.out.print("Received SSDP response ");
                        
//                        // TODO do we need to read the packet
//                        String response = new String(responsePacket.getData(), 0, responsePacket.getLength());

                        final String remoteIp              = responsePacket.getAddress().getHostAddress();
                        final String remoteMac             = getMacAddress(responsePacket.getAddress());
                              String remoteHostName        = "<not looked up>";  
                        final String remoteMacManufacturer = lookupManufacturer(remoteMac);                                              

                        if (responsePacket.getAddress().getHostAddress().toString().equals(bindAddress.getHostAddress().toString())) {
                            System.out.print("from me          \t");                        
                        }
                        else {
                            if (knownIPMac.containsKey(remoteIp) && knownIPMac.getOrDefault(remoteIp, "").equals(remoteMac)) {

                                if (knownIPIsCamera.get(remoteIp) == Boolean.TRUE) {
                                    System.out.print("from known camera\t");
                                }
                                else {
                                    System.out.print("from known site  \t");
                                    remoteHostName = getHostName(responsePacket.getAddress());
                                }                                
                            }
                            else {
                                knownIPMac.put(remoteIp, remoteMac);
                                
                                if (knownIPIsCamera.containsKey(remoteIp)) {
                                    // TODO remove the camera view for this remoteIp
                                }



                                // is a new IP address - test for a camera
                                final Boolean isCamera = checkCameraPort(remoteIp, remoteMacManufacturer);
                                knownIPIsCamera.put(remoteIp, isCamera);

                                System.out.print("from someone   \t");

                            }
                        }

                        System.out.print(remoteIp);                  
                        System.out.print("\t");                        
                        System.out.print(remoteMac);                                  
                        System.out.print("\t");
                        System.out.print(remoteMacManufacturer);
                        System.out.print("\t");
                        System.out.print(remoteHostName);                                          
                        System.out.println();
                
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout occurred while waiting for response.");
                } catch (IOException e) {
                    System.out.println("An error occurred while receiving the response.");
                }
            }

            System.out.println("Stopping");
        } catch (Throwable e) {
            e.printStackTrace(System.err);
        }
    }

    private static String getMacAddress(InetAddress ip) {
        String ipAddress = ip.getHostAddress();
        try {
            // Use ProcessBuilder to execute the `arp` command
            ProcessBuilder pb = new ProcessBuilder("arp", "-a", ipAddress);
            pb.redirectErrorStream(true); // Merge standard error into standard output
            Process process = pb.start();

            // Read the output of the command
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.contains(ipAddress)) {
                        // Extract the MAC address using a regex
                        String[] parts = line.split("\\s+");
                        for (String part : parts) {
                            if (part.matches("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")) {
                                return part.toUpperCase(); // Return MAC address
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return "      N/A        "; // Return N/A if MAC address could not be found
    }


    // TODO Lookup the file https://standards-oui.ieee.org/oui/oui.txt and save a more recent copy
    private static String loadOuiData(final String searchOui) {
         try (BufferedReader br = new BufferedReader(new InputStreamReader(SSDPDiscovery.class.getResource(OUI_FILE).openStream()))) {


            String line;
            while ((line = br.readLine()) != null) {
                // Lines containing OUIs and manufacturers start with a hexadecimal prefix
                if (line.matches("^[0-9A-Fa-f]{6}\\s+\\(base 16\\)\\s+.+")) {
                    String[] parts = line.split("\\s+\\(base 16\\)\\s+");
                    if (parts.length == 2 && parts[0].toUpperCase().equals(searchOui)) {
                        return parts[1];
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load file:");
            System.err.println(OUI_FILE);
            e.printStackTrace(System.err);
        }
        return null;
    }

    private static String lookupManufacturer(String macAddress) {
        if (macAddress == null || macAddress.trim().length() < 8) {
            return "Unknown Manufacturer";
        }

        // Extract the OUI (first 6 characters of the MAC address, no colons)
        String oui = macAddress.trim().substring(0, 8).replaceAll("[:-]", "").toUpperCase();

        if (!ouiMap.containsKey(oui)) {
            final String manufacturer = loadOuiData(oui);
            if (manufacturer != null) {
                ouiMap.put(oui, (manufacturer + "                     ").substring(0,20));
            }
        }

        return ouiMap.getOrDefault(oui, "Unknown Manufacturer");
    }

    public static String getHostName(InetAddress ip) {
        try {
            // Attempt to get the host name
            String hostName = ip.getHostName();
            // If getHostName() returns the IP itself, consider it a failure
            if (hostName.equals(ip.getHostAddress())) {
                return "<failed finding host>";
            }
            return hostName;
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return "<error finding host>";
        }
    }

    public static Boolean checkCameraPort(final String remoteIp, final String remoteMacManufacturer) {

        if (!remoteMacManufacturer.startsWith("TP-Link Corporation")) {
            return Boolean.FALSE;
        }

        final String rtspUrl = "rtsp://camera1:camera12@" + remoteIp + ":554/stream1";
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl)) {
			grabber.setOption("rtsp_transport", "tcp");
			grabber.start();

			return Boolean.TRUE;

        }
        catch (Throwable t) {
            t.printStackTrace(System.err);
        }

        return Boolean.FALSE;
    }

}