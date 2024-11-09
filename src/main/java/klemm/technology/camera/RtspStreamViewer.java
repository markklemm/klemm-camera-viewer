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

public class RtspStreamViewer {

	private static volatile boolean keepScanningCameras = false;
	
	public static void main(String[] args) {

		final Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				List<String> cameras = findCameras("10.1.1");
				System.out.println("Cameras found: " + cameras);
			}
		});
		keepScanningCameras = true;
		t.start();

		String rtspUrl = "rtsp://camera1:camera12@10.1.1.75:554/stream1";

		avutil.av_log_set_level(avutil.AV_LOG_INFO);
		FFmpegLogCallback.set();

		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl)) {
			grabber.setOption("rtsp_transport", "tcp");
			grabber.start();

			// Video display setup
			CanvasFrame canvas = new CanvasFrame("RTSP Stream Viewer",
					CanvasFrame.getDefaultGamma() / grabber.getGamma());

			canvas.setCanvasSize(1024, 768);

			canvas.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			canvas.setVisible(true);

			// Set up audio output
			AudioFormat audioFormat = new AudioFormat(grabber.getSampleRate(), 16, grabber.getAudioChannels(), true,
					true);
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
			SourceDataLine audioLine = (SourceDataLine) AudioSystem.getLine(info);
			audioLine.open(audioFormat);
			audioLine.start();

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

			grabber.stop();
			canvas.dispose();
			audioLine.drain();
			audioLine.close();
			
			keepScanningCameras = false;
			t.join();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static List<String> findCameras(String subnet) {
		List<String> cameraAddresses = new ArrayList<>();
		int port = 554;
		String path = "/stream1";

		System.out.println("Scanning for cameras on subnet: " + subnet);

		// Scan IP range from 1 to 254 on the provided subnet
		for (int i = 2; i < 255; i++) {
					
			String ipAddress = subnet + "." + i;

			// Check if port 554 is open
			try (Socket socket = new Socket(ipAddress, port)) {
				socket.setSoTimeout(100); // Set a timeout for quick scanning
				System.out.println("Port 554 open on " + ipAddress);

				// Check if there's an RTSP stream at /stream1
				String rtspUrl = "rtsp://" + ipAddress + ":" + port + path;
				if (isRtspStreamActive(rtspUrl)) {
					cameraAddresses.add(rtspUrl);
					System.out.println("Camera found at " + rtspUrl);
				}
				else {
					System.out.println("Camera not at " + rtspUrl);					
				}

			} catch (Exception e) {
				// Ignore hosts where connection failed
				System.out.println("Camera not at " + ipAddress);						
			}

			if (!keepScanningCameras) {
				break;
			}
			
		}

		if (cameraAddresses.isEmpty()) {
			System.out.println("No cameras found on the subnet.");
		}

		return cameraAddresses;
	}

	private static boolean isRtspStreamActive(String rtspUrl) {
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl)) {
			grabber.setOption("rtsp_transport", "tcp");
			grabber.start();
			grabber.stop();
			return true;
		} catch (Exception e) {
			// Stream not active or not accessible
			return false;
		}
	}

}
