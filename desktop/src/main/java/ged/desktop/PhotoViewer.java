package ged.desktop;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.List;

/// A person's photos, large, one at a time: the whole picture, with the part
/// the GEDCOM crop names outlined (the rest dimmed), and arrows to step
/// through them.
final class PhotoViewer extends JDialog {

    private final Images images;
    private final List<Model.Photo> photos;
    private int index;
    private final JLabel caption = Ui.label("");
    private final JLabel counter = Ui.label("");
    private final JCheckBox showCrop = new JCheckBox("Show crop", true);
    private final Canvas canvas = new Canvas();
    private final javax.swing.JButton prev, next;

    PhotoViewer(Window owner, Images images, List<Model.Photo> photos, int index, String who) {
        super(owner, who + " — photos", ModalityType.MODELESS);
        this.images = images;
        this.photos = photos;
        this.index = Math.max(0, Math.min(index, photos.size() - 1));
        var root = new JPanel(new BorderLayout());
        root.setBackground(new Color(0x161618));
        root.add(canvas, BorderLayout.CENTER);
        var bar = Ui.transparent(new BorderLayout(12, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));
        caption.setForeground(new Color(0xF2F2F2));
        caption.setFont(Ui.font(1, Font.BOLD));
        counter.setForeground(new Color(0xA0A0A6));
        var text = Ui.stack(2);
        text.add(caption);
        text.add(counter);
        bar.add(text, BorderLayout.CENTER);
        prev = Ui.toolButton(Icons.tinted(Icons.chevronLeft(18), Color.WHITE), "Previous (←)", () -> step(-1));
        next = Ui.toolButton(Icons.tinted(Icons.chevronRight(18), Color.WHITE), "Next (→)", () -> step(1));
        showCrop.setOpaque(false);
        showCrop.setForeground(new Color(0xD0D0D4));
        showCrop.setFocusable(false);
        showCrop.addActionListener(e -> canvas.repaint());
        bar.add(Ui.row(6, showCrop, prev, next), BorderLayout.EAST);
        root.add(bar, BorderLayout.SOUTH);
        setContentPane(root);
        bind(KeyEvent.VK_LEFT, () -> step(-1));
        bind(KeyEvent.VK_RIGHT, () -> step(1));
        bind(KeyEvent.VK_ESCAPE, this::dispose);
        update();
        var screen = owner != null ? owner.getGraphicsConfiguration().getBounds() : new Rectangle(0, 0, 1280, 800);
        setSize(new Dimension(Math.min(1100, screen.width - 120), Math.min(820, screen.height - 120)));
        setLocationRelativeTo(owner);
    }

    private void bind(int key, Runnable r) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), "k" + key);
        getRootPane().getActionMap().put("k" + key, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        });
    }

    private void step(int d) {
        if (photos.size() < 2) return;
        index = Math.floorMod(index + d, photos.size());
        update();
    }

    private void update() {
        var ph = photos.get(index);
        var t = PersonView.caption(ph);
        caption.setText(t + (ph.date().isBlank() ? "" : "  ·  " + ph.date()));
        counter.setText((photos.size() > 1 ? (index + 1) + " of " + photos.size() + "  ·  " : "") + ph.file());
        showCrop.setVisible(ph.crop() != null);
        prev.setVisible(photos.size() > 1);
        next.setVisible(photos.size() > 1);
        canvas.repaint();
    }

    private final class Canvas extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            var ph = photos.get(index);
            var img = ph.url() == null ? null : images.original(ph.url(), this);
            int pad = 24;
            if (img == null) {
                g2.setColor(new Color(0x9A9AA0));
                g2.setFont(Ui.font(1, Font.PLAIN));
                var msg = ph.url() == null ? "This picture is not on this computer."
                        : images.failed(ph.url()) ? "This picture can't be shown." : "Loading…";
                var fm = g2.getFontMetrics();
                g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
                g2.dispose();
                return;
            }
            var d = PhotoMath.fit(img.getWidth(), img.getHeight(), getWidth() - 2 * pad, getHeight() - 2 * pad);
            int x = (getWidth() - d.width) / 2, y = (getHeight() - d.height) / 2;
            g2.drawImage(img, x, y, d.width, d.height, null);
            if (ph.crop() != null && showCrop.isSelected()) {
                double s = d.width / (double) img.getWidth();
                var r = PhotoMath.cropRect(img.getWidth(), img.getHeight(), ph.crop());
                var crop = new Rectangle2D.Double(x + r.x * s, y + r.y * s, r.width * s, r.height * s);
                var dim = new Area(new Rectangle2D.Double(x, y, d.width, d.height));
                dim.subtract(new Area(crop));
                g2.setColor(new Color(0, 0, 0, 120));
                g2.fill(dim);
                g2.setColor(Color.WHITE);
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.draw(crop);
            }
            g2.dispose();
        }
    }
}
