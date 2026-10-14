package ctf.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.IdentityHashMap;
import java.util.Map;

/** Scales UI fonts proportionally when a resizable Swing window changes size. */
public final class ResponsiveUi {
    private ResponsiveUi() {}

    public static void install(JFrame frame, JComponent root, int baseWidth, int baseHeight) {
        Map<Component, Font> originalFonts = new IdentityHashMap<>();
        captureFonts(root, originalFonts);
        root.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                double sx = root.getWidth() / (double) baseWidth;
                double sy = root.getHeight() / (double) baseHeight;
                double scale = Math.max(0.78, Math.min(1.45, Math.min(sx, sy)));
                for (Map.Entry<Component, Font> entry : originalFonts.entrySet()) {
                    Component component = entry.getKey();
                    Font original = entry.getValue();
                    int size = Math.max(10, (int)Math.round(original.getSize2D() * scale));
                    component.setFont(original.deriveFont((float)size));
                }
                root.revalidate();
                root.repaint();
            }
        });
    }

    private static void captureFonts(Component component, Map<Component, Font> fonts) {
        if (component.getFont() != null) fonts.put(component, component.getFont());
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) captureFonts(child, fonts);
        }
    }
}
