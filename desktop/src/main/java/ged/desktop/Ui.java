package ged.desktop;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/// The app's visual vocabulary: colours derived from the current FlatLaf theme
/// (read at paint time, so a theme switch needs no bookkeeping), a type scale,
/// and the few building blocks pages are made of.
final class Ui {

    // ---- spacing ----------------------------------------------------------------

    static final int GAP = 8;
    static final int PAGE_PAD = 28;

    // ---- colours ----------------------------------------------------------------

    static boolean dark() {
        var bg = UIManager.getColor("Panel.background");
        return bg != null && luminance(bg) < 0.5;
    }

    static double luminance(Color c) {
        return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255.0;
    }

    static Color mix(Color a, Color b, double t) {
        return new Color(
                (int) Math.round(a.getRed() * (1 - t) + b.getRed() * t),
                (int) Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                (int) Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    static Color alpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    static Color foreground() {
        return UIManager.getColor("Label.foreground");
    }

    /// Secondary text: dates, places, keys.
    static Color muted() {
        return mix(pageBackground(), foreground(), dark() ? 0.58 : 0.52);
    }

    /// The main area's background (a document surface).
    static Color pageBackground() {
        return dark() ? new Color(0x1F1F21) : Color.WHITE;
    }

    /// The sidebar and toolbar: the window's own grey.
    static Color chromeBackground() {
        return UIManager.getColor("Panel.background");
    }

    static Color card() {
        return dark() ? new Color(0x2A2A2D) : new Color(0xF6F6F8);
    }

    static Color cardHover() {
        return dark() ? new Color(0x343438) : new Color(0xEDEDF1);
    }

    static Color hairline() {
        return dark() ? new Color(0x3A3A3E) : new Color(0xE2E2E6);
    }

    static Color accent() {
        var c = UIManager.getColor("Component.accentColor");
        if (c == null) c = UIManager.getColor("Component.focusColor");
        return c != null ? new Color(c.getRGB()) : new Color(0x007AFF);
    }

    static Color link() {
        return dark() ? new Color(0x5AA9FF) : new Color(0x0868D8);
    }

    static Color danger() {
        return dark() ? new Color(0xFF6B61) : new Color(0xD93025);
    }

    static Color warning() {
        return dark() ? new Color(0xFFB340) : new Color(0xB86E00);
    }

    static Color success() {
        return dark() ? new Color(0x4CD36B) : new Color(0x1E9E46);
    }

    /// A soft tint per sex for chart boxes and avatars: blue, rose, grey.
    static Color sexColor(String sex) {
        return switch (sex == null ? "" : sex) {
            case "M" -> dark() ? new Color(0x5B9BD5) : new Color(0x4A8BD0);
            case "F" -> dark() ? new Color(0xE07A9A) : new Color(0xD9668A);
            default -> dark() ? new Color(0x8E8E93) : new Color(0x9A9AA0);
        };
    }

    /// A pleasant, stable colour for a person's initials.
    static Color avatarColor(String seed) {
        Color[] palette = {
                new Color(0x5B8DEF), new Color(0xE0795F), new Color(0x4FAE8B), new Color(0xB07CD8),
                new Color(0xE2A23B), new Color(0x4FA6C2), new Color(0xD8679A), new Color(0x7A8C5B),
        };
        int h = seed == null ? 0 : seed.hashCode();
        return palette[Math.floorMod(h, palette.length)];
    }

    // ---- type -------------------------------------------------------------------

    static Font base() {
        var f = UIManager.getFont("Label.font");
        return f != null ? f : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    static Font font(float delta, int style) {
        var b = base();
        return b.deriveFont(style, b.getSize2D() + delta);
    }

    static JLabel label(String text) {
        var l = new JLabel(text);
        l.putClientProperty("html.disable", Boolean.TRUE);
        return l;
    }

    static JLabel title(String text) {
        var l = label(text);
        l.setFont(font(13, Font.BOLD));
        return l;
    }

    static JLabel heading(String text) {
        var l = label(text);
        l.setFont(font(2, Font.BOLD));
        return l;
    }

    /// The small caps-like label above a card ("PARENTS").
    static JLabel overline(String text) {
        var l = label(text.toUpperCase(java.util.Locale.ROOT));
        l.setFont(font(-2, Font.BOLD));
        l.setForeground(muted());
        return l;
    }

    static JLabel mutedLabel(String text) {
        var l = label(text);
        l.setForeground(muted());
        return l;
    }

    static JLabel small(String text) {
        var l = label(text);
        l.setFont(font(-1.5f, Font.PLAIN));
        l.setForeground(muted());
        return l;
    }

    /// Wrapping, selectable text for notes: looks like a label.
    static JTextArea paragraph(String text) {
        var t = new JTextArea(text);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setEditable(false);
        t.setOpaque(false);
        t.setBorder(null);
        t.setFont(base());
        t.setForeground(foreground());
        t.setFocusable(true);
        return t;
    }

    // ---- links and buttons ----------------------------------------------------------

    /// A text link: accent coloured, underlined on hover.
    static JLabel link(String text, Runnable action) {
        return new LinkLabel(text, action);
    }

    static final class LinkLabel extends JLabel {
        private boolean hover;

        LinkLabel(String text, Runnable action) {
            super(text);
            putClientProperty("html.disable", Boolean.TRUE);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) action.run();
                }

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
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            setForeground(link());
            super.paintComponent(g);
            if (hover) {
                var fm = g.getFontMetrics();
                var ins = getInsets();
                int textX = ins.left + (getIcon() == null ? 0 : getIcon().getIconWidth() + getIconTextGap());
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2 + 2;
                g.setColor(link());
                g.drawLine(textX, y, textX + fm.stringWidth(getText()), y);
            }
        }
    }

    /// A transparent toolbar-style button (FlatLaf's `toolBarButton` type).
    static JButton toolButton(Icon icon, String tooltip, Runnable action) {
        var b = new JButton(icon);
        styleToolButton(b, tooltip);
        b.addActionListener(e -> action.run());
        return b;
    }

    static void styleToolButton(AbstractButton b, String tooltip) {
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.setFocusable(false);
        b.setToolTipText(tooltip);
        b.setMargin(new Insets(4, 6, 4, 6));
        b.setIconTextGap(5);
    }

    /// A normal push button, optionally with an icon.
    static JButton button(String text, Icon icon, Runnable action) {
        var b = new JButton(text, icon);
        b.setIconTextGap(6);
        b.addActionListener(e -> action.run());
        return b;
    }

    // ---- containers -------------------------------------------------------------------

    /// A vertical stack of components, each as wide as the stack.
    static JPanel stack(int gap) {
        var p = new JPanel(new StackLayout(gap));
        p.setOpaque(false);
        return p;
    }

    static JPanel row(int gap, Component... cs) {
        var p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        for (int i = 0; i < cs.length; i++) {
            if (i > 0 && gap > 0) p.add(Box.createHorizontalStrut(gap));
            p.add(cs[i]);
        }
        return p;
    }

    static JPanel hbox(Component... cs) {
        var p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setOpaque(false);
        for (var c : cs) p.add(c);
        return p;
    }

    static JPanel transparent(LayoutManager lm) {
        var p = new JPanel(lm);
        p.setOpaque(false);
        return p;
    }

    /// A rounded surface a little darker (or lighter) than the page.
    static class Card extends JPanel {
        private final int arc;

        Card(LayoutManager layout) {
            this(layout, 14);
        }

        Card(LayoutManager layout, int arc) {
            super(layout);
            this.arc = arc;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        }

        Color fill() {
            return card();
        }

        @Override
        protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill());
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
            g2.setColor(hairline());
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, arc, arc));
            g2.dispose();
        }
    }

    /// A card with a small heading over its content.
    static JPanel section(String heading, Icon icon, JComponent content) {
        var card = new Card(new BorderLayout(0, 10));
        var h = overline(heading);
        if (icon != null) {
            h.setIcon(Icons.tinted(icon, muted()));
            h.setIconTextGap(6);
        }
        card.add(h, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    /// A pill-shaped tag ("aka Mary Ross").
    static JLabel chip(String text) {
        var l = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(card());
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.setColor(hairline());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.putClientProperty("html.disable", Boolean.TRUE);
        l.setFont(font(-1, Font.PLAIN));
        l.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        return l;
    }

    /// A message across the top of a form or page: info, warning or error.
    static final class Banner extends JPanel {
        enum Kind { INFO, WARNING, ERROR }

        private final JTextArea text = paragraph("");
        private final JLabel icon = new JLabel();
        private Kind kind = Kind.INFO;

        Banner() {
            super(new BorderLayout(10, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            icon.setVerticalAlignment(SwingConstants.TOP);
            add(icon, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            setVisible(false);
        }

        void show(Kind kind, String message) {
            this.kind = kind;
            text.setText(message);
            var c = color();
            icon.setIcon(Icons.tinted(kind == Kind.INFO ? Icons.info(16) : Icons.warning(16), c));
            setVisible(true);
            revalidate();
            repaint();
        }

        void hideBanner() {
            setVisible(false);
            revalidate();
        }

        private Color color() {
            return switch (kind) {
                case INFO -> accent();
                case WARNING -> warning();
                case ERROR -> danger();
            };
        }

        @Override
        protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var c = color();
            g2.setColor(alpha(c, dark() ? 40 : 26));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.setColor(alpha(c, 110));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.dispose();
        }
    }

    // ---- layouts ----------------------------------------------------------------------

    /// Children stacked top to bottom, each stretched to the container's width.
    static final class StackLayout implements LayoutManager {
        private final int gap;

        StackLayout(int gap) {
            this.gap = gap;
        }

        @Override public void addLayoutComponent(String name, Component comp) { }
        @Override public void removeLayoutComponent(Component comp) { }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            var ins = parent.getInsets();
            int w = 0, h = 0, n = 0;
            int width = parent.getWidth() - ins.left - ins.right;
            for (var c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                var p = c.getPreferredSize();
                w = Math.max(w, p.width);
                h += heightFor(c, width > 0 ? width : p.width) + (n++ > 0 ? gap : 0);
            }
            return new Dimension(w + ins.left + ins.right, h + ins.top + ins.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            var ins = parent.getInsets();
            int width = parent.getWidth() - ins.left - ins.right;
            int y = ins.top;
            for (var c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                int h = heightFor(c, width);
                c.setBounds(ins.left, y, width, h);
                y += h + gap;
            }
        }
    }

    /// The height a component wants at a given width: text that wraps needs
    /// to be asked with the width set first.
    static int heightFor(Component c, int width) {
        if (c instanceof JTextArea || c instanceof Container && containsWrapping((Container) c)) {
            var old = c.getSize();
            c.setSize(width, Short.MAX_VALUE);
            if (c instanceof Container ct) ct.doLayout();
            int h = c.getPreferredSize().height;
            c.setSize(old);
            return h;
        }
        return c.getPreferredSize().height;
    }

    private static boolean containsWrapping(Container c) {
        for (var k : c.getComponents()) {
            if (k instanceof JTextArea) return true;
            if (k instanceof Container ct && containsWrapping(ct)) return true;
        }
        return false;
    }

    /// Two columns side by side when there is room, else one under the other.
    static final class Columns extends JPanel implements LayoutManager {
        private final JComponent left, right;
        private final int gap, breakpoint;
        private final double leftShare;

        Columns(JComponent left, JComponent right, double leftShare, int gap, int breakpoint) {
            this.left = left;
            this.right = right;
            this.leftShare = leftShare;
            this.gap = gap;
            this.breakpoint = breakpoint;
            setLayout(this);
            setOpaque(false);
            add(left);
            add(right);
        }

        private boolean wide(int width) {
            return width >= breakpoint;
        }

        @Override public void addLayoutComponent(String name, Component comp) { }
        @Override public void removeLayoutComponent(Component comp) { }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            int width = getWidth() > 0 ? getWidth() : breakpoint;
            if (wide(width)) {
                int lw = (int) ((width - gap) * leftShare);
                return new Dimension(width, Math.max(heightFor(left, lw), heightFor(right, width - gap - lw)));
            }
            return new Dimension(width, heightFor(left, width) + gap + heightFor(right, width));
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(0, 0);
        }

        @Override
        public void layoutContainer(Container parent) {
            int width = getWidth();
            if (wide(width)) {
                int lw = (int) ((width - gap) * leftShare);
                left.setBounds(0, 0, lw, heightFor(left, lw));
                right.setBounds(lw + gap, 0, width - gap - lw, heightFor(right, width - gap - lw));
            } else {
                int lh = heightFor(left, width);
                left.setBounds(0, 0, width, lh);
                right.setBounds(0, lh + gap, width, heightFor(right, width));
            }
        }
    }

    /// A page's scrollable content: as wide as the viewport, centred and capped
    /// at a comfortable reading width.
    static final class Page extends JPanel implements Scrollable {
        private final int maxWidth;
        private final JComponent content;

        Page(JComponent content, int maxWidth) {
            super(null);
            this.content = content;
            this.maxWidth = maxWidth;
            setBackground(pageBackground());
            setBorder(BorderFactory.createEmptyBorder(PAGE_PAD - 4, PAGE_PAD, PAGE_PAD + 8, PAGE_PAD));
            add(content);
        }

        private int contentWidth() {
            var ins = getInsets();
            return Math.max(0, Math.min(maxWidth, getWidth() - ins.left - ins.right));
        }

        @Override
        public void doLayout() {
            var ins = getInsets();
            int w = contentWidth();
            int x = ins.left + Math.max(0, (getWidth() - ins.left - ins.right - w) / 2);
            content.setBounds(x, ins.top, w, heightFor(content, w));
        }

        @Override
        public Dimension getPreferredSize() {
            var ins = getInsets();
            int w = getWidth() > 0 ? contentWidth() : maxWidth;
            return new Dimension(w + ins.left + ins.right, heightFor(content, w) + ins.top + ins.bottom);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(24, r.height - 48); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
    }

    static Color withFallback(String key, Color fallback) {
        var c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private Ui() {
    }
}
