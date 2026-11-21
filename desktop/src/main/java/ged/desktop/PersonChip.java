package ged.desktop;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Function;

/// A person on a page: avatar, name and lifespan (or their role), clickable to
/// go to them, with a right-click menu (open, ancestors, unlink…).
final class PersonChip extends JPanel {

    private boolean hover;

    PersonChip(Actions actions, Model.PersonSummary p, String note, Model.Person context, Model.Relative relative) {
        this(actions, p, note, context, relative, 36);
    }

    PersonChip(Actions actions, Model.PersonSummary p, String note, Model.Person context, Model.Relative relative, int avatar) {
        super(new BorderLayout(10, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        var face = new JLabel(new Avatar(actions.images(), p, avatar));
        add(face, BorderLayout.WEST);
        var lines = Ui.stack(1);
        var name = Ui.label(p.displayName());
        name.setFont(Ui.font(0, Font.BOLD));
        lines.add(name);
        var sub = new StringBuilder();
        if (note != null && !note.isBlank()) sub.append(note);
        if (!p.lifespan().isBlank()) {
            if (!sub.isEmpty()) sub.append("  ·  ");
            sub.append(p.lifespan());
        }
        if (!sub.isEmpty()) lines.add(Ui.small(sub.toString()));
        var center = Ui.transparent(new java.awt.GridBagLayout());
        center.add(lines, new java.awt.GridBagConstraints(0, 0, 1, 1, 1, 1, java.awt.GridBagConstraints.WEST,
                java.awt.GridBagConstraints.HORIZONTAL, new java.awt.Insets(0, 0, 0, 0), 0, 0));
        add(center, BorderLayout.CENTER);
        setToolTipText(p.displayName() + " (" + p.key() + ")");
        var mouse = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) actions.personMenu(PersonChip.this, e.getX(), e.getY(), p, context, relative);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    actions.personMenu(PersonChip.this, e.getX(), e.getY(), p, context, relative);
                } else if (e.getButton() == MouseEvent.BUTTON1 && contains(e.getPoint())) {
                    actions.openPerson(p.key());
                }
            }
        };
        addMouseListener(mouse);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (hover) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Ui.cardHover());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.dispose();
        }
    }

    /// People in a grid of `columns`, each with an optional note.
    static JComponent grid(Actions actions, List<Model.PersonSummary> people, int columns,
                           Function<Model.PersonSummary, String> note, Model.Person context,
                           Function<Model.PersonSummary, Model.Relative> relative) {
        var p = new JPanel(new GridLayout(0, columns, 4, 2));
        p.setOpaque(false);
        for (var s : people) p.add(new PersonChip(actions, s, note == null ? null : note.apply(s), context,
                relative == null ? null : relative.apply(s)));
        return p;
    }

    /// A dashed "add" placeholder where a relative is missing ("Add father").
    static JComponent placeholder(String text, Runnable action) {
        var l = new JLabel(text, Icons.plus(14), JLabel.LEFT) {
            boolean hover;

            {
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = false;
                        repaint();
                    }

                    @Override
                    public void mouseClicked(MouseEvent e) {
                        action.run();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hover ? Ui.cardHover() : Ui.alpha(Ui.cardHover(), 0));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.setColor(Ui.hairline().darker());
                g2.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND,
                        1f, new float[] {4f, 4f}, 0f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
                setForeground(Ui.muted());
                super.paintComponent(g);
            }
        };
        l.setIconTextGap(8);
        l.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return l;
    }
}
