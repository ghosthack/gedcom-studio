package ged.desktop;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/// A row of the people list, laid out like a macOS source list: avatar, name,
/// and under it the lifespan (and, when searching, the other name or nickname
/// that matched), with the key at the right. Selection and hover are rounded
/// fills inset from the list's edges.
final class PersonCellRenderer extends JPanel implements ListCellRenderer<Model.PersonSummary> {

    /// Client property on the list: the hovered row's index (Integer), or absent.
    static final String HOVER = "gedcom.hoverIndex";

    private static final int INSET_X = 6, INSET_Y = 1, ARC = 12;

    private final Images images;
    private final JLabel avatar = new JLabel();
    private final JLabel name = Ui.label("");
    private final JLabel detail = Ui.label("");
    private final JLabel key = Ui.label("");
    private Color fill;

    PersonCellRenderer(Images images) {
        super(new BorderLayout(10, 0));
        this.images = images;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(5, INSET_X + 8, 5, INSET_X + 10));
        add(avatar, BorderLayout.WEST);
        var lines = Ui.stack(0);
        lines.add(name);
        lines.add(detail);
        var center = Ui.transparent(new java.awt.GridBagLayout());
        center.add(lines, new java.awt.GridBagConstraints(0, 0, 1, 1, 1, 1, java.awt.GridBagConstraints.WEST,
                java.awt.GridBagConstraints.HORIZONTAL, new java.awt.Insets(0, 0, 0, 0), 0, 0));
        add(center, BorderLayout.CENTER);
        var east = Ui.transparent(new java.awt.GridBagLayout());
        east.add(key);
        add(east, BorderLayout.EAST);
    }

    /// The second line: lifespan, and what a search matched if it wasn't the name.
    static String detail(Model.PersonSummary p) {
        var parts = new java.util.ArrayList<String>();
        if (!p.lifespan().isBlank()) parts.add(p.lifespan());
        if (p.matched() != null && !p.matched().isBlank()) parts.add("matches “" + p.matched() + "”");
        return parts.isEmpty() ? " " : String.join("  ·  ", parts);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends Model.PersonSummary> list, Model.PersonSummary p,
                                                  int index, boolean selected, boolean focused) {
        boolean listFocused = list.hasFocus();
        boolean hover = Integer.valueOf(index).equals(list.getClientProperty(HOVER));
        boolean strong = selected && listFocused;
        fill = selected ? (listFocused ? Ui.accent() : Ui.dark() ? new Color(0x3A3A3E) : new Color(0xDCDCE0))
                : hover ? Ui.alpha(Ui.foreground(), Ui.dark() ? 22 : 14) : null;
        avatar.setIcon(new Avatar(images, p, 32));
        name.setText(p.displayName());
        name.setFont(Ui.font(0, Font.BOLD));
        detail.setText(detail(p));
        detail.setFont(Ui.font(-1.5f, Font.PLAIN));
        key.setText(p.key());
        key.setFont(Ui.font(-2.5f, Font.PLAIN));
        var fg = strong ? Color.WHITE : Ui.foreground();
        var muted = strong ? Ui.alpha(Color.WHITE, 200) : Ui.muted();
        name.setForeground(fg);
        detail.setForeground(p.matched() != null && !p.matched().isBlank() && !strong ? Ui.mix(muted, Ui.accent(), 0.35) : muted);
        key.setForeground(muted);
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (fill == null) return;
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(fill);
        g2.fillRoundRect(INSET_X, INSET_Y, getWidth() - 2 * INSET_X, getHeight() - 2 * INSET_Y, ARC, ARC);
        g2.dispose();
    }
}
