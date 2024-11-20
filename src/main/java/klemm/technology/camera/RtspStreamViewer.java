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
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

public class RtspStreamViewer {

	protected volatile boolean keepScanningCameras = true;

//	protected int start  = 2;
//	protected int size   = 15;
//	protected int number = 10;
	
	public static void main(String[] args) {
		try {
			new RtspStreamViewer().run();
		}
		catch (Exception m) {
			m.printStackTrace();
		}

	}

	private RtspStreamViewer() {
	}

	private void run() {
		System.out.println("Starting");

		//while (number > 0 && start < (255 - size)) {
	//		final Thread t = new Thread(new Runnable() {
	//			@Override
	//			public void run() {
	//				List<String> cameras = findCameras("10.1.1", RtspStreamViewer.this.start, RtspStreamViewer.this.size);
	//				System.out.println("Cameras found: " + cameras);
	//			}
	//		});
	//	
	//	//	t.start();
//
//			this.start  += this.size;
//			this.number --;
//		}



		String rtspUrl = "rtsp://camera1:camera12@10.1.1.7:554/stream1";

		// avutil.av_log_set_level(avutil.AV_LOG_INFO);
		FFmpegLogCallback.set();

		System.out.println("Init complete");

		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl)) {
			grabber.setOption("rtsp_transport", "tcp");
			grabber.start();

			System.out.println("Started feed");

			// Video display setup
			CanvasFrame canvas = new CanvasFrame("Klemm Camera - Iris Court",
					CanvasFrame.getDefaultGamma() / grabber.getGamma());

			canvas.setCanvasSize(960, 540); // 1920x1080 / 2

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
			canvas.setVisible(true);

			System.out.println("Canvas init complete");

			// Set up audio output
			AudioFormat audioFormat = new AudioFormat(grabber.getSampleRate(), 16, grabber.getAudioChannels(), true, true);
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
			SourceDataLine audioLine = (SourceDataLine) AudioSystem.getLine(info);
			audioLine.open(audioFormat);
			audioLine.start();

			System.out.println("Audio started");

			// Stream video and audio
			Frame frame;
			while (canvas.isVisible() && (frame = grabber.grab()) != null) {
				// Display video frames
				if (frame.image != null) {
					canvas.showImage(frame);
				}

				// Play audio frames
				if (frame.samples != null) {
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
			audioLine.drain();
			audioLine.close();
			
			this.keepScanningCameras = false;

			System.out.println("Cleaned up");

		} catch (Exception e) {
			e.printStackTrace();
		}

		System.out.println("Done");
	}

	// protected List<String> findCameras(String subnet, int number, int size) {
	// 	List<String> cameraAddresses = new ArrayList<>();
	// 	int port = 554;
	// 	String path = "/stream1";

	// 	System.out.println("Scanning for cameras on subnet: " + subnet);

	// 	// Scan IP range from 1 to 254 on the provided subnet
	// 	for (int i = number; i < 255 && i < (number + size); i++) {
					
	// 		String ipAddress = subnet + "." + i;

	// 		// Check if port 554 is open
	// 		try (Socket socket = new Socket(ipAddress, port)) {
	// 			socket.setSoTimeout(100); // Set a timeout for quick scanning
	// 			System.out.println("Port 554 open on " + ipAddress);

	// 			// Check if there's an RTSP stream at /stream1
	// 			String rtspUrl = "rtsp://camera1:camera12@" + ipAddress + ":" + port + path;
	// 			if (isRtspStreamActive(rtspUrl)) {
	// 				cameraAddresses.add(rtspUrl);
	// 				System.out.println("Camera found at " + rtspUrl);
	// 			}
	// 			else {
	// 				System.out.println("Camera not at " + rtspUrl);					
	// 			}

	// 		} catch (Exception e) {
	// 			// Ignore hosts where connection failed
	// 			System.out.println("Camera not at " + ipAddress);						
	// 		}

	// 		if (!this.keepScanningCameras) {
	// 			break;
	// 		}
			
	// 	}

	// 	if (cameraAddresses.isEmpty()) {
	// 		System.out.println("No cameras found on the subnet.");
	// 	}

	// 	return cameraAddresses;
	// }

	// private static boolean isRtspStreamActive(String rtspUrl) {
	// 	try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl)) {
	// 		grabber.setOption("rtsp_transport", "tcp");
	// 		grabber.start();
	// 		grabber.stop();
	// 		return true;
	// 	} catch (Exception e) {
	// 		// Stream not active or not accessible
	// 		return false;
	// 	}
	// }

}
