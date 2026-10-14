package ctf.ui;

import javax.swing.*;
import java.awt.*;

/** A simple, resizable in-game guide explaining the current controls and objective. */
public class HowToPlayWindow extends JFrame {
    public HowToPlayWindow() {
        setTitle("Capture The Flag - How to Play");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(780, 680);
        setMinimumSize(new Dimension(560, 500));
        setResizable(true);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(new Color(14, 22, 35));
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JLabel title = new JLabel("HOW TO PLAY", SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI", Font.BOLD, 30));
        root.add(title, BorderLayout.NORTH);

        String html = """
            <html><body style='font-family:Segoe UI; font-size:13px; color:#172033;'>
            <h2 style='color:#b52835;'>OBJECTIVE</h2>
            Work with your team to steal the opposing team's flag and return it to your own base.
            Protect your flag, coordinate with teammates, and score captures to win.
            <h2 style='color:#b52835;'>MOVEMENT</h2>
            <table cellpadding='5' cellspacing='0' border='0'>
              <tr><td><b>A / Left Arrow</b></td><td>Move left</td></tr>
              <tr><td><b>D / Right Arrow</b></td><td>Move right</td></tr>
              <tr><td><b>Space</b></td><td>Jump</td></tr>
              <tr><td><b>W / Up Arrow (hold)</b></td><td>Use the flight booster. Energy drains while flying and recharges only while grounded.</td></tr>
              <tr><td><b>Mouse movement</b></td><td>Aim weapons</td></tr>
            </table>
            <h2 style='color:#b52835;'>COMBAT</h2>
            <table cellpadding='5' cellspacing='0' border='0'>
              <tr><td><b>Z</b></td><td>Punch with your fist in the aimed direction</td></tr>
              <tr><td><b>X</b></td><td>Fire the selected ranged weapon, or swing the energy blade</td></tr>
              <tr><td><b>1</b></td><td>Select pistol</td></tr>
              <tr><td><b>2</b></td><td>Select shotgun</td></tr>
              <tr><td><b>3</b></td><td>Select energy blade</td></tr>
              <tr><td><b>R</b></td><td>Reload pistol or shotgun</td></tr>
            </table>
            <h2 style='color:#b52835;'>WEAPONS</h2>
            <b>Fists:</b> unlimited melee attack. <b>Pistol:</b> accurate single projectiles.
            <b>Shotgun:</b> fires a spread of pellets, strongest at close range.
            <b>Energy blade:</b> close-range melee weapon with extended reach.
            Each player has 100 health. At zero health, you respawn after a short delay.
            Attacking and eliminating opponents is handled by the game server.
            <h2 style='color:#b52835;'>MULTIPLAYER</h2>
            Host a LAN lobby or enter the host's IPv4 address to join. Choose a team,
            chat with other players, and press <b>READY</b>. The host can start once both
            teams are represented and everyone is ready.
            <p><i>Tip: stay on the platforms, watch your flight energy, and help teammates carrying the flag.</i></p>
            </body></html>
            """;
        JEditorPane content = new JEditorPane("text/html", html);
        content.setEditable(false);
        content.setBackground(new Color(245, 247, 250));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(80, 115, 155), 1));
        root.add(scroll, BorderLayout.CENTER);

        JButton close = new JButton("BACK TO MAIN MENU");
        close.setFont(new Font("Segoe UI", Font.BOLD, 14));
        close.setFocusPainted(false);
        close.addActionListener(e -> dispose());
        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.add(close);
        root.add(bottom, BorderLayout.SOUTH);

        setContentPane(root);
        ResponsiveUi.install(this, root, 780, 680);
    }
}
