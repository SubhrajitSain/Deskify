// server

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.swing.*;

import com.formdev.flatlaf.FlatDarkLaf; // imported during build, don't worry

public class DeskifyServer {

    private static ServerSocket videoServer;
    private static ServerSocket eventServer;
    private static Socket videoSocket;
    private static Socket eventSocket;

    private static volatile boolean running = false;

    private static JTextField vPortField = new JTextField("5900");
    private static JTextField ePortField = new JTextField("5901");
    private static JLabel statusLabel = new JLabel("● Idle");

    private static float imageQuality = 0.7f;

    private static final byte AUTH = 9;
    private static final byte QUALITY_UPDATE = 10;

    private static String serverPassword;

    public static void main(String[] args) {

        try {
            FlatDarkLaf.setup();
            UIManager.put("Component.arc", 15);
            UIManager.put("Button.arc", 20);
            UIManager.put("TextComponent.arc", 15);
            UIManager.put("TextComponent.background", new Color(30, 30, 30));
            UIManager.put("TextComponent.foreground", Color.WHITE);
            UIManager.put("TextComponent.caretForeground", Color.WHITE);
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(DeskifyServer::createUI);
    }

    private static void createUI() {

        JFrame frame = new JFrame("Deskify Server");
        try {
            frame.setIconImage(ImageIO.read(new File("deskify-logo.png")));
        } catch (IOException ignored) {}
        frame.setLayout(new BorderLayout());

        JTextArea logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        frame.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setPreferredSize(new Dimension(0, 50));
        bottomBar.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));

        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));

        vPortField.setToolTipText("Video port");
        ePortField.setToolTipText("Events port");

        styleTextField(vPortField);
        styleTextField(ePortField);

        JCheckBox tlsBox = new JCheckBox("Enable TLS");
        tlsBox.setToolTipText("Encrypts the connection using TLS (recommended for internet use)");
        tlsBox.addActionListener(e -> {
            if (tlsBox.isSelected()) {
                JOptionPane.showMessageDialog(frame,
                    "TLS encryption is only necessary if using Deskify over the internet.\n" +
                    "It adds security but can cause a minor drop in connection speeds.\n" +
                    "TLS will be enabled the next time the server is started.",
                    "Deskify Server",
                    JOptionPane.WARNING_MESSAGE);
            }
        });

        inputPanel.add(new JLabel("Video:"));
        inputPanel.add(vPortField);
        inputPanel.add(new JLabel("Events:"));
        inputPanel.add(ePortField);
        inputPanel.add(tlsBox);

        JButton startBtn = new JButton("Start");
        JButton stopBtn = new JButton("Stop");

        startBtn.setToolTipText("Starts the server");
        stopBtn.setToolTipText("Stops the server");

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightPanel.add(statusLabel);
        rightPanel.add(startBtn);
        rightPanel.add(stopBtn);

        bottomBar.add(inputPanel, BorderLayout.CENTER);
        bottomBar.add(rightPanel, BorderLayout.EAST);

        frame.add(bottomBar, BorderLayout.SOUTH);

        startBtn.addActionListener(e -> {
            JPasswordField passwordField = new JPasswordField();
            passwordField.setPreferredSize(new Dimension(200, 28));

            int option = JOptionPane.showConfirmDialog(
                    null,
                    passwordField,
                    "Set Server Password",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
            );

            if (option != JOptionPane.OK_OPTION) {
                return;
            }

            String pass = new String(passwordField.getPassword());

            if (pass.isBlank()) {
                JOptionPane.showMessageDialog(
                        null,
                        "Password cannot be empty.",
                        "Deskify Server",
                        JOptionPane.ERROR_MESSAGE
                );
                return;
            } else if (pass.length() < 8) {
                JOptionPane.showMessageDialog(
                        null,
                        "Password cannot be less than 8 characters.",
                        "Deskify Server",
                        JOptionPane.ERROR_MESSAGE
                );
                return;
            }

            serverPassword = pass;
            boolean useTLS = tlsBox.isSelected();
            startServer(logArea, useTLS);
        });
        stopBtn.addActionListener(e -> stopServer(logArea));

        frame.setSize(700, 500);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        updateStatus("● Offline", new Color(200, 200, 200));
        log(logArea, "+----------------------------------------+");
        log(logArea, "| DESKIFY                                |");
        log(logArea, "+----------------------------------------+");
        log(logArea, "| A remote desktop access suite. By ANW. |");
        log(logArea, "+----------------------------------------+\n");
        log(logArea, "[i] Click 'Start' after configuring the video and events ports below.");
    }

    private static void styleTextField(JTextField field) {
        field.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        field.setPreferredSize(new Dimension(50, 22));
        field.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    }

    private static void ensureKeystore(JTextArea logArea) {
        File ksFile = new File("deskify.p12");
        if (ksFile.exists()) return; 

        log(logArea, "[x] File 'deskify.p12' not found, maybe first start.");
        log(logArea, "[i] Generating TLS certificates, this might take some time...");
        try {
            String javaHome = System.getProperty("java.home");
            String keytoolPath = javaHome + File.separator + "bin" + File.separator + "keytool";
            
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                keytoolPath += ".exe";
            }

            ProcessBuilder pb = new ProcessBuilder(
                keytoolPath,
                "-genkeypair",
                "-alias", "deskify",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-storetype", "PKCS12",
                "-keystore", "deskify.p12",
                "-validity", "3650",
                "-storepass", "DeskifyTLS",
                "-keypass", "DeskifyTLS",
                "-dname", "CN=DeskifyServer",
                "-noprompt"
            );

            Process p = pb.start();
            
            // Give it a moment to work (RSA generation takes a second)
            if (p.waitFor() == 0) {
                log(logArea, "[s] TLS encryption certificate ready.");
            } else {
                log(logArea, "[x] Certificate generation error.");
            }
        } catch (Exception e) {
            log(logArea, "[x] Critical security error: " + e.getMessage());
        }
    }

    private static void startServer(JTextArea logArea, boolean useTLS) {
        if (running) return;

        try {
            int videoPort = Integer.parseInt(vPortField.getText());
            int eventPort = Integer.parseInt(ePortField.getText());

            if (useTLS) {
                log(logArea, "[i] Using TLS encryption...");
                ensureKeystore(logArea);
                char[] password = "DeskifyTLS".toCharArray();
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream("deskify.p12")) {
                    ks.load(fis, password);
                }

                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, password);

                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(kmf.getKeyManagers(), null, null);

                SSLServerSocketFactory ssf = sc.getServerSocketFactory();
                videoServer = ssf.createServerSocket(videoPort);
                eventServer = ssf.createServerSocket(eventPort);
                log(logArea, "[s] TLS enabled.");
            } else {
                // Standard Unencrypted Sockets
                videoServer = new ServerSocket(videoPort);
                eventServer = new ServerSocket(eventPort);
                log(logArea, "[i] Not using TLS.");
            }

            log(logArea, "[s] Server started. Video port: " + videoPort + " | Events port: " + eventPort);

            running = true;
            updateStatus("● Waiting...", new Color(255, 170, 0));
            log(logArea, "[i] Online, waiting for a client to join...");

            new Thread(() -> {
                int tlsErrCount = 0;
                while(running) {
                    try {
                        Robot robot = new Robot();
                        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
                        Rectangle screenRect = new Rectangle(screenSize);

                        try {
                            videoSocket = videoServer.accept();
                            videoSocket.setTcpNoDelay(true);
                        } catch (IOException e) {
                            tlsErrCount++;
                            log(logArea, "[x] Client may have failed to detect TLS/non-TLS connection. Retrying (" + tlsErrCount + "/3)...");
                            if (tlsErrCount >= 3) {
                                log(logArea, "[x] Max retries exceeded. Stopping server...");
                                stopServer(logArea);
                                return;
                            }
                            continue; 
                        }
                        log(logArea, "[i] Incoming connection from: " + videoSocket.getInetAddress());

                        DataInputStream authIn = new DataInputStream(videoSocket.getInputStream());
                        DataOutputStream authOut = new DataOutputStream(videoSocket.getOutputStream());

                        byte code = authIn.readByte();
                        if (code != AUTH) {
                            videoSocket.close();
                            return;
                        }

                        String clientPass = authIn.readUTF();
                        String verificationCode = authIn.readUTF();

                        if (!serverPassword.equals(clientPass)) {
                            authOut.writeBoolean(false);
                            authOut.flush();
                            videoSocket.close();
                            log(logArea, "[x] Wrong password attempt from: " + videoSocket.getInetAddress());
                            return;
                        }

                        final String displayCode = verificationCode;
                        final int[] allow = new int[1];

                        try {
                            SwingUtilities.invokeAndWait(() -> {
                                JPanel panel = new JPanel(new GridLayout(0, 1));
                                panel.add(new JLabel("A client is requesting access."));
                                JLabel codeLabel = new JLabel(displayCode);
                                codeLabel.setFont(new Font("Monospaced", Font.BOLD, 30));
                                codeLabel.setHorizontalAlignment(SwingConstants.CENTER);
                                codeLabel.setForeground(new Color(0, 150, 255));
                                panel.add(codeLabel);
                                panel.add(new JLabel("Do the codes on the client and server both match?"));

                                allow[0] = JOptionPane.showConfirmDialog(
                                        null,
                                        panel,
                                        "Connection Request",
                                        JOptionPane.YES_NO_OPTION,
                                        JOptionPane.QUESTION_MESSAGE
                                );
                            });
                        } catch (Exception e) {
                            log(logArea, "[x] Dialog Error: " + e.getMessage());
                            return;
                        }

                        if (allow[0] != JOptionPane.YES_OPTION) {
                            authOut.writeBoolean(false);
                            authOut.flush();
                            videoSocket.close();
                            log(logArea, "[i] Connection rejected by user. Not connecting.");
                            return;
                        }

                        authOut.writeBoolean(true);
                        authOut.flush();

                        eventSocket = eventServer.accept();
                        eventSocket.setTcpNoDelay(true);

                        final boolean[] sessionActive = {true};

                        updateStatus("● Connected", new Color(0, 200, 0));
                        log(logArea, "[s] Access granted to: " + videoSocket.getInetAddress());

                        byte qualityCode = authIn.readByte();

                        if (qualityCode == QUALITY_UPDATE) {
                            String q = authIn.readUTF();

                            switch (q) {
                                case "Low" -> imageQuality = 0.3f;
                                case "Medium" -> imageQuality = 0.6f;
                                case "High" -> imageQuality = 0.9f;
                            }

                            log(logArea, "[i] Image quality: " + q);
                        }

                        Thread videoThread = new Thread(() -> {
                            try (DataOutputStream dos = new DataOutputStream(videoSocket.getOutputStream());
                                DataInputStream dis = new DataInputStream(videoSocket.getInputStream())) {

                                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
                                ImageWriteParam param = writer.getDefaultWriteParam();
                                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);

                                while (running && sessionActive[0]) {
                                    if (videoSocket.getInputStream().available() > 0) {
                                        byte readCode = dis.readByte();
                                        if (readCode == QUALITY_UPDATE) {
                                            String q = dis.readUTF();
                                            imageQuality = switch (q) {
                                                case "Low" -> 0.3f;
                                                case "Medium" -> 0.6f;
                                                default -> 0.9f;
                                            };
                                            log(logArea, "[i] Image quality switched to: " + q);
                                        }
                                    }

                                    BufferedImage screenshot = robot.createScreenCapture(screenRect);
                                    
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    param.setCompressionQuality(imageQuality);

                                    try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                                        writer.setOutput(ios);
                                        writer.write(null, new IIOImage(screenshot, null, null), param);
                                    }

                                    byte[] data = baos.toByteArray();
                                    dos.writeInt(data.length);
                                    dos.write(data);
                                    dos.flush();

                                    Thread.sleep(35);
                                }
                                writer.dispose();
                            } catch (Exception ex) {
                                String msg = ex.getMessage();
                                if (msg != null && !msg.contains("Connection reset") && !msg.contains("aborted") && !msg.contains("null") && !msg.contains("connection was aborted")) {
                                    log(logArea, "[x] Video stream error: " + msg);
                                } else {
                                    log(logArea, "[i] Client disconnected, waiting for new clients...");
                                }
                                sessionActive[0] = false;
                            }
                        });

                        Thread eventThread = new Thread(() -> {
                            try (DataInputStream dis =
                                        new DataInputStream(eventSocket.getInputStream())) {

                                while (running && sessionActive[0]) {
                                    byte type = dis.readByte();
                                    int p1 = dis.readInt();
                                    int p2 = dis.readInt();

                                    switch (type) {
                                        case 1 -> robot.mouseMove(p1, p2);
                                        case 2 -> robot.mousePress(getMouseMask(p1));
                                        case 3 -> robot.mouseRelease(getMouseMask(p1));
                                        case 4 -> robot.keyPress(p1);
                                        case 5 -> robot.keyRelease(p1);
                                    }
                                }
                            } catch (Exception ex) {
                                String msg = ex.getMessage();
                                if (msg != null && !msg.contains("Connection reset") && !msg.contains("aborted") && !msg.contains("null") && !msg.contains("connection was aborted")) {
                                    log(logArea, "[x] Events stream error: " + msg);
                                } else {
                                    log(logArea, "[i] Client disconnected, waiting for new clients...");
                                }
                                sessionActive[0] = false;
                            }
                        });

                        videoThread.start();
                        eventThread.start();
                        videoThread.join();
                        eventThread.join();

                        cleanup(videoSocket, eventSocket);

                        updateStatus("● Waiting...", new Color(255, 170, 0));

                    } catch (Exception ex) {
                        if (running) {
                            log(logArea, "[x] Session reset! Critical error: " + ex.getMessage());

                            JOptionPane.showMessageDialog(
                                    null,
                                    "Server error:\n" + ex.getMessage(),
                                    "Deskify Server",
                                    JOptionPane.ERROR_MESSAGE
                            );
                        }
                    }
                }
            }).start();

        } catch (Exception e) {
            log(logArea, "[x] Failed to start server.");
            JOptionPane.showMessageDialog(
                    null,
                    "Failed to start server.\nCheck if ports are already in use, or use different settings.\nError: " + e.getMessage(),
                    "Deskify Server",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private static void stopServer(JTextArea logArea) {
        running = false;
        cleanup(videoSocket, eventSocket, videoServer, eventServer);
        updateStatus("● Stopped", new Color(200, 0, 0));
        log(logArea, "[s] Server stopped successfully.");
    }

    private static void updateStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    private static void log(JTextArea area, String msg) {
        SwingUtilities.invokeLater(() -> {
            area.append(msg + "\n");
        });
    }

    private static int getMouseMask(int button) {
        return switch (button) {
            case 1 -> InputEvent.BUTTON1_DOWN_MASK;
            case 2 -> InputEvent.BUTTON2_DOWN_MASK;
            case 3 -> InputEvent.BUTTON3_DOWN_MASK;
            default -> 0;
        };
    }

    private static void cleanup(Closeable... resources) {
        for (Closeable r : resources) {
            if (r != null) {
                try { r.close(); }
                catch (IOException ignored) {}
            }
        }
    }
}