// client

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.*;

import com.formdev.flatlaf.FlatDarkLaf; // imported during build, don't worry

public class DeskifyClient {

    private static Dimension serverScreenDim = new Dimension(1920, 1080);
    private static Socket vSocket;
    private static Socket eSocket;
    private static boolean running = false;

    private static JFrame frame;
    private static JLabel displayLabel = new JLabel();
    private static JPanel centerPanel;
    private static CardLayout cardLayout;

    private static JTextField ipField = new JTextField("127.0.0.1");
    private static JTextField vPortField = new JTextField("5900");
    private static JTextField ePortField = new JTextField("5901");
    private static JButton connectBtn;

    private static final byte AUTH = 9;
    private static final byte QUALITY_UPDATE = 10;

    private static String generatedCode;
    private static JDialog waitDialog;

    public static void main(String[] args) {
        try {
            FlatDarkLaf.setup();
            UIManager.put("Component.arc", 20);
            UIManager.put("TextComponent.arc", 20);
            UIManager.put("Button.arc", 20);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("TextComponent.background", new Color(30, 30, 30));
            UIManager.put("TextComponent.foreground", Color.WHITE);
            UIManager.put("TextComponent.caretForeground", Color.WHITE);
        } catch (Exception ex) {}
        SwingUtilities.invokeLater(DeskifyClient::createUI);
    }

    private static void createUI() {
        frame = new JFrame("Deskify | Disconnected");
        frame.setLayout(new BorderLayout());

        try {
            frame.setIconImage(ImageIO.read(new File("deskify-logo.png")));
        } catch (IOException ignored) {}

        cardLayout = new CardLayout();
        centerPanel = new JPanel(cardLayout);
        centerPanel.add(createDisconnectedPanel(), "DISCONNECTED");

        displayLabel.setHorizontalAlignment(JLabel.CENTER);
        displayLabel.setFocusable(true);
        centerPanel.add(new JScrollPane(displayLabel), "CONNECTED");

        frame.add(centerPanel, BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setPreferredSize(new Dimension(0, 42));
        bottomBar.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        styleTextField(ipField);
        styleTextField(vPortField);
        styleTextField(ePortField);

        String[] qualities = {"Low", "Medium", "High"};
        JComboBox<String> qualityBox = new JComboBox<>(qualities);
        qualityBox.setPreferredSize(new Dimension(90, 22));
        
        inputPanel.add(qualityBox);
        inputPanel.add(ipField);
        inputPanel.add(vPortField);
        inputPanel.add(ePortField);

        connectBtn = new JButton("Connect");
        JButton exitBtn = new JButton("Exit");

        connectBtn.setPreferredSize(new Dimension(100, 24));
        exitBtn.setPreferredSize(new Dimension(70, 24));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttonPanel.add(connectBtn);
        buttonPanel.add(exitBtn);

        bottomBar.add(inputPanel, BorderLayout.WEST);
        bottomBar.add(buttonPanel, BorderLayout.EAST);
        frame.add(bottomBar, BorderLayout.SOUTH);

        connectBtn.addActionListener(e -> {
            if (running) shutdown(); 
            else new Thread(() -> connectToServer(qualityBox)).start();
        });

        exitBtn.addActionListener(e -> {
            shutdown();
            System.exit(0);
        });

        qualityBox.addActionListener(e -> {
            if (running) {
                new Thread(() -> {
                    try {
                        DataOutputStream out = new DataOutputStream(vSocket.getOutputStream());
                        out.writeByte(QUALITY_UPDATE);
                        out.writeUTF((String) qualityBox.getSelectedItem());
                        out.flush();
                    } catch (IOException ignored) {}
                }).start();
            }
        });

        frame.setSize(1100, 700);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
        cardLayout.show(centerPanel, "DISCONNECTED");
    }

    private static void connectToServer(JComboBox<String> qualityBox) {
        if (running) return;

        String host = ipField.getText();
        int vPort = Integer.parseInt(vPortField.getText());
        int ePort = Integer.parseInt(ePortField.getText());
        boolean useTLS = false;

        try {
            try {
                SSLSocketFactory factory = getTrustAllSocketFactory();
                vSocket = factory.createSocket();
                vSocket.connect(new InetSocketAddress(host, vPort), 3000);
                
                ((SSLSocket) vSocket).startHandshake();
                useTLS = true;
                
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(frame, 
                        "This server is using end-to-end TLS encryption.", 
                        "Secure Connection", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (IOException sslEx) {
                vSocket = new Socket();
                vSocket.connect(new InetSocketAddress(host, vPort), 3000);
                useTLS = false;
            }

            vSocket.setTcpNoDelay(true);
            DataOutputStream authOut = new DataOutputStream(vSocket.getOutputStream());
            DataInputStream authIn = new DataInputStream(vSocket.getInputStream());

            final String[] passwordWrapper = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                JPasswordField pf = new JPasswordField();
                int ok = JOptionPane.showConfirmDialog(frame, pf, "Enter Server Password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                passwordWrapper[0] = (ok == JOptionPane.OK_OPTION) ? new String(pf.getPassword()) : null;
            });
            String password = (passwordWrapper[0] == null) ? "" : passwordWrapper[0];

            generatedCode = String.format("%06d", new java.util.Random().nextInt(999999));
            new Thread(() -> showWaitDialog(generatedCode)).start();

            authOut.writeByte(AUTH);
            authOut.writeUTF(password);
            authOut.writeUTF(generatedCode);
            authOut.flush();

            boolean approved = authIn.readBoolean();
            if (waitDialog != null) SwingUtilities.invokeLater(waitDialog::dispose);

            if (!approved) {
                SwingUtilities.invokeLater(() -> 
                    JOptionPane.showMessageDialog(frame, "Connection rejected by server. Please verify that the password is correct and try again.", "Error", JOptionPane.ERROR_MESSAGE)
                );
                shutdown();
                return;
            }

            authOut.writeByte(QUALITY_UPDATE);
            authOut.writeUTF((String) qualityBox.getSelectedItem());
            authOut.flush();

            if (useTLS) {
                eSocket = getTrustAllSocketFactory().createSocket(host, ePort);
                ((SSLSocket)eSocket).startHandshake();
            } else {
                eSocket = new Socket(host, ePort);
            }
            eSocket.setTcpNoDelay(true);
            running = true;

            SwingUtilities.invokeLater(() -> {
                connectBtn.setText("Disconnect");
                connectBtn.setBackground(new Color(180, 50, 50));
                cardLayout.show(centerPanel, "CONNECTED");
            });

            updateTitleConnected(host, vPort, ePort);
            setupInputMapping(displayLabel, new DataOutputStream(eSocket.getOutputStream()));
            startVideoThread(authIn);

        } catch (Exception ex) {
            if (waitDialog != null) SwingUtilities.invokeLater(waitDialog::dispose);
            SwingUtilities.invokeLater(() -> 
                JOptionPane.showMessageDialog(frame, "Failed to connect to the server.\nTry checking server details.\nError: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
            );
            shutdown();
        }
    }

    private static SSLSocketFactory getTrustAllSocketFactory() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String auth) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String auth) {}
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        return sc.getSocketFactory();
    }

    private static void startVideoThread(DataInputStream dis) {
        new Thread(() -> {
            try {
                while (running) {
                    int len = dis.readInt();
                    byte[] data = new byte[len];
                    dis.readFully(data);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                    if (img != null) {
                        serverScreenDim = new Dimension(img.getWidth(), img.getHeight());
                        Image scaled = img.getScaledInstance(displayLabel.getWidth(), displayLabel.getHeight(), Image.SCALE_SMOOTH);
                        SwingUtilities.invokeLater(() -> displayLabel.setIcon(new ImageIcon(scaled)));
                    }
                }
            } catch (Exception e) {
                if (waitDialog != null) SwingUtilities.invokeLater(waitDialog::dispose);
                SwingUtilities.invokeLater(() -> 
                    JOptionPane.showMessageDialog(frame, "Connection lost, video disconnected.\nError: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
                );
            } finally {
                running = false;
                shutdown();
            }
        }).start();
    }

    private static void shutdown() {
        running = false;
        try {
            if (vSocket != null) vSocket.close();
            if (eSocket != null) eSocket.close();
        } catch (IOException ignored) {}

        for (MouseListener ml : displayLabel.getMouseListeners()) displayLabel.removeMouseListener(ml);
        for (MouseMotionListener mml : displayLabel.getMouseMotionListeners()) displayLabel.removeMouseMotionListener(mml);
        for (KeyListener kl : displayLabel.getKeyListeners()) displayLabel.removeKeyListener(kl);

        SwingUtilities.invokeLater(() -> {
            connectBtn.setText("Connect");
            connectBtn.setBackground(UIManager.getColor("Button.background"));
            cardLayout.show(centerPanel, "DISCONNECTED");
            updateTitleDisconnected();
            displayLabel.setIcon(null);
        });
    }

    private static void styleTextField(JTextField field) {
        field.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        field.setPreferredSize(new Dimension(100, 22));
        field.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    }

    private static void showWaitDialog(String code) {
        SwingUtilities.invokeLater(() -> {
            waitDialog = new JDialog(frame, "Verification", true);
            waitDialog.setLayout(new GridBagLayout());
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            JLabel msg = new JLabel("Waiting for the server to accept request. Please mutually verify with this code:");
            JLabel codeLbl = new JLabel(code);
            codeLbl.setFont(new Font("Monospaced", Font.BOLD, 28));
            codeLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
            msg.setAlignmentX(Component.CENTER_ALIGNMENT);
            p.add(msg); p.add(Box.createRigidArea(new Dimension(0, 10))); p.add(codeLbl);
            waitDialog.add(p);
            waitDialog.pack();
            waitDialog.setLocationRelativeTo(frame);
            waitDialog.setVisible(true);
        });
    }

    private static JPanel createDisconnectedPanel() {
        JPanel wrapper = new JPanel(new GridBagLayout());
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        JLabel logoLabel = new JLabel();
        logoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        try {
            Image logo = ImageIO.read(new File("deskify-logo-dark.png"));
            Image scaled = logo.getScaledInstance(100, 100, Image.SCALE_SMOOTH);
            logoLabel.setIcon(new ImageIcon(scaled));
        } catch (IOException ignored) {}
        JLabel message = new JLabel("<html><div style='text-align:center;'><b>Disconnected</b><br><br>You are not connected to a remote device.<br>Use the bottom bar to connect to one.<br>Enter the server's IP, video port and events port below.</div></html>");
        message.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        message.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(logoLabel); content.add(Box.createRigidArea(new Dimension(0, 25))); content.add(message);
        wrapper.add(content);
        return wrapper;
    }

    private static void updateTitleConnected(String host, int vPort, int ePort) {
        frame.setTitle("Deskify | Connected: " + host + " | V: " + vPort + " | E: " + ePort);
    }

    private static void updateTitleDisconnected() {
        frame.setTitle("Deskify | Disconnected");
    }

    private static void setupInputMapping(JLabel label, DataOutputStream dos) {
        MouseAdapter adapter = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { sendMapped(e, (byte)1, dos, label); }
            @Override public void mouseDragged(MouseEvent e) { sendMapped(e, (byte)1, dos, label); }
            @Override public void mousePressed(MouseEvent e) { sendEvent(dos, (byte)2, e.getButton(), 0); }
            @Override public void mouseReleased(MouseEvent e) { sendEvent(dos, (byte)3, e.getButton(), 0); }
        };
        label.addMouseListener(adapter);
        label.addMouseMotionListener(adapter);
        label.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { sendEvent(dos, (byte)4, e.getKeyCode(), 0); }
            @Override public void keyReleased(KeyEvent e) { sendEvent(dos, (byte)5, e.getKeyCode(), 0); }
        });
    }

    private static void sendMapped(MouseEvent e, byte type, DataOutputStream dos, JLabel label) {
        if (label.getWidth() == 0 || label.getHeight() == 0) return;
        int remoteX = (int)((double)e.getX() / label.getWidth() * serverScreenDim.width);
        int remoteY = (int)((double)e.getY() / label.getHeight() * serverScreenDim.height);
        sendEvent(dos, type, remoteX, remoteY);
    }

    private static void sendEvent(DataOutputStream dos, byte type, int p1, int p2) {
        try {
            if (eSocket != null && !eSocket.isClosed()) {
                dos.writeByte(type);
                dos.writeInt(p1);
                dos.writeInt(p2);
                dos.flush();
            }
        } catch (IOException ignored) {}
    }
}
