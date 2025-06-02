package com.chromascape.utils.input;

import javax.swing.*;
import java.awt.*;

public class MouseOverlay extends JFrame {

    private Point mousePoint = new Point(100, 100);

    public MouseOverlay() {
        setUndecorated(true);
        setBackground(new Color(0, 0, 0, 0));
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        setType(Type.UTILITY); // Prevents taskbar entry

        // Transparent overlay without fullscreen lock
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setBounds(0, 0, screenSize.width, screenSize.height);

        setLayout(null);
        setVisible(true);
    }

    public void setMousePoint(Point p) {
        this.mousePoint = p;
        repaint();
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setColor(Color.RED);
        g2d.setStroke(new BasicStroke(2f));
        int size = 6;

        int x = mousePoint.x;
        int y = mousePoint.y;

        g2d.drawLine(x - size, y - size, x + size, y + size); // Top-left to bottom-right
        g2d.drawLine(x - size, y + size, x + size, y - size); // Bottom-left to top-right

        g2d.dispose();
    }
}
