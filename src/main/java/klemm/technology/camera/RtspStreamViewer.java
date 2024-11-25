package klemm.technology.camera;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.SplashScreen;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ShortBuffer;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Frame;

/* 
 * TODO this is a sample only
 * TODO there should be a single Canvas and a number of Frames with matching Grabbers - where the grabbers run in a Future
 * TODO the Discovery will advise when a new Frame/Grabber should be created and tiled only to the Canvas.
 * TODO each Frame should have a panel that draws over the top as a Glass Pane with buttons and controls for that Frame
 * TODO the Canvas should have a panel that draws over the top as a Glass Pane
 * TODO the Canvas Glass Pane should draw from each of the Frames
 */

public class RtspStreamViewer {
	
	public static void main(String[] args) {

		try {

			boolean enableSound  = false;
			boolean isFullScreen = false;

			if (args != null && args.length > 0 && args[0] != null) {
				enableSound = ("--enableSound".equals(args[0]));
			}

			if (args != null && args.length > 0 && args[0] != null) {
				isFullScreen = ("--isFullScreen".equals(args[0]));
			}			

			new RtspStreamViewer(enableSound, isFullScreen).run();
		}
		catch (Exception m) {
			m.printStackTrace();
		}
	}


	private CanvasFrame canvas;

	private boolean   enableSound  = false;
    private boolean   isFullScreen = false; // Track fullscreen state


	private RtspStreamViewer(final boolean enableSound, final boolean isFullScreen) {
		this.enableSound  = enableSound;
		this.isFullScreen = isFullScreen;
	}

	private void run() {
		// Show the splash screen (this happens automatically if specified in MANIFEST.MF)
		final SplashScreen splash = SplashScreen.getSplashScreen();

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

		String rtspUrl = "rtsp://camera1:camera12@10.1.1.75:554/stream1";

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

					// Close the splash screen when done
					try {
						if (splash != null && splash.isVisible()) {
							splash.close();
						}	
					}
					catch (Throwable t) {
						t.printStackTrace();
					}						
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

		if (canvas == null) {
			return;
		}

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

	public void toggleSound() {
	    // TODO enable sound is per frame
	}

    public void toggleFullScreen() {
        this.isFullScreen = !this.isFullScreen;
		
    }	

}
