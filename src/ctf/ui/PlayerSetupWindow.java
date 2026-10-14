package ctf.ui;

import ctf.network.GameClient;
import ctf.network.GameServer;
import ctf.network.NetUtil;
import javax.swing.*;
import java.awt.*;

public class PlayerSetupWindow extends JFrame {
    private static final int PORT = GameServer.PORT;
    private JTextField nameField;
    private JTextField hostField;

    public PlayerSetupWindow() {
        setTitle("CTF - Player Setup");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(560, 420);
        setMinimumSize(new Dimension(420, 360));
        setResizable(true);
        setLocationRelativeTo(null);

        BackgroundPanel background = new BackgroundPanel();
        background.setLayout(new GridBagLayout());

        JPanel card = new JPanel();
        card.setBackground(new Color(10, 15, 25, 225));
        card.setBorder(BorderFactory.createEmptyBorder(22, 30, 22, 30));
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("PLAYER SETUP");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI Black", Font.BOLD, 28));

        nameField = new JTextField();
        nameField.setMaximumSize(new Dimension(520, 36));
        nameField.setAlignmentX(Component.CENTER_ALIGNMENT);

        hostField = new JTextField();
        hostField.setMaximumSize(new Dimension(520, 36));
        hostField.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel nameLabel = label("Player Name");
        JLabel hostLabel = label("Host IP (for Join)");

        JButton host = button("HOST GAME");
        JButton join = button("JOIN GAME");
        JButton back = button("BACK");

        host.addActionListener(e -> hostGame());
        join.addActionListener(e -> joinGame());
        back.addActionListener(e -> {
            new GameWindow().setVisible(true);
            dispose();
        });

        card.add(title);
        card.add(Box.createVerticalStrut(18));
        card.add(nameLabel);
        card.add(nameField);
        card.add(Box.createVerticalStrut(10));
        card.add(hostLabel);
        card.add(hostField);
        card.add(Box.createVerticalStrut(16));
        card.add(host);
        card.add(Box.createVerticalStrut(8));
        card.add(join);
        card.add(Box.createVerticalStrut(8));
        card.add(back);

        background.add(card);
        setContentPane(background);
        ResponsiveUi.install(this, background, 560, 420);
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.WHITE);
        l.setAlignmentX(Component.CENTER_ALIGNMENT);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        return l;
    }

    private JButton button(String text) {
        JButton b = new JButton(text);
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        b.setFont(new Font("Segoe UI", Font.BOLD, 14));
        b.setFocusPainted(false);
        b.setMaximumSize(new Dimension(360, 40));
        return b;
    }

    private String readName() {
        String name = nameField.getText().trim();

        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter a player name.");
            return null;
        }

        return name.replace(' ', '_');
    }

    private void hostGame() {
        String name = readName();
        if (name == null) return;

        GameServer server = new GameServer(PORT);

        try {
            server.start();

            GameClient client = new GameClient("127.0.0.1", PORT, name);
            client.connect();

            LobbyWindow lobby = new LobbyWindow(
                    client, server, true, NetUtil.getLanIPv4()
            );
            lobby.setVisible(true);
            dispose();

        } catch (Exception ex) {
            server.stop();
            JOptionPane.showMessageDialog(
                    this,
                    "Could not start the game server:\n" + ex.getMessage(),
                    "Server Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void joinGame() {
        String name = readName();
        if (name == null) return;

        String host = hostField.getText().trim();

        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Enter the host PC's IPv4 address."
            );
            return;
        }

        try {
            GameClient client = new GameClient(host, PORT, name);
            client.connect();

            LobbyWindow lobby = new LobbyWindow(
                    client, null, false, host
            );
            lobby.setVisible(true);
            dispose();

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Could not connect to host:\n" + ex.getMessage()
                            + "\n\nCheck the IP address and Windows Firewall.",
                    "Connection Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private static class BackgroundPanel extends JPanel {
        private final Image bg;

        BackgroundPanel() {
            bg = new ImageIcon(
                    getClass().getResource("/ctf/ui/ctf_map.jpeg")
            ).getImage();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.drawImage(bg, 0, 0, getWidth(), getHeight(), this);
            g2.setColor(new Color(0, 0, 0, 100));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }
}
