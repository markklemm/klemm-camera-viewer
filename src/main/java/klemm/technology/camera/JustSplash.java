package klemm.technology.camera;

import java.util.concurrent.TimeUnit;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

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
			t.printStackTrace();
		}

        try {
            // Get the current process ID
            String name = ManagementFactory.getRuntimeMXBean().getName();
            String pid = name.split("@")[0]; // PID is the part before '@'
            
            // Define the file path where PID will be written
            String pidFilePath = "splash.pid";

            // Write PID to a file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(pidFilePath))) {
                writer.write(pid);
                System.out.println("PID written to " + pidFilePath);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }


        // Simulate some initialization work
        try {
            TimeUnit.SECONDS.sleep(90); // Simulate loading for 3 seconds
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

}