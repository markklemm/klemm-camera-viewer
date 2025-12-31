package klemm.technology.camera;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.SplashScreen;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubnetScanner {

    public static void main(String[] args) {

        try {
            Thread virtualThread = Thread.startVirtualThread(() -> {
                new SubnetScanner().scan();
            });

            virtualThread.join(); // Wait for the virtual thread to finish
        } catch (Exception e) {

        }
    }

    private final Object CAMERA_NUMBER_SYNC_OBJECT = new Object() {
                                                   };
    volatile int         cameraNumber              = 1;

    public void scan() {

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

        try {

            long network   = bytesToLong(InetAddress.getByName("192.168.1.0").getAddress());
            long broadcast = bytesToLong(InetAddress.getByName("192.168.1.255").getAddress());

            long startIp = network + 1;   // 192.168.1.1
            long endIp   = broadcast - 1; // 192.168.1.254

            // Create a thread pool with 10 threads (adjust based on your system's cores/resources)
            ExecutorService executor = Executors.newFixedThreadPool(20);

            // Submit a task for each IP
            for (long i = startIp; i <= endIp; i++) {
                final long ipLong = i;
                executor.submit(() -> {
                    final InetAddress ip = longToInetAddress(ipLong);
                    if (ip == null) {
                        return;
                    }

                    final String ipStr = ip.getHostAddress();

                    // Check if port 554 is open
                    final boolean portOpen = isPortOpen(ip, 554, 850); // 850ms timeout
                    if (portOpen) {
                        System.out.println("Found port on IP:\t" + ipStr);
                        System.out.print("IP: " + ipStr);
                        System.out.print("\tPort 554 Open: " + portOpen);
                        System.out.println();

                        try {
                            new RtspStreamViewer(false, false, ipStr, getNextCameraNumber()).run();
                        } catch (Exception m) {
                            m.printStackTrace(System.err);
                            System.out.println("Error connecting player on IP:\t" + ipStr);
                        }
                    }
                });
            }

            System.out.println("All ports are being scanned.");
        } catch (Throwable e) {
            e.printStackTrace(System.err);
        }
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
            return true;
        } catch (IOException e) {
            return false;
        }
    }

}