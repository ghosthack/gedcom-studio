package ged.desktop;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/// The line at the bottom of the window: whether the daemon is there, and
/// what the current file holds.
final class StatusBar extends JPanel {

    private final JLabel connection = Ui.label("");
    private final JLabel file = Ui.label("");
    private Color dot = Color.GRAY;

    StatusBar() {
        super(new BorderLayout(12, 0));
        connection.setIcon(new Dot());
        connection.setIconTextGap(7);
        connection.setFont(Ui.font(-1.5f, Font.PLAIN));
        file.setFont(Ui.font(-1.5f, Font.PLAIN));
        add(connection, BorderLayout.WEST);
        add(file, BorderLayout.EAST);
        applyColors();
    }

    void applyColors() {
        setBackground(Ui.chromeBackground());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.hairline()),
                BorderFactory.createEmptyBorder(4, 12, 5, 12)));
        connection.setForeground(Ui.muted());
        file.setForeground(Ui.muted());
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (connection != null) applyColors();
    }

    void connection(Connection.Status s) {
        dot = switch (s.state()) {
            case CONNECTED -> Ui.success();
            case RECONNECTING -> Ui.warning();
            case LOST -> Ui.danger();
        };
        connection.setText(s.text());
        connection.setToolTipText(s.text());
        repaint();
    }

    void file(Model.FileInfo info) {
        if (info == null) {
            file.setText("");
            return;
        }
        var parts = new java.util.ArrayList<String>();
        parts.add(info.people() + " people");
        parts.add(info.families() + " families");
        if (info.sources() > 0) parts.add(info.sources() == 1 ? "1 source" : info.sources() + " sources");
        parts.add("GEDCOM " + info.gedcom() + ", " + info.encoding());
        parts.add("revision " + info.revision());
        if (info.changedOnDisk()) parts.add("changed on disk");
        file.setText(String.join("  ·  ", parts));
        file.setToolTipText(info.path());
    }

    private final class Dot implements Icon {
        @Override public int getIconWidth() { return 8; }
        @Override public int getIconHeight() { return 8; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(dot);
            g2.fillOval(x, y, 8, 8);
            g2.dispose();
        }
    }
}
