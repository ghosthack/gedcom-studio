package ged.desktop;

import javax.swing.JFrame;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// The app's mark, drawn with Java2D: a family tree (one person, two parents,
/// four grandparents) in warm colours on a green rounded square, sized like a
/// macOS icon (the square fills about 80 % of the canvas, with a soft shadow).
final class AppIcon {

    private static final int[] SIZES = {16, 20, 24, 32, 48, 64, 128, 256, 512};
    private static final Map<Integer, BufferedImage> CACHE = new ConcurrentHashMap<>();

    static final Color GREEN_TOP = new Color(0x3A9A74);
    static final Color GREEN_BOTTOM = new Color(0x1B5A45);
    static final Color CREAM = new Color(0xF7EEDC);
    static final Color GOLD = new Color(0xF5C04E);
    static final Color CORAL = new Color(0xEE8466);
    static final Color SKY = new Color(0x86C3E3);

    static List<Image> images() {
        var out = new ArrayList<Image>();
        for (int s : SIZES) out.add(render(s));
        return out;
    }

    static void applyTo(JFrame frame) {
        frame.setIconImages(images());
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(render(512));
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // the frame's icons still cover window managers without a taskbar
        }
    }

    /// The icon at `size`×`size` pixels.
    static BufferedImage render(int size) {
        return CACHE.computeIfAbsent(size, AppIcon::draw);
    }

    private static BufferedImage draw(int size) {
        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double f = size / 1024.0;
        g.scale(f, f);
        // small sizes use more of the canvas: there is no room for a shadow
        boolean small = size <= 32;
        double inset = small ? 40 : 100;
        double side = 1024 - 2 * inset;
        double arc = side * 0.45;
        if (!small) paintShadow(g, inset, side, arc);
        var square = new RoundRectangle2D.Double(inset, inset, side, side, arc, arc);
        g.setPaint(new GradientPaint(0, (float) inset, GREEN_TOP, 0, (float) (inset + side), GREEN_BOTTOM));
        g.fill(square);
        // a soft light from the top
        g.setClip(square);
        g.setPaint(new RadialGradientPaint(512f, (float) inset, (float) side * 0.8f, new float[] {0f, 1f},
                new Color[] {new Color(255, 255, 255, 46), new Color(255, 255, 255, 0)}));
        g.fill(square);
        g.setClip(null);

        double k = side / 824.0;      // the tree is laid out for the 824 square
        double ox = inset, oy = inset;
        double[][] nodes = {
                {412, 640, 70},                                    // the person
                {222, 418, 60}, {602, 418, 60},                    // parents
                {122, 196, 50}, {322, 196, 50}, {502, 196, 50}, {702, 196, 50}, // grandparents
        };
        Color[] fills = {GOLD, SKY, CORAL, SKY, CORAL, SKY, CORAL};
        float branch = (float) ((small ? 44 : 30) * k);
        g.setStroke(new BasicStroke(branch, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(CREAM);
        int[][] edges = {{0, 1}, {0, 2}, {1, 3}, {1, 4}, {2, 5}, {2, 6}};
        for (var e : edges) {
            var a = nodes[e[0]];
            var b = nodes[e[1]];
            double ax = ox + a[0] * k, ay = oy + a[1] * k, bx = ox + b[0] * k, by = oy + b[1] * k;
            double my = (ay + by) / 2;
            g.draw(new CubicCurve2D.Double(ax, ay, ax, my, bx, my, bx, by));
        }
        // the trunk, from the person down to the ground
        var root = nodes[0];
        g.draw(new java.awt.geom.Line2D.Double(ox + root[0] * k, oy + root[1] * k, ox + root[0] * k, oy + 760 * k));
        for (int i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            double r = n[2] * k * (small ? 1.25 : 1);
            double cx = ox + n[0] * k, cy = oy + n[1] * k;
            var disc = new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
            g.setColor(CREAM);
            double ring = r + (small ? 22 : 16) * k;
            g.fill(new Ellipse2D.Double(cx - ring, cy - ring, 2 * ring, 2 * ring));
            g.setColor(fills[i]);
            g.fill(disc);
            if (!small) {
                // a little head-and-shoulders in each disc
                g.setColor(new Color(255, 255, 255, 120));
                double hr = r * 0.32;
                g.fill(new Ellipse2D.Double(cx - hr, cy - r * 0.55, 2 * hr, 2 * hr));
                g.fill(new java.awt.geom.Arc2D.Double(cx - r * 0.62, cy + r * 0.12, r * 1.24, r * 1.1, 0, 180, java.awt.geom.Arc2D.CHORD));
            }
        }
        g.dispose();
        return img;
    }

    private static void paintShadow(Graphics2D g, double inset, double side, double arc) {
        for (int i = 0; i < 18; i++) {
            float a = 0.018f * (1 - i / 18f);
            g.setColor(new Color(0, 0, 0, Math.round(a * 255)));
            double grow = i * 2.2;
            g.fill(new RoundRectangle2D.Double(inset - grow, inset - grow + 14, side + 2 * grow, side + 2 * grow, arc + grow, arc + grow));
        }
    }

    private AppIcon() {
    }
}
