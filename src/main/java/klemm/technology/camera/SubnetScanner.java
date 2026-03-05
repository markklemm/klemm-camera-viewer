package klemm.technology.camera;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.SplashScreen;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import klemm.technology.ui.LookAndFeel;

public class SubnetScanner {

    public static void main(String[] args) {

        LookAndFeel.applyWeb();

        final String cloudPassword = ((args.length > 1) ? args[1].trim() : "default");

        try {
            Thread virtualThread = Thread.startVirtualThread(() -> {
                new SubnetScanner().scan(cloudPassword);
            });

            virtualThread.join(); // Wait for the virtual thread to finish
        } catch (Exception e) {

        }
    }

    private final Object CAMERA_NUMBER_SYNC_OBJECT = new Object() {
                                                   };
    volatile int         cameraNumber              = 1;

    public void scan(final String cloudPassword) {

        final SplashScreen splash = SplashScreen.getSplashScreen();

        try {
            if (splash != null) {
                Graphics2D g = splash.createGraphics();
                if (g != null) {
                    // Display loading messages on the splash screen
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("Arial", Font.BOLD, 14));
                    g.drawString("Looking for cameras, please wait...", 20, 32);
                    splash.update();
                }
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }

        // TODO check if there is a saved list - try them.
        
        
        try {

            long network   = bytesToLong(InetAddress.getByName("192.168.1.0").getAddress());
            long broadcast = bytesToLong(InetAddress.getByName("192.168.1.255").getAddress());

            long startIp = network + 1;   // 192.168.1.1
            long endIp   = broadcast - 1; // 192.168.1.254
            
            try {
                network = -1L;
                broadcast = -1L;
                String localIp = null;
                
                // Iterate all network interfaces (Ethernet, WiFi, etc.)
                for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (nif.isLoopback() || !nif.isUp() || nif.isVirtual() || nif.isPointToPoint()) {
                        continue;
                    }

                    for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        if (ia.getAddress() instanceof Inet4Address) {
                            InetAddress bcastAddr = ia.getBroadcast();
                            if (bcastAddr != null) {                    // valid IPv4 LAN
                                byte[] ipBytes = ia.getAddress().getAddress();
                                long ipLong = bytesToLong(ipBytes);
                                long bcastLong = bytesToLong(bcastAddr.getAddress());

                                short prefixLen = ia.getNetworkPrefixLength();

                                // Calculate network address (works for ANY prefix length)
                                long mask = (0xFFFFFFFFL << (32 - prefixLen)) & 0xFFFFFFFFL;
                                network = ipLong & mask;
                                broadcast = bcastLong;                  // Java already computed the correct one

                                localIp = ia.getAddress().getHostAddress();
                                break;   // use the first good interface (most common case)
                            }
                        }
                    }
                    if (network != -1L) break;   // stop after finding one
                }

                if (network == -1L) {
                    throw new SocketException("No suitable IPv4 LAN interface found");
                }

                startIp = network + 1;   // e.g. 192.168.1.1
                endIp   = broadcast - 1; // e.g. 192.168.1.254

                System.out.println("Scanning local subnet from " + localIp +
                                   " → network=" + Long.toUnsignedString(network, 10) +
                                   ", broadcast=" + Long.toUnsignedString(broadcast, 10));

                // ←←← now use startIp / endIp exactly as before in your scanning loop

            } catch (Exception e) {
                // fallback or error handling
                System.err.println("Could not detect local subnet: " + e.getMessage());
                // optionally fall back to your old hardcoded values
            }            
            

            // Create a thread pool with 10 threads (adjust based on your system's cores/resources)
            ExecutorService executor = Executors.newFixedThreadPool(20);

            // Submit a task for each IP
            for (long i = startIp; i <= endIp; i++) {
                final long ipLong = i;
                executor.submit((Runnable) () -> {
                    final InetAddress ip = longToInetAddress(ipLong);
                    if (ip == null) {
                        return;
                    }

                    final String ipStr = ip.getHostAddress();

                    // Check if port 554 is open
                    boolean portOpen = isPortOpen(ip, 554, 980); // 980ms timeout                    
                    if (portOpen) {
                        System.out.println("Found port on IP:\t" + ipStr);
                        System.out.print("IP: ");
                        System.out.print(ipStr);
                        System.out.print(":554 is open");
                        System.out.println();

                        try {
                            new RtspStreamViewer(false, false, ipStr, getNextCameraNumber(), true).run();
                        } catch (Exception m) {
                            m.printStackTrace(System.err);
                            System.out.println("Error connecting player on IP:\t" + ipStr);
                        }
                    }
                    else {
                        // ping and wait, then ping again, then third time lucky - it might work with a pause but this works.
                        portOpen = isPortOpen(ip, 8800, 980); // 950ms timeout
                        if (!portOpen) {
                            portOpen = isPortOpen(ip, 8800, 1000); // 950ms timeout
                        }
                        if (!portOpen) {
                            portOpen = isPortOpen(ip, 8800, 980); // 950ms timeout
                        }
                        if (portOpen) {
                            System.out.print("IP: ");
                            System.out.print(ipStr);
                            System.out.print(":8800 is open");
                            System.out.println();
    
                            try {
                                TapoStreamViewer.start(cloudPassword, ipStr);
                            } catch (Exception m) {
                                m.printStackTrace(System.err);
                                System.out.println("Error connecting player on IP:\t" + ipStr);
                            }
                        }
                        
                    }
                });
            }

            System.out.println("All ports are being scanned.");
        } catch (Throwable e) {
            e.printStackTrace(System.err);
        }
        
        // TODO save a list of ip addresses and ports.
    }

    private int getNextCameraNumber() {

        final int cameraNumberReturn;
        synchronized (CAMERA_NUMBER_SYNC_OBJECT) {
            cameraNumberReturn = this.cameraNumber++;
        }
        return cameraNumberReturn;
    }

    private static long bytesToLong(byte[] bytes) {
        return ((bytes[0] & 0xFFL) << 24) |
                ((bytes[1] & 0xFFL) << 16) |
                ((bytes[2] & 0xFFL) << 8) |
                (bytes[3] & 0xFFL);
    }

    private static InetAddress longToInetAddress(long addr) {
        byte[] bytes = new byte[] {
                (byte) ((addr >> 24) & 0xFF),
                (byte) ((addr >> 16) & 0xFF),
                (byte) ((addr >> 8) & 0xFF),
                (byte) (addr & 0xFF)
        };
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            e.printStackTrace(System.err);
            return null;
        }
    }

    private static boolean isPortOpen(InetAddress ip, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), timeout);
            socket.setSoTimeout(0); // streaming
            socket.setTcpNoDelay(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

}