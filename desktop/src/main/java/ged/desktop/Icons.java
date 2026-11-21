package ged.desktop;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;

/// Line icons in the Lucide style (SVG path data on a 24×24 grid, stroked 2
/// wide with round ends), drawn rather than shipped, and tinted with the
/// component's own foreground when painted, so they follow the theme.
///
/// The same idea as micro-harness-manager's `LucidIcon`, with curves added.
final class Icons {

    static Icon back(int s) { return new Line("M19 12H5M12 19l-7-7 7-7", s); }
    static Icon forward(int s) { return new Line("M5 12h14M12 5l7 7-7 7", s); }
    static Icon home(int s) { return new Line("M3 10.5 12 3l9 7.5M5 9v11a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V9", s); }
    static Icon edit(int s) { return new Line("M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5ZM15 5l4 4", s); }
    static Icon userPlus(int s) { return new Line("M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0zM19 8v6M22 11h-6", s); }
    static Icon user(int s) { return new Line("M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0z", s); }
    static Icon users(int s) { return new Line("M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0zM22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75", s); }
    static Icon save(int s) { return new Line("M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2zM17 21v-8H7v8M7 3v5h8", s); }
    static Icon sun(int s) {
        return new Line("M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42"
                + "M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42", s);
    }
    static Icon moon(int s) { return new Line("M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z", s); }
    static Icon search(int s) { return new Line("M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.35-4.35", s); }
    static Icon sort(int s) { return new Line("M3 16l4 4 4-4M7 20V4M21 8l-4-4-4 4M17 4v16", s); }
    static Icon folderOpen(int s) {
        return new Line("M6 14l1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5"
                + "a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2", s);
    }
    /// Ancestors: a person on the left branching to two on the right.
    static Icon ancestors(int s) { return new Line("M2 9.5h5v5H2zM17 3h5v5h-5zM17 16h5v5h-5zM7 12h5M12 5.5v13M12 5.5h5M12 18.5h5", s); }
    /// Descendants: one at the top branching down.
    static Icon descendants(int s) { return new Line("M9.5 2h5v5h-5zM3 17h5v5H3zM16 17h5v5h-5zM12 7v5M5.5 12h13M5.5 12v5M18.5 12v5", s); }
    static Icon note(int s) { return new Line("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8", s); }
    static Icon source(int s) { return new Line("M4 19.5A2.5 2.5 0 0 1 6.5 17H20M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z", s); }
    static Icon media(int s) { return new Line("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM10.5 8.5a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM21 15l-5-5L5 21", s); }
    static Icon family(int s) { return new Line("M9 13a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM15 13a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM3 21v-1a5 5 0 0 1 5-5h8a5 5 0 0 1 5 5v1", s); }
    static Icon warning(int s) { return new Line("M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0zM12 9v4M12 17h.01", s); }
    static Icon link(int s) {
        return new Line("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71", s);
    }
    static Icon unlink(int s) {
        return new Line("M18.84 12.25l1.72-1.71a5 5 0 0 0-7.07-7.07l-1.72 1.71M5.17 11.75l-1.71 1.71a5 5 0 0 0 7.07 7.07l1.71-1.71"
                + "M8 2v3M2 8h3M16 22v-3M22 16h-3", s);
    }
    static Icon chevronDown(int s) { return new Line("M6 9l6 6 6-6", s); }
    static Icon chevronRight(int s) { return new Line("M9 6l6 6-6 6", s); }
    static Icon chevronLeft(int s) { return new Line("M15 6l-6 6 6 6", s); }
    static Icon plus(int s) { return new Line("M12 5v14M5 12h14", s); }
    static Icon close(int s) { return new Line("M18 6 6 18M6 6l12 12", s); }
    static Icon external(int s) { return new Line("M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6", s); }
    static Icon calendar(int s) { return new Line("M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM16 2v4M8 2v4M3 10h18", s); }
    static Icon pin(int s) { return new Line("M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0zM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0z", s); }
    static Icon file(int s) { return new Line("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6", s); }
    static Icon refresh(int s) { return new Line("M20 11a8.1 8.1 0 0 0-15.5-2M4 4v5h5M4 13a8.1 8.1 0 0 0 15.5 2M20 20v-5h-5", s); }
    static Icon check(int s) { return new Line("M20 6 9 17l-5-5", s); }
    static Icon info(int s) { return new Line("M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 16v-4M12 8h.01", s); }

    /// A record kind's icon (`notes`, `sources`, `media`, `families`, `person`, `family`).
    static Icon forKind(String kind, int s) {
        return switch (kind) {
            case "notes" -> note(s);
            case "sources" -> source(s);
            case "media" -> media(s);
            case "families", "family" -> family(s);
            default -> user(s);
        };
    }

    /// An icon drawn from path data, in a fixed colour instead of the component's.
    static Icon tinted(Icon icon, Color color) {
        return icon instanceof Line l ? new Line(l.path, l.size, color) : icon;
    }

    static final class Line implements Icon {
        private final Path2D path;
        private final int size;
        private final Color color;

        Line(String data, int size) {
            this(parse(data), size, null);
        }

        private Line(Path2D path, int size, Color color) {
            this.path = path;
            this.size = size;
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            if (c != null && !c.isEnabled()) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g2.translate(x, y);
            double f = size / 24.0;
            g2.scale(f, f);
            g2.setColor(color != null ? color : foreground(c));
            // a little heavier when small, so 14 px icons don't look faint
            g2.setStroke(new BasicStroke(size <= 14 ? 2.2f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(path);
            g2.dispose();
        }

        private static Color foreground(Component c) {
            if (c instanceof JButton b && b.isDefaultButton()) {
                var d = UIManager.getColor("Button.default.foreground");
                if (d != null) return d;
            }
            if (c instanceof AbstractButton b && b.isSelected() && b.getClientProperty("gedcom.selectedForeground") instanceof Color sel) {
                return sel;
            }
            return c == null ? UIManager.getColor("Label.foreground") : c.getForeground();
        }
    }

    // ---- SVG path data: M L H V C S Q T A Z, absolute and relative --------------------

    static Path2D parse(String data) {
        var path = new Path2D.Float();
        var in = new Cursor(data);
        char cmd = 0;
        float x = 0, y = 0, sx = 0, sy = 0, cx = 0, cy = 0; // current, subpath start, last control
        char prev = 0;
        while (in.more()) {
            char c = in.command();
            if (c != 0) cmd = c;
            else if (cmd == 'M') cmd = 'L';
            else if (cmd == 'm') cmd = 'l';
            else if (cmd == 0) throw new IllegalArgumentException("path data starts with a number: " + data);
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    x = in.num() + ox;
                    y = in.num() + oy;
                    path.moveTo(x, y);
                    sx = x;
                    sy = y;
                }
                case 'L' -> {
                    x = in.num() + ox;
                    y = in.num() + oy;
                    path.lineTo(x, y);
                }
                case 'H' -> {
                    x = in.num() + ox;
                    path.lineTo(x, y);
                }
                case 'V' -> {
                    y = in.num() + oy;
                    path.lineTo(x, y);
                }
                case 'C' -> {
                    float x1 = in.num() + ox, y1 = in.num() + oy, x2 = in.num() + ox, y2 = in.num() + oy;
                    x = in.num() + ox;
                    y = in.num() + oy;
                    path.curveTo(x1, y1, x2, y2, x, y);
                    cx = x2;
                    cy = y2;
                }
                case 'S' -> {
                    boolean smooth = "CcSs".indexOf(prev) >= 0;
                    float x1 = smooth ? 2 * x - cx : x, y1 = smooth ? 2 * y - cy : y;
                    float x2 = in.num() + ox, y2 = in.num() + oy;
                    x = in.num() + ox;
                    y = in.num() + oy;
                    path.curveTo(x1, y1, x2, y2, x, y);
                    cx = x2;
                    cy = y2;
                }
                case 'Q' -> {
                    float x1 = in.num() + ox, y1 = in.num() + oy;
                    x = in.num() + ox;
                    y = in.num() + oy;
                    path.quadTo(x1, y1, x, y);
                    cx = x1;
                    cy = y1;
                }
                case 'T' -> {
                    boolean smooth = "QqTt".indexOf(prev) >= 0;
                    float x1 = smooth ? 2 * x - cx : x, y1 = smooth ? 2 * y - cy : y;
                    x = in.num() + ox;
                    y = in.num() + oy;
                    path.quadTo(x1, y1, x, y);
                    cx = x1;
                    cy = y1;
                }
                case 'A' -> {
                    float rx = in.num(), ry = in.num();
                    in.num();
                    boolean large = in.flag(), sweep = in.flag();
                    float ex = in.num() + ox, ey = in.num() + oy;
                    arc(path, x, y, rx, ry, large, sweep, ex, ey);
                    x = ex;
                    y = ey;
                }
                case 'Z' -> {
                    path.closePath();
                    x = sx;
                    y = sy;
                }
                default -> throw new IllegalArgumentException("unsupported path command '" + cmd + "'");
            }
            prev = cmd;
        }
        return path;
    }

    /// SVG's endpoint arc to an [Arc2D] (unrotated ellipses; SVG appendix F.6.5).
    private static void arc(Path2D.Float path, double x0, double y0, double rx, double ry,
                            boolean large, boolean sweep, double x1, double y1) {
        if (rx == 0 || ry == 0) {
            path.lineTo(x1, y1);
            return;
        }
        double dx2 = (x0 - x1) / 2.0, dy2 = (y0 - y1) / 2.0;
        double lambda = (dx2 * dx2) / (rx * rx) + (dy2 * dy2) / (ry * ry);
        if (lambda > 1) {
            rx *= Math.sqrt(lambda);
            ry *= Math.sqrt(lambda);
        }
        double rxs = rx * rx, rys = ry * ry;
        double num = rxs * rys - rxs * dy2 * dy2 - rys * dx2 * dx2;
        double den = rxs * dy2 * dy2 + rys * dx2 * dx2;
        double co = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double ccx = co * rx * dy2 / ry + (x0 + x1) / 2.0;
        double ccy = -co * ry * dx2 / rx + (y0 + y1) / 2.0;
        double t1 = Math.atan2((y0 - ccy) / ry, (x0 - ccx) / rx);
        double t2 = Math.atan2((y1 - ccy) / ry, (x1 - ccx) / rx);
        double d = t2 - t1;
        if (sweep && d < 0) d += 2 * Math.PI;
        else if (!sweep && d > 0) d -= 2 * Math.PI;
        path.append(new Arc2D.Double(ccx - rx, ccy - ry, 2 * rx, 2 * ry, -Math.toDegrees(t1), -Math.toDegrees(d), Arc2D.OPEN), true);
    }

    private static final class Cursor {
        private final String s;
        private int i;

        Cursor(String s) {
            this.s = s;
        }

        private void skip() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',')) i++;
        }

        boolean more() {
            skip();
            return i < s.length();
        }

        char command() {
            skip();
            char c = s.charAt(i);
            if (Character.isLetter(c) && c != 'e' && c != 'E') {
                i++;
                return c;
            }
            return 0;
        }

        /// An arc flag: a single 0 or 1, which SVG lets run into the next number.
        boolean flag() {
            skip();
            char c = s.charAt(i++);
            if (c != '0' && c != '1') throw new IllegalArgumentException("bad arc flag at " + (i - 1) + " in " + s);
            return c == '1';
        }

        float num() {
            skip();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) i++;
                else if (c == '.' && !dot) {
                    dot = true;
                    i++;
                } else break;
            }
            if (i == start) throw new IllegalArgumentException("expected a number at " + start + " in " + s);
            return Float.parseFloat(s.substring(start, i));
        }
    }

    private Icons() {
    }
}
