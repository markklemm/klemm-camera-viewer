package klemm.technology.camera;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.SplashScreen;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ShortBuffer;
import java.util.Map.Entry;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry;
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
	    
        boolean enableSound  = false;
        boolean isFullScreen = false;
	    
	    if (args.length > 0) {
	        switch (args[0].toLowerCase()) {
	            case "/s":  // run full screen
	                isFullScreen = true;
	                
	                break;
	            case "/c":  // open settings dialog
	                
	                // TODO show settings only
	                return;
//	                break;

	            case "/p":  // preview mode
	                
	                // TODO use the windows handle for the display
                    return;
	                
//	                if (args.length > 1) {
//	                    long hwnd = Long.parseLong(args[1]);
//	                    showPreview(hwnd);
//	                }
//	                break;
                    
	            case "--enableSound":
	                
	                enableSound = true;
	                
	                break;
	                
	           case "--isFullScreen":
                    
                    isFullScreen = true;
                    
                    break;	                
                    
	            default:
	                System.out.println("Unknown argument: " + args[0]);
	        }
	    }
	    
	    try {
            new RtspStreamViewer(enableSound, isFullScreen, null, 1).run();
        }
        catch (Exception m) {
            m.printStackTrace(System.err);
        }
	}


	private CanvasFrame canvas;

	private boolean   enableSound  = false;
    private boolean   isFullScreen = false; // Track fullscreen state

    
    private final String rtspUrl;
    private final int cameraNumber;

	public RtspStreamViewer(final boolean enableSound, final boolean isFullScreen, final String address, final int cameraNumber) {
		this.enableSound  = enableSound;
		this.isFullScreen = isFullScreen;
		
		if (address == null || address.trim().length() == 0) {
		    this.rtspUrl = "rtsp://camera1:camera12@192.168.1.106:554/stream2";
		    
		    System.out.println("Connecting to default IP:" + this.rtspUrl);
		}
		else {
		    this.rtspUrl = "rtsp://camera1:camera12@" + address + ":554/stream2";
		    
		    System.out.println("Connecting to provided IP:" + this.rtspUrl);
		}
		
		this.cameraNumber = cameraNumber;
		
		
	}

	public void run() {
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

		

		// avutil.av_log_set_level(avutil.AV_LOG_INFO);
		FFmpegLogCallback.set();

		System.out.println("Init complete");

		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl)) {
		
			grabber.setFormat("rtsp");
		    grabber.setOption("rtsp_transport", "tcp");   // try TCP first
		    
		    for (Entry<String, String> e : grabber.getOptions().entrySet())
		    {
		        System.out.println("Key: " + e.getKey() + ", value: " + e.getValue());
		    }
		    
            System.out.println("Options set, starting feed");
		    
		    grabber.start(false);
			
			System.out.println("Started feed");

			// Video display setup
			final CanvasFrame canvas = new CanvasFrame("Klemm Camera: " + this.cameraNumber, CanvasFrame.getDefaultGamma() / grabber.getGamma());
		
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
					adjustFrameSize(canvas, isFullScreen);					

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

	private void adjustFrameSize(CanvasFrame canvas, boolean isFullScreen) {

		if (canvas == null) {
			return;
		}

        // Get current frame size
        Dimension currentSize = canvas.getSize();
        int currentWidth = currentSize.width;
        int currentHeight = currentSize.height;
        
        System.out.println("Current Size:" + currentWidth + "x" + currentHeight);

        // Tolerance
        int tolerance = (isFullScreen ? 1000 : 100);
        
        System.out.println("Fullscreen:" + isFullScreen);
        System.out.println("Tolerance:" + tolerance);

        // Get screen dimensions
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int screenWidth = screenSize.width;
        int screenHeight = screenSize.height;
        
        System.out.println("ScreenSize Size:" + screenWidth + "x" + screenHeight);

        // Check if the screen resolution is within the tolerance of the current frame size
        if (Math.abs(screenWidth - currentWidth) <= tolerance || Math.abs(screenHeight - currentHeight) <= tolerance) {
            // Maximize the frame
            canvas.setExtendedState(JFrame.MAXIMIZED_BOTH);
        } else {
            // Center the frame
            canvas.setLocationRelativeTo(null);
            
            if (this.cameraNumber > 1) {
                SwingUtilities.invokeLater(new Runnable() {
                    
                    @Override
                    public void run() {
                        final Point location = canvas.getLocation();
                        
                        canvas.setLocation((location.x + (50 * cameraNumber)), (location.y + (50 * cameraNumber)));
                    }
                });                
            }
            
        }
    }

	public void toggleSound() {
	    // TODO enable sound is per frame
	}

    public void toggleFullScreen() {
        this.isFullScreen = !this.isFullScreen;
		
    }	

}
