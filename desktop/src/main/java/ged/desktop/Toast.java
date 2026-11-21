package ged.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/// A short message floating at the bottom of the main area ("Added Ana
/// Bianchi as a child of…"), with an optional action, that goes away by itself.
final class Toast extends JPanel {

    private final JLabel icon = new JLabel();
    private final javax.swing.JTextArea text = Ui.paragraph("");
    private final JButton action = new JButton();
    private final JButton close;
    private final Timer timer;
    private Runnable onAction;
    private boolean error;

    Toast() {
        super(new BorderLayout(10, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 8));
        add(icon, BorderLayout.WEST);
        text.setFocusable(false);
        add(text, BorderLayout.CENTER);
        action.putClientProperty("JButton.buttonType", "toolBarButton");
        action.setFocusable(false);
        action.addActionListener(e -> {
            setVisible(false);
            if (onAction != null) onAction.run();
        });
        close = Ui.toolButton(Icons.close(12), "Dismiss", () -> setVisible(false));
        add(Ui.row(2, action, close), BorderLayout.EAST);
        timer = new Timer(6000, e -> setVisible(false));
        timer.setRepeats(false);
        setVisible(false);
    }

    void show(String message, boolean isError, String actionText, Runnable onAction) {
        this.error = isError;
        this.onAction = onAction;
        text.setText(message);
        text.setForeground(Ui.foreground());
        icon.setIcon(Icons.tinted(isError ? Icons.warning(16) : Icons.check(16), isError ? Ui.danger() : Ui.success()));
        action.setText(actionText);
        action.setVisible(actionText != null);
        action.setForeground(Ui.link());
        setVisible(true);
        if (getParent() != null) getParent().doLayout();
        timer.setInitialDelay(isError ? 10000 : Math.min(12000, 4500 + message.length() * 30));
        timer.restart();
        repaint();
    }

    /// The size to show at, at most `maxWidth` wide.
    Dimension sizeFor(int maxWidth) {
        int w = Math.min(560, maxWidth);
        int ins = getInsets().left + getInsets().right + 26 + action.getPreferredSize().width + 40;
        text.setSize(Math.max(100, w - ins), Short.MAX_VALUE);
        int h = Math.max(text.getPreferredSize().height, 22) + getInsets().top + getInsets().bottom;
        return new Dimension(w, h);
    }

    @Override
    protected void paintComponent(Graphics g) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = 6; i > 0; i--) {
            g2.setColor(new Color(0, 0, 0, Ui.dark() ? 18 : 7));
            g2.fill(new RoundRectangle2D.Float(-i + 2, -i + 4, getWidth() + 2 * i - 4, getHeight() + 2 * i - 6, 16 + i, 16 + i));
        }
        var shape = new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
        g2.setColor(Ui.dark() ? new Color(0x323236) : Color.WHITE);
        g2.fill(shape);
        g2.setColor(error ? Ui.alpha(Ui.danger(), 140) : Ui.hairline());
        g2.draw(shape);
        g2.dispose();
    }
}
