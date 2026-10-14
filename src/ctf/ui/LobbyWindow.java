package ctf.ui;

import ctf.network.GameClient;
import ctf.network.GameServer;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class LobbyWindow extends JFrame {
    private final GameClient client;
    private final GameServer server;
    private final boolean hosting;

    private final DefaultListModel<String> redModel = new DefaultListModel<>();
    private final DefaultListModel<String> blueModel = new DefaultListModel<>();
    private final JTextArea chatArea = new JTextArea();
    private final JTextField chatField = new JTextField();
    private final JButton readyButton = new JButton("READY");
    private final JButton redButton = new JButton("JOIN RED");
    private final JButton blueButton = new JButton("JOIN BLUE");
    private final JButton startButton = new JButton("START MATCH");
    private final JLabel statusLabel = new JLabel("Waiting for players...");
    private final JLabel ipLabel = new JLabel();

    private boolean switchingToGame = false;
    private boolean disconnectHandled = false;
    private int lastChatCount = 0;

    public LobbyWindow(GameClient client, GameServer server, boolean hosting, String hostIp) {
        this.client = client;
        this.server = server;
        this.hosting = hosting;

        setTitle("CTF - Lobby");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1000, 720);
        setMinimumSize(new Dimension(700, 520));
        setResizable(true);
        setLocationRelativeTo(null);

        BackgroundPanel background = new BackgroundPanel();
        background.setLayout(new BorderLayout(15, 15));
        background.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel top = new JPanel(new BorderLayout(10, 5));
        top.setOpaque(false);

        JLabel title = new JLabel("CAPTURE THE FLAG — LOBBY");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI Black", Font.BOLD, 28));

        ipLabel.setForeground(Color.WHITE);
        ipLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        ipLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        ipLabel.setText(hosting
                ? "LOBBY IP: " + hostIp + "   |   PORT: " + GameServer.PORT
                : "HOST: " + hostIp + "   |   PORT: " + GameServer.PORT);

        top.add(title, BorderLayout.WEST);
        top.add(ipLabel, BorderLayout.EAST);

        JPanel center = new JPanel(new GridLayout(1, 2, 15, 0));
        center.setOpaque(false);
        center.add(teamPanel("RED TEAM", new Color(220, 65, 65), redModel));
        center.add(teamPanel("BLUE TEAM", new Color(75, 105, 235), blueModel));

        JPanel bottom = new JPanel(new BorderLayout(12, 10));
        bottom.setOpaque(false);
        bottom.add(chatPanel(), BorderLayout.CENTER);
        bottom.add(controlPanel(), BorderLayout.EAST);

        background.add(top, BorderLayout.NORTH);
        background.add(center, BorderLayout.CENTER);
        background.add(bottom, BorderLayout.SOUTH);
        setContentPane(background);
        ResponsiveUi.install(this, background, 1000, 720);

        Timer timer = new Timer(150, e -> refreshLobby());
        timer.start();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                timer.stop();
                if (!switchingToGame) {
                    client.closeConnection();
                    if (hosting && server != null) server.stop();
                }
            }
        });
    }

    private JPanel teamPanel(String title, Color titleColor, DefaultListModel<String> model) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(new Color(5, 12, 25, 215));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(titleColor, 2),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));

        JLabel label = new JLabel(title);
        label.setForeground(titleColor);
        label.setFont(new Font("Segoe UI Black", Font.BOLD, 22));

        JList<String> list = new JList<>(model);
        list.setOpaque(false);
        list.setForeground(Color.WHITE);
        list.setFont(new Font("Segoe UI", Font.BOLD, 15));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(
                        l, value, index, isSelected, cellHasFocus);
                c.setOpaque(false);
                c.setForeground(Color.WHITE);
                c.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
                return c;
            }
        });

        panel.add(label, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        return panel;
    }

    private JPanel chatPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setOpaque(false);

        JLabel label = new JLabel("LOBBY CHAT");
        label.setForeground(Color.WHITE);
        label.setFont(new Font("Segoe UI Black", Font.BOLD, 15));

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        // White chat surface with black text for strong contrast.
        chatArea.setBackground(Color.WHITE);
        chatArea.setForeground(Color.BLACK);
        chatArea.setCaretColor(Color.BLACK);
        chatArea.setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));

        chatField.setPreferredSize(new Dimension(0, 32));
        chatField.setBackground(Color.WHITE);
        chatField.setForeground(Color.BLACK);
        chatField.setCaretColor(Color.BLACK);
        chatField.addActionListener(e -> sendChat());

        panel.add(label, BorderLayout.NORTH);
        panel.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        panel.add(chatField, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(540, 175));
        return panel;
    }

    private JPanel controlPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(240, 175));

        statusLabel.setForeground(Color.WHITE);
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        styleButton(redButton);
        styleButton(blueButton);
        styleButton(readyButton);
        styleButton(startButton);

        redButton.addActionListener(e -> client.chooseTeam("RED"));
        blueButton.addActionListener(e -> client.chooseTeam("BLUE"));
        readyButton.addActionListener(e -> toggleReady());
        startButton.addActionListener(e -> client.startMatch());

        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(8));
        panel.add(redButton);
        panel.add(Box.createVerticalStrut(6));
        panel.add(blueButton);
        panel.add(Box.createVerticalStrut(6));
        panel.add(readyButton);
        panel.add(Box.createVerticalStrut(6));
        panel.add(startButton);

        return panel;
    }

    private void styleButton(JButton button) {
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.setFocusPainted(false);
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        button.setMaximumSize(new Dimension(420, 36));
    }

    private void toggleReady() {
        GameClient.Player me = findMe();
        if (me == null) return;
        client.setReady(!me.ready);
    }

    private void sendChat() {
        String message = chatField.getText().trim();
        if (!message.isEmpty()) {
            client.sendChat(message);
            chatField.setText("");
        }
    }

    private GameClient.Player findMe() {
        for (GameClient.Player p : client.getPlayersSnapshot()) {
            if (p.id == client.getMyId()) return p;
        }
        return null;
    }

    private void refreshLobby() {
        if ("DISCONNECTED".equals(client.getPhase()) && !disconnectHandled) {
            disconnectHandled = true;
            JOptionPane.showMessageDialog(this,
                    "The connection to the game server was lost. Returning to the main menu.",
                    "Disconnected", JOptionPane.WARNING_MESSAGE);
            client.closeConnection();
            if (hosting && server != null) server.stop();
            dispose();
            SwingUtilities.invokeLater(() -> new GameWindow().setVisible(true));
            return;
        }
        if ("PLAYING".equals(client.getPhase()) && !switchingToGame) {
            switchingToGame = true;
            dispose();
            GameClient.GameFrame frame = new GameClient.GameFrame(client, hosting ? server : null);
            frame.setVisible(true);
            return;
        }

        List<GameClient.Player> players = client.getPlayersSnapshot();
        redModel.clear();
        blueModel.clear();

        boolean everyoneReady = !players.isEmpty();
        for (GameClient.Player p : players) {
            String suffix = p.ready ? "  ✓ READY" : "  • NOT READY";
            String hostMark = p.host ? "  ★ HOST" : "";
            String entry = p.name + suffix + hostMark;

            if ("RED".equals(p.team)) redModel.addElement(entry);
            else blueModel.addElement(entry);

            if (!p.ready) everyoneReady = false;
        }

        GameClient.Player me = findMe();
        if (me != null) {
            readyButton.setText(me.ready ? "UNREADY" : "READY");
            redButton.setEnabled(!me.ready && !"RED".equals(me.team));
            blueButton.setEnabled(!me.ready && !"BLUE".equals(me.team));
        }

        boolean host = client.isHost();
        boolean enoughPlayers = players.size() >= 2;
        boolean bothTeams = !redModel.isEmpty() && !blueModel.isEmpty();
        startButton.setVisible(host);
        startButton.setEnabled(host && enoughPlayers && bothTeams && everyoneReady);

        if (players.size() < 2) {
            statusLabel.setText("Waiting for another player...");
        } else if (!bothTeams) {
            statusLabel.setText("Both teams need at least one player.");
        } else if (!everyoneReady) {
            statusLabel.setText("Waiting for everyone to be READY...");
        } else if (host) {
            statusLabel.setText("Everyone ready — you can start!");
        } else {
            statusLabel.setText("Everyone ready — waiting for host.");
        }

        List<String> chats = client.getChatSnapshot();
        if (chats.size() != lastChatCount) {
            chatArea.setText(String.join("\n", chats));
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
            lastChatCount = chats.size();
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
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }
}
