package klemm.technology.camera;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.SplashScreen;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

public class JustSplash {

    public static void main(String [] args) {

		final SplashScreen splash = SplashScreen.getSplashScreen();

		try {
			if (splash != null) {
				Graphics2D g = splash.createGraphics();
				if (g != null) {
					// Display loading messages on the splash screen
					g.setColor(Color.BLACK);
					g.setFont(new Font("Arial", Font.BOLD, 14));
					g.drawString("Preparing application, please wait...", 20, 50);
					splash.update();
				}
			}

		}
		catch (Throwable t) {
			t.printStackTrace(System.err);
		}

        try {
            // Get the current process ID
            String name = ManagementFactory.getRuntimeMXBean().getName();
            String pid = name.split("@")[0]; // PID is the part before '@'
            System.out.println(pid);

            if (args != null && args.length > 0 && args[0] != null) {
                final String pidFilePath = args[0];
                // Write PID to a file
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(pidFilePath))) {
                    writer.write(pid);
                    System.out.println("PID written to " + pidFilePath);
                }            
            }
            
        } catch (Throwable e) {
            e.printStackTrace(System.err);
        }


        // Simulate some initialization work
        try {
            TimeUnit.SECONDS.sleep(90); // Simulate loading for 3 seconds
        } catch (InterruptedException e) {
            e.printStackTrace(System.err);
        }

    }

}