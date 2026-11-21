package ged.desktop;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/// A person's face: their photo (cropped as GEDCOM says) in a circle, or
/// their initials on a colour of their own while there is none.
final class Avatar implements Icon {

    private final Images images;
    private final Model.PersonSummary person;
    private final int size;

    Avatar(Images images, Model.PersonSummary person, int size) {
        this.images = images;
        this.person = person;
        this.size = size;
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
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        var circle = new Ellipse2D.Float(x, y, size, size);
        var photo = person == null || person.photo() == null || images == null ? null
                : images.cover(person.photo(), person.photoCrop(), person.cropKnown(), size, size, c);
        if (photo != null) {
            g2.setClip(circle);
            g2.drawImage(photo, x, y, size, size, null);
            g2.setClip(null);
            g2.setColor(Ui.alpha(Color.BLACK, 30));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new Ellipse2D.Float(x + 0.5f, y + 0.5f, size - 1, size - 1));
        } else {
            paintInitials(g2, circle, person, size);
        }
        g2.dispose();
    }

    static void paintInitials(Graphics2D g2, Shape shape, Model.PersonSummary p, int size) {
        var seed = p == null ? "?" : p.key() + p.name();
        var base = Ui.avatarColor(seed);
        var bounds = shape.getBounds2D();
        g2.setPaint(new java.awt.GradientPaint(0, (float) bounds.getY(), Ui.mix(base, Color.WHITE, 0.12),
                0, (float) bounds.getMaxY(), Ui.mix(base, Color.BLACK, 0.08)));
        g2.fill(shape);
        var text = p == null ? "?" : PhotoMath.initials(p.given(), p.surname(), p.name());
        var font = Ui.base().deriveFont(Font.BOLD, Math.max(9f, size * 0.38f));
        g2.setFont(font);
        var fm = g2.getFontMetrics();
        g2.setColor(Color.WHITE);
        float tx = (float) (bounds.getCenterX() - fm.stringWidth(text) / 2.0);
        float ty = (float) (bounds.getCenterY() + (fm.getAscent() - fm.getDescent()) / 2.0);
        g2.drawString(text, tx, ty);
    }

    /// A rounded-rectangle portrait for page headers and the photo strip.
    static final class Portrait extends JComponent {
        private final Images images;
        private final Model.PersonSummary person;
        private final String url;
        private final Model.Crop crop;
        private final boolean knownCrop;
        private final int w, h;

        /// `url` and `crop` from the person's photo list (crop known), or
        /// null `url` for the initials.
        Portrait(Images images, Model.PersonSummary person, String url, Model.Crop crop, int w, int h) {
            this.images = images;
            this.person = person;
            this.url = url;
            this.crop = crop;
            this.knownCrop = true;
            this.w = w;
            this.h = h;
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(w, h);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            float arc = Math.min(w, h) * 0.22f;
            var shape = new RoundRectangle2D.Float(0, 0, w, h, arc, arc);
            var img = url == null ? null : images.cover(url, crop, knownCrop, w, h, this);
            if (img != null) {
                g2.setClip(shape);
                g2.drawImage(img, 0, 0, w, h, null);
                g2.setClip(null);
                g2.setColor(Ui.alpha(Color.BLACK, 36));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc));
            } else {
                paintInitials(g2, shape, person, Math.min(w, h));
            }
            g2.dispose();
        }
    }
}
