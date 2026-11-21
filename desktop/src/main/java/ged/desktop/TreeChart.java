package ged.desktop;

import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/// A family tree: ancestors drawn left to right (the chosen person at the
/// left, father above mother, generation by generation), descendants top
/// down (the chosen person at the top, each family, then its children).
///
/// Boxes are laid out like a tidy tree: leaves one under another, each parent
/// centred on its children. Clicking a person centres the chart on them;
/// clicking the person in the centre opens their page; clicking an empty slot
/// offers to add the missing parent.
final class TreeChart extends JComponent implements Scrollable {

    /// A box: a person, a family (in descendant charts) or an empty slot.
    record Node(Model.PersonSummary person, Kind kind, String text, String owner, String relation, List<Node> next) {
        enum Kind { PERSON, FAMILY, EMPTY }

        static Node person(Model.PersonSummary p, List<Node> next) {
            return new Node(p, Kind.PERSON, null, null, null, next);
        }
    }

    interface Listener {
        void person(Model.PersonSummary p, boolean root, MouseEvent e);

        void empty(String owner, String relation);
    }

    static final int BOX_W = 212, BOX_H = 56, FAMILY_W = 190, FAMILY_H = 44, GAP_X = 40, GAP_Y = 12, GAP_ROWS = 44, GAP_COLS = 16, MARGIN = 36;

    private record Placed(Node node, Rectangle box, int depth) {
    }

    private record Edge(Rectangle from, Rectangle to) {
    }

    private final Images images;
    private final Listener listener;
    private final List<Placed> placed = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Dimension content;
    private Placed hover;

    private final boolean vertical;
    private int[] rowSize;

    /// `vertical`: the root at the top and each generation in a row below
    /// (descendants); otherwise the root at the left (ancestors).
    TreeChart(Node root, boolean vertical, Images images, Listener listener) {
        this.images = images;
        this.listener = listener;
        this.vertical = vertical;
        var sizes = new ArrayList<Integer>();
        measure(root, 0, sizes);
        rowSize = sizes.stream().mapToInt(Integer::intValue).toArray();
        var main = new int[sizes.size() + 1];
        for (int i = 0; i < sizes.size(); i++) main[i + 1] = main[i] + sizes.get(i) + (vertical ? GAP_ROWS : GAP_X);
        int[] cursor = {0};
        place(root, 0, main, cursor);
        int w = 0, h = 0;
        for (var p : placed) {
            w = Math.max(w, p.box.x + p.box.width);
            h = Math.max(h, p.box.y + p.box.height);
        }
        content = new Dimension(w, h);
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        var mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                var p = at(e.getPoint());
                if (p != hover) {
                    hover = p;
                    setCursor(Cursor.getPredefinedCursor(p == null || p.node.kind() == Node.Kind.FAMILY && p.node.person() == null
                            ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = null;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (popup(e)) return;
                if (e.getButton() != MouseEvent.BUTTON1) return;
                var p = at(e.getPoint());
                if (p == null) return;
                if (p.node.kind() == Node.Kind.EMPTY) listener.empty(p.node.owner(), p.node.relation());
                else if (p.node.person() != null) listener.person(p.node.person(), p.depth == 0, e);
            }

            private boolean popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return false;
                var p = at(e.getPoint());
                if (p != null && p.node.person() != null) listener.person(p.node.person(), p.depth == 0, e);
                return true;
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    private static int width(Node n) {
        return switch (n.kind()) {
            case FAMILY -> FAMILY_W;
            case EMPTY -> 148;
            default -> BOX_W;
        };
    }

    private static int height(Node n) {
        return switch (n.kind()) {
            case FAMILY -> FAMILY_H;
            case EMPTY -> 36;
            default -> BOX_H;
        };
    }

    private void measure(Node n, int depth, List<Integer> sizes) {
        while (sizes.size() <= depth) sizes.add(0);
        sizes.set(depth, Math.max(sizes.get(depth), vertical ? height(n) : width(n)));
        for (var c : n.next()) measure(c, depth + 1, sizes);
    }

    /// Places `n` and its subtree along the cross axis (leaves one after
    /// another, each parent centred on its children); returns the box's centre
    /// on that axis.
    private int place(Node n, int depth, int[] main, int[] cursor) {
        int w = width(n), h = height(n);
        int cross = vertical ? w : h;
        int gap = vertical ? GAP_COLS : GAP_Y;
        int c;
        int start = placed.size();
        if (n.next().isEmpty()) {
            c = cursor[0] + cross / 2;
            cursor[0] += cross + gap;
        } else {
            var centres = new ArrayList<Integer>();
            for (var k : n.next()) centres.add(place(k, depth + 1, main, cursor));
            c = (centres.getFirst() + centres.getLast()) / 2;
        }
        var box = vertical
                ? new Rectangle(c - w / 2, main[depth] + (rowSize[depth] - h) / 2, w, h)
                : new Rectangle(main[depth], c - h / 2, w, h);
        for (int i = start; i < placed.size(); i++) {
            if (placed.get(i).depth == depth + 1) edges.add(new Edge(box, placed.get(i).box));
        }
        placed.add(new Placed(n, box, depth));
        return c;
    }

    private Point offset() {
        int ox = Math.max(MARGIN, (getWidth() - content.width) / 2);
        int oy = Math.max(MARGIN, (getHeight() - content.height) / 2);
        return new Point(ox, oy);
    }

    private Placed at(Point pt) {
        var o = offset();
        for (var p : placed) {
            var r = new Rectangle(p.box);
            r.translate(o.x, o.y);
            if (r.contains(pt)) return p;
        }
        return null;
    }

    /// Where the root box is, so a scroll pane can bring it into view.
    Rectangle rootBounds() {
        var o = offset();
        for (var p : placed) {
            if (p.depth == 0) {
                var r = new Rectangle(p.box);
                r.translate(o.x, o.y);
                return r;
            }
        }
        return new Rectangle();
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        var p = at(e.getPoint());
        if (p == null) return null;
        if (p.node.kind() == Node.Kind.EMPTY) return "Add " + p.node.relation() + "…";
        if (p.node.person() == null) return null;
        var s = p.node.person();
        return s.displayName() + (s.lifespan().isBlank() ? "" : " (" + s.lifespan() + ")")
                + (p.depth == 0 ? " — click to open" : " — click to centre the chart; right-click for more");
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(content.width + 2 * MARGIN, content.height + 2 * MARGIN);
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 200; }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() != null && getParent().getWidth() > getPreferredSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }

    // ---- painting -----------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setColor(Ui.pageBackground());
        g2.fillRect(0, 0, getWidth(), getHeight());
        var o = offset();
        g2.translate(o.x, o.y);
        g2.setColor(Ui.mix(Ui.pageBackground(), Ui.foreground(), Ui.dark() ? 0.32 : 0.24));
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (var e : edges) g2.draw(vertical ? connectorDown(e.from, e.to) : connector(e.from, e.to));
        for (var p : placed) paintBox(g2, p);
        g2.dispose();
    }

    /// An elbow from the right edge of `a` to the left edge of `b`, with rounded corners.
    static Path2D connector(Rectangle a, Rectangle b) {
        double x1 = a.getMaxX(), y1 = a.getCenterY(), x2 = b.getX(), y2 = b.getCenterY();
        double mx = (x1 + x2) / 2;
        double r = Math.min(8, Math.abs(y2 - y1) / 2);
        var p = new Path2D.Double();
        p.moveTo(x1, y1);
        if (r < 0.5) {
            p.lineTo(x2, y2);
            return p;
        }
        double dir = Math.signum(y2 - y1);
        p.lineTo(mx - r, y1);
        p.quadTo(mx, y1, mx, y1 + dir * r);
        p.lineTo(mx, y2 - dir * r);
        p.quadTo(mx, y2, mx + r, y2);
        p.lineTo(x2, y2);
        return p;
    }

    /// An elbow from the bottom of `a` to the top of `b`, with rounded corners.
    static Path2D connectorDown(Rectangle a, Rectangle b) {
        double x1 = a.getCenterX(), y1 = a.getMaxY(), x2 = b.getCenterX(), y2 = b.getY();
        double my = (y1 + y2) / 2;
        double r = Math.min(8, Math.abs(x2 - x1) / 2);
        var p = new Path2D.Double();
        p.moveTo(x1, y1);
        if (r < 0.5) {
            p.lineTo(x2, y2);
            return p;
        }
        double dir = Math.signum(x2 - x1);
        p.lineTo(x1, my - r);
        p.quadTo(x1, my, x1 + dir * r, my);
        p.lineTo(x2 - dir * r, my);
        p.quadTo(x2, my, x2, my + r);
        p.lineTo(x2, y2);
        return p;
    }

    private void paintBox(Graphics2D g, Placed p) {
        var b = p.box;
        var n = p.node;
        boolean hot = p == hover;
        var shape = new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, 14, 14);
        if (n.kind() == Node.Kind.EMPTY) {
            g.setColor(hot ? Ui.cardHover() : Ui.pageBackground());
            g.fill(shape);
            g.setColor(hot ? Ui.accent() : Ui.mix(Ui.pageBackground(), Ui.foreground(), 0.25));
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {4f, 4f}, 0f));
            g.draw(new RoundRectangle2D.Float(b.x + 0.5f, b.y + 0.5f, b.width - 1, b.height - 1, 14, 14));
            g.setFont(Ui.font(-1, java.awt.Font.PLAIN));
            var fm = g.getFontMetrics();
            var t = lines(n).getFirst();
            g.setColor(hot ? Ui.accent() : Ui.muted());
            g.drawString(t, b.x + (b.width - fm.stringWidth(t)) / 2, b.y + (b.height + fm.getAscent() - fm.getDescent()) / 2);
            return;
        }
        var s = n.person();
        boolean family = n.kind() == Node.Kind.FAMILY;
        g.setColor(hot && s != null ? Ui.cardHover() : family ? Ui.pageBackground() : Ui.card());
        g.fill(shape);
        // the sex stripe on the left edge
        if (s != null && !family) {
            var clip = g.getClip();
            g.clip(shape);
            g.setColor(Ui.sexColor(s.sex()));
            g.fillRect(b.x, b.y, 4, b.height);
            g.setClip(clip);
        }
        boolean root = p.depth == 0;
        g.setColor(root ? Ui.accent() : hot && s != null ? Ui.mix(Ui.hairline(), Ui.accent(), 0.6) : Ui.hairline());
        g.setStroke(root ? new BasicStroke(2f) : family
                ? new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {3f, 3f}, 0f)
                : new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(b.x + 0.5f, b.y + 0.5f, b.width - 1, b.height - 1, 14, 14));

        int av = family ? 26 : 36;
        int ax = b.x + (family ? 10 : 14);
        int ay = b.y + (b.height - av) / 2;
        int tx = ax + av + 10;
        int tw = b.x + b.width - tx - 10;
        if (s != null) new Avatar(images, s, av).paintIcon(this, g, ax, ay);
        g.setColor(Ui.foreground());
        var nameFont = Ui.font(family ? -0.5f : 0, Font.BOLD);
        var subFont = Ui.font(-1.5f, Font.PLAIN);
        var text = lines(n);
        if (family) {
            g.setFont(subFont);
            var fm = g.getFontMetrics();
            if (s == null) {
                g.setColor(Ui.muted());
                g.drawString(ellipsize(text.getFirst(), fm, b.width - 20), b.x + 12, b.y + (b.height + fm.getAscent() - fm.getDescent()) / 2);
                return;
            }
            g.setColor(Ui.muted());
            g.drawString(ellipsize(text.get(0), fm, tw), tx, b.y + 17);
            g.setFont(nameFont);
            g.setColor(Ui.foreground());
            g.drawString(ellipsize(text.get(1), g.getFontMetrics(), tw), tx, b.y + 33);
            return;
        }
        g.setFont(nameFont);
        var fm = g.getFontMetrics();
        boolean hasSub = text.size() > 1;
        int nameY = hasSub ? b.y + 24 : b.y + (b.height + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(ellipsize(text.get(0), fm, tw), tx, nameY);
        if (hasSub) {
            g.setFont(subFont);
            g.setColor(Ui.muted());
            g.drawString(ellipsize(text.get(1), g.getFontMetrics(), tw), tx, b.y + 41);
        }
    }

    /// What a box says, top to bottom, as painted (before any ellipsis): a
    /// person's name and lifespan; a family's line, then its partner's name;
    /// an empty slot's offer.
    static List<String> lines(Node n) {
        var s = n.person();
        return switch (n.kind()) {
            case EMPTY -> List.of("+  " + n.text());
            case FAMILY -> s == null ? List.of(n.text() == null ? "" : n.text())
                    : List.of(n.text() == null ? "" : n.text(), s.displayName());
            case PERSON -> s.lifespan().isBlank() ? List.of(s.displayName()) : List.of(s.displayName(), s.lifespan());
        };
    }

    /// Every box's [lines], in layout order: what the chart shows, for tests.
    List<List<String>> boxes() {
        return placed.stream().map(p -> lines(p.node())).toList();
    }

    static String ellipsize(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) return s;
        var e = "…";
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end) + e) > width) end--;
        return s.substring(0, end).stripTrailing() + e;
    }

    // ---- building the trees ---------------------------------------------------------

    /// Ancestors: father above mother; a missing parent becomes an empty slot
    /// (only for people shown, so the chart doesn't fill with blanks).
    static Node ancestors(Model.Pedigree p, int depth) {
        var next = new ArrayList<Node>();
        if (depth > 0) {
            next.add(p.father() != null ? ancestors(p.father(), depth - 1)
                    : new Node(null, Node.Kind.EMPTY, "Add father", p.person().key(), "father", List.of()));
            next.add(p.mother() != null ? ancestors(p.mother(), depth - 1)
                    : new Node(null, Node.Kind.EMPTY, "Add mother", p.person().key(), "mother", List.of()));
        }
        return Node.person(p.person(), next);
    }

    /// Descendants: each family as a small box naming the partner, then its children.
    static Node descendants(Model.Descendants d) {
        var next = new ArrayList<Node>();
        for (var f : d.families()) {
            var kids = new ArrayList<Node>();
            for (var c : f.children()) kids.add(descendants(c));
            var label = familyLabel(f);
            next.add(new Node(f.partner(), Node.Kind.FAMILY, label, d.person().key(), f.family(), kids));
        }
        return Node.person(d.person(), next);
    }

    /// A family box's small line: the marriage if known, else the family's key.
    static String familyLabel(Model.DescendantFamily f) {
        var parts = new ArrayList<String>();
        var m = f.married();
        if (m != null && !m.date().isBlank()) parts.add("m. " + m.date().replaceFirst("^(ABT|EST|CAL) ", "c. "));
        else if (m != null) parts.add("Married");
        if (f.divorced()) parts.add("divorced");
        parts.add(f.family());
        var line = String.join("  ·  ", parts);
        return f.partner() == null ? line + "  ·  partner unknown" : line;
    }

    /// Scrolls so the root box is in view (vertically centred when possible).
    void revealRoot() {
        SwingUtilities.invokeLater(() -> {
            var r = rootBounds();
            if (getParent() instanceof javax.swing.JViewport vp) {
                int y = vertical ? 0 : Math.max(0, r.y + r.height / 2 - vp.getHeight() / 2);
                int x = vertical ? Math.max(0, r.x + r.width / 2 - vp.getWidth() / 2) : Math.max(0, r.x - MARGIN);
                vp.setViewPosition(new Point(Math.min(x, Math.max(0, getWidth() - vp.getWidth())),
                        Math.min(y, Math.max(0, getHeight() - vp.getHeight()))));
            }
        });
    }
}
