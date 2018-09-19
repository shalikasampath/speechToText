package com.microsoft.cognitive_services.speech_recognition.SpeechAPI;


import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.apache.tomcat.jni.Time;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.microsoft.cognitive_services.speech_recognition.SpeechAPI.SpeechAPI.Language;
import com.microsoft.cognitive_services.speech_recognition.SpeechAPI.SpeechAPI.OutputFormat;
import com.microsoft.cognitive_services.speech_recognition.SpeechAPI.SpeechAPI.RecognitionMode;

@Controller
public class ExtendedExample extends JPanel implements ActionListener {

  private final JButton micButton;
  private final JTextArea log;
  private final JFileChooser fc;
  private final JTextField keyField;

  private final JComboBox<RecognitionMode> modeBox;
  private final JComboBox<Language> languageBox;
  private final JComboBox<OutputFormat> formatBox;

  private static final int MIC_SAMPLE_RATE = 16000;
  private static final int MIC_CHANNEL_COUNT = 1;
  private static final int MIC_BITS_PER_SAMPLE = 16;
  private static final int MIC_BYTES_PER_SAMPLE = MIC_BITS_PER_SAMPLE / 8;
  private static final int MIC_BYTE_RATE = MIC_SAMPLE_RATE * MIC_BYTES_PER_SAMPLE;
  private static final byte[] WAV_HEADER;

  private volatile boolean bootstrapped = false;
  private AtomicBoolean recording = new AtomicBoolean(false);
  private SpeechClientREST speechClient;
  
  // API SUBSCRIPTION KEY
  private static String text = "841ed6260bd24adf8750c83b7049fbf8";
  static JFrame frame = new JFrame("Extended Speech Services Example");
  public String result_pass;
  ModelMap model;
  
  static {
    ByteBuffer buffer = ByteBuffer.allocate(44);
    buffer.order(ByteOrder.LITTLE_ENDIAN);

    // RIFF identifier
    buffer.put("RIFF".getBytes());
    // file length, we dont know ahead of time about the 
    // length of audio to stream, So setting this to 0.
    buffer.putInt(0);
    // RIFF type & Format
    buffer.put("WAVEfmt ".getBytes());
    // format chunk length
    buffer.putInt(16);
    // sample format (raw)
    buffer.putShort((short) 1);
    // channel count (1)
    buffer.putShort((short) MIC_CHANNEL_COUNT);
    // sample rate
    buffer.putInt(MIC_SAMPLE_RATE);
    // byte rate (sample rate * block align)
    buffer.putInt(MIC_BYTE_RATE);
    // block align (channel count * bytes per sample)
    buffer.putShort((short) MIC_BYTES_PER_SAMPLE);
    // bits per sample
    buffer.putShort((short) MIC_BITS_PER_SAMPLE);
    // data chunk identifier
    buffer.put("data".getBytes());
    // data chunk length
    buffer.putInt(40);

    WAV_HEADER = buffer.array();
  }

  public ExtendedExample() {

    super(new BorderLayout());

    keyField = new JTextField(32);
    modeBox = new JComboBox<>(SpeechAPI.RecognitionMode.values());
    languageBox = new JComboBox<>(SpeechAPI.Language.values());
    formatBox = new JComboBox<>(SpeechAPI.OutputFormat.values());

    log = new JTextArea(20, 40);
    log.setMargin(new Insets(5, 5, 5, 5));
    log.setEditable(false);
    log.setLineWrap(true);
    JScrollPane logScrollPane = new JScrollPane(log);

    fc = new JFileChooser();
    fc.setFileFilter(new FileNameExtensionFilter("WAV audio files", "wav"));


    micButton = new JButton("Use Microphone", UIManager.getIcon("Tree.expandedIcon"));
    micButton.addActionListener(this);

    JPanel midPanel = new JPanel();

    midPanel.add(micButton);

    JPanel knobs = new JPanel(new GridLayout(0, 2));
    knobs.add(new JLabel("Recognition mode:"));
    knobs.add(modeBox);
    knobs.add(new JLabel("Recognition language:"));
    knobs.add(languageBox);
    knobs.add(new JLabel("Output format:"));
    knobs.add(formatBox);

    midPanel.add(knobs);

    micButton.setEnabled(false);

    JPanel keyPanel = new JPanel();

    CompletableFuture.supplyAsync(() -> {
        return new RenewableAuthentication(text);
      }).thenAccept(this::bootstrap);

    add(keyPanel, BorderLayout.NORTH);
    add(midPanel, BorderLayout.CENTER);
    add(logScrollPane, BorderLayout.SOUTH);

    languageBox.setSelectedItem(SpeechAPI.Language.en_US);
  }

  private synchronized void bootstrap(Authentication auth) {
    if (bootstrapped)
      return;
    
    bootstrapped = true;
    speechClient = new SpeechClientREST(auth);

    micButton.setEnabled(true);
    keyField.setEnabled(false);


  }

  public void actionPerformed(ActionEvent e) {

    speechClient.setMode(modeBox.getItemAt(modeBox.getSelectedIndex()));
    speechClient.setLanguage(languageBox.getItemAt(languageBox.getSelectedIndex()));
    speechClient.setFormat(formatBox.getItemAt(formatBox.getSelectedIndex()));

    if (e.getSource() == micButton) {
      if (!recording.get()) {
        log.append("Recording microphone input (15 seconds) ... ");
        CompletableFuture.runAsync(this::processMicrophoneInput);
      } else {
        recording.set(false);
      }
    }
  }

  private synchronized void processMicrophoneInput() {
    if (recording.getAndSet(true))
      return;
    micButton.setText("Stop Recording");
    AudioFormat format = new AudioFormat(MIC_SAMPLE_RATE, MIC_BITS_PER_SAMPLE, MIC_CHANNEL_COUNT, true, false);
    try (TargetDataLine microphone = AudioSystem.getTargetDataLine(format);
        PipedOutputStream source = new PipedOutputStream();) {

      microphone.open(format);
      microphone.start();

      CountDownLatch startToRecordLatch = new CountDownLatch(1), startToUploadLatch = new CountDownLatch(1);

      CompletableFuture.runAsync(() -> {
        // Using a buffer here to hold up to 5 seconds of speech.
        try (PipedInputStream sink = new PipedInputStream(MIC_BYTE_RATE * 5)) {
          source.connect(sink);
          startToRecordLatch.countDown();
          startToUploadLatch.await();
          String result = speechClient.process(sink);
          log.append(String.format("Speech recognition results:\n%s\n", result));
          result_pass = String.format(result);
        } catch (Exception error) {
          log.append(String.format("Ups...something went wrong (%s).\n", error.getMessage()));
        }
        finally {
        	try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
            System.out.println(result_pass);
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        }
      });

      startToRecordLatch.await();

      byte[] buffer = new byte[1024];

      source.write(WAV_HEADER);
      
      // 15 seconds of audio in bytes =
      // (byte rate per second = 16000 (samples per second) * 2 (bytes per sample)) * 15 (seconds)
      // However, REST request times out after 14000 ms (with a 408), so before
      // starting to upload the audio, we need to buffer it for a few (4) seconds.
      for (int i = 0; recording.get() && i < MIC_BYTE_RATE * 15 / buffer.length; i++) {

        if (i >= MIC_BYTE_RATE * 4 / buffer.length && startToUploadLatch.getCount() == 1) {
          startToUploadLatch.countDown();
        }

        int count = microphone.read(buffer, 0, buffer.length);
        source.write(buffer, 0, count);
      }

      // decrement the latch count one more time, in case we were interrupted
      // before reaching the 4 seconds mark.
      startToUploadLatch.countDown();

      source.flush();
      source.close();

      log.append("Waiting for recognition results...\n");

    } catch (Exception error) {
      log.append(String.format("Microphone is not working (%s).\n", error.getMessage()));
    } finally {
      micButton.setText("Processing Text");

//      recording.set(false);
    }

  }

  private static void createAndShowGUI() {
  //  JFrame frame = new JFrame("Extended Speech Services Example");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.add(new ExtendedExample());
    frame.pack();
    frame.setVisible(true);
    frame.setLocationRelativeTo(null);
  }
  
  @RequestMapping("/test")
  public static void main(String[] args) {
        createAndShowGUI(); }
}
