package ctf.ui;

import javax.swing.*;
import java.awt.*;

public class GameWindow extends JFrame {
    public GameWindow() {
        setTitle("Capture The Flag");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 720);
        setMinimumSize(new Dimension(640, 480));
        setResizable(true);
        setLocationRelativeTo(null);

        BackgroundPanel panel = new BackgroundPanel();
        panel.setLayout(new GridBagLayout());

        JPanel card = new JPanel();
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("CAPTURE THE FLAG");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI Black", Font.BOLD, 42));

        JLabel subtitle = new JLabel("LAN MULTIPLAYER");
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setForeground(new Color(230, 230, 230));
        subtitle.setFont(new Font("Segoe UI", Font.BOLD, 16));

        JButton howToPlay = new JButton("HOW TO PLAY");
        howToPlay.setAlignmentX(Component.CENTER_ALIGNMENT);
        howToPlay.setFont(new Font("Segoe UI", Font.BOLD, 16));
        howToPlay.setFocusPainted(false);
        howToPlay.setMaximumSize(new Dimension(360, 48));
        howToPlay.addActionListener(e -> new HowToPlayWindow().setVisible(true));

        JButton start = new JButton("START GAME");
        start.setAlignmentX(Component.CENTER_ALIGNMENT);
        start.setFont(new Font("Segoe UI", Font.BOLD, 18));
        start.setFocusPainted(false);
        start.setPreferredSize(new Dimension(180, 45));
        start.setMaximumSize(new Dimension(360, 52));
        start.addActionListener(e -> {
            new PlayerSetupWindow().setVisible(true);
            dispose();
        });

        card.add(title);
        card.add(Box.createVerticalStrut(8));
        card.add(subtitle);
        card.add(Box.createVerticalStrut(28));
        card.add(start);
        card.add(Box.createVerticalStrut(10));
        card.add(howToPlay);

        panel.add(card);
        setContentPane(panel);
        ResponsiveUi.install(this, panel, 1000, 720);
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
            g2.setColor(new Color(0, 0, 0, 90));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GameWindow().setVisible(true));
    }
}
