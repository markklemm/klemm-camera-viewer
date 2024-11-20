package klemm.technology.camera;

import java.net.Socket;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.swing.JFrame;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Frame;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

public class RtspStreamViewer {
	
	public static void main(String[] args) {

		try {

			boolean enableSound = false;
			if (args != null && args.length > 0 && args[0] != null) {
				enableSound = ("--sound".equals(args[0]));
			}

			new RtspStreamViewer(enableSound).run();
		}
		catch (Exception m) {
			m.printStackTrace();
		}



	}

	private boolean enableSound = false;

	private RtspStreamViewer(final boolean enableSound) {
		this.enableSound = enableSound;
	}

	private void run() {
		// Show the splash screen (this happens automatically if specified in MANIFEST.MF)
		SplashScreen splash = SplashScreen.getSplashScreen();

		try {
			if (splash != null) {
				Graphics2D g = splash.createGraphics();
				if (g != null) {
					// Display loading messages on the splash screen
					g.setColor(Color.BLACK);
					g.setFont(new Font("Arial", Font.BOLD, 14));
					g.drawString("Connecting to camera, please wait...", 20, 50);
					splash.update();
				}
			}

		}
		catch (Throwable t) {
			t.printStackTrace();
		}

		System.out.println("Starting");

		String rtspUrl = "rtsp://camera1:camera12@10.1.1.7:554/stream1";

		// avutil.av_log_set_level(avutil.AV_LOG_INFO);
		FFmpegLogCallback.set();

		System.out.println("Init complete");

		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl)) {
			grabber.setOption("rtsp_transport", "tcp");
			grabber.start();

			System.out.println("Started feed");

			// Video display setup
			final CanvasFrame canvas = new CanvasFrame("Klemm Camera - Iris Court", CanvasFrame.getDefaultGamma() / grabber.getGamma());
		
			try {
				// Load the icon image from the resources folder
				InputStream iconStream = RtspStreamViewer.class.getClassLoader().getResourceAsStream("security-camera.png");
				
				if (iconStream != null) {
					BufferedImage iconImage = ImageIO.read(iconStream);
					canvas.setIconImage(iconImage);
				} else {
					System.err.println("Icon image not found in resources.");
				}
			} catch (Exception e) {
				e.printStackTrace();
			}

			canvas.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);		
			canvas.setCanvasSize(960, 540); // 1920x1080 / 2
			
			
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					canvas.setVisible(true);
					adjustFrameSize(canvas);					
				}
			});

			System.out.println("Canvas init complete");

			// Set up audio output
			AudioFormat audioFormat = new AudioFormat(grabber.getSampleRate(), 16, grabber.getAudioChannels(), true, true);
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
			
			SourceDataLine audioLine = (SourceDataLine) AudioSystem.getLine(info);

			if (this.enableSound) {
				audioLine.open(audioFormat);
				audioLine.start();
				System.out.println("Audio started");
			}
			else {
				System.out.println("Audio not enabled");
			}
		

			// Stream video and audio
			Frame frame;
			while (canvas.isVisible() && (frame = grabber.grab()) != null) {
				// Display video frames
				if (frame.image != null) {
					canvas.showImage(frame);
				}

				// Play audio frames
				if (this.enableSound && frame.samples != null) {
					ShortBuffer audioBuffer = (ShortBuffer) frame.samples[0];
					audioBuffer.rewind(); // Reset buffer position to zero

					// Allocate byte array to hold the converted short data
					byte[] audioBytes = new byte[audioBuffer.remaining() * 2];

					// Convert each short value to two bytes (little endian)
					for (int i = 0; audioBuffer.hasRemaining(); i += 2) {
						short s = audioBuffer.get();
						audioBytes[i] = (byte) (s & 0xFF); // Lower 8 bits
						audioBytes[i + 1] = (byte) ((s >> 8) & 0xFF); // Upper 8 bits
					}

					// Write the converted bytes to the audio line
					audioLine.write(audioBytes, 0, audioBytes.length);
				}

				// Close the splash screen when done
				try {
					if (splash != null && splash.isVisible()) {
						splash.close();
						splash = null;
					}	
				}
				catch (Throwable t) {
					t.printStackTrace();
				}					
			}

			System.out.println("Finished");

			grabber.stop();
			canvas.dispose();

			if (this.enableSound) {
				audioLine.drain();
				audioLine.close();
			}
			
			System.out.println("Cleaned up");

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		System.out.println("Done");
	}

	private void adjustFrameSize(CanvasFrame canvas) {
        // Get current frame size
        Dimension currentSize = canvas.getSize();
        int currentWidth = currentSize.width;
        int currentHeight = currentSize.height;

        // Tolerance
        int tolerance = 100;

        // Get screen dimensions
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int screenWidth = screenSize.width;
        int screenHeight = screenSize.height;

        // Check if the screen resolution is within the tolerance of the current frame size
        if (Math.abs(screenWidth - currentWidth) <= tolerance || Math.abs(screenHeight - currentHeight) <= tolerance) {
            // Maximize the frame
            canvas.setExtendedState(JFrame.MAXIMIZED_BOTH);
        } else {
            // Center the frame
            canvas.setLocationRelativeTo(null);
        }
    }

}
