package ged.desktop;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;

/// Shown when no file is chosen: the app's mark, a way to open a file, and the
/// files open in the daemon or opened lately.
final class WelcomeView {

    static JComponent build(Model.Files files, Actions actions) {
        var page = Ui.stack(26);

        var hero = Ui.stack(10);
        var icon = new JLabel(new javax.swing.ImageIcon(AppIcon.render(256).getScaledInstance(112, 112, java.awt.Image.SCALE_SMOOTH)));
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        hero.add(icon);
        var title = Ui.title("gedcom-shell");
        title.setHorizontalAlignment(SwingConstants.CENTER);
        hero.add(title);
        var sub = Ui.mutedLabel("Browse and edit GEDCOM family trees. Your edits are kept by the gedcom-shell daemon until you save them.");
        sub.setHorizontalAlignment(SwingConstants.CENTER);
        hero.add(sub);
        var open = Ui.button("Open a GEDCOM file…", Icons.folderOpen(16), actions::chooseFile);
        open.putClientProperty("JButton.buttonType", null);
        var buttonRow = Ui.transparent(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 6));
        buttonRow.add(open);
        hero.add(buttonRow);
        page.add(hero);

        if (!files.open().isEmpty()) {
            var list = Ui.stack(4);
            for (var f : files.open()) {
                var detail = new ArrayList<String>();
                detail.add(f.people() + " people");
                detail.add(f.families() + " families");
                if (f.dirtyCount() > 0) detail.add(f.dirtyCount() == 1 ? "1 unsaved edit" : f.dirtyCount() + " unsaved edits");
                list.add(fileRow(f.name(), f.path(), String.join("  ·  ", detail), true, f.dirtyCount() > 0,
                        () -> actions.openFile(f.path()), null));
            }
            page.add(Ui.section("Open in the daemon", Icons.folderOpen(14), list));
        }
        var recent = files.recent().stream().filter(r -> !r.open()).toList();
        if (!recent.isEmpty()) {
            var list = Ui.stack(4);
            for (var r : recent) {
                list.add(fileRow(r.name(), r.path(), r.exists() ? "" : "Missing: the file is no longer there", r.exists(), false,
                        r.exists() ? () -> actions.openFile(r.path()) : null, () -> actions.forget(r.id())));
            }
            page.add(Ui.section("Recent files", Icons.refresh(14), list));
        }
        if (files.open().isEmpty() && recent.isEmpty()) {
            var hint = Ui.mutedLabel("No files yet. Open one with File ▸ Open… (⌘O), or drop a .ged path on the command line.");
            hint.setHorizontalAlignment(SwingConstants.CENTER);
            page.add(hint);
        }
        var wrap = new Ui.Page(page, 680);
        wrap.setBorder(BorderFactory.createEmptyBorder(56, Ui.PAGE_PAD, Ui.PAGE_PAD, Ui.PAGE_PAD));
        return wrap;
    }

    private static JComponent fileRow(String name, String path, String detail, boolean enabled, boolean dirty,
                                      Runnable open, Runnable forget) {
        var row = new JPanel(new BorderLayout(12, 0)) {
            boolean hover;

            @Override
            protected void paintComponent(Graphics g) {
                if (hover && open != null) {
                    var g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(Ui.cardHover());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g2.dispose();
                }
            }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        var icon = new JLabel(Icons.tinted(Icons.file(22), enabled ? Ui.accent() : Ui.muted()));
        row.add(icon, BorderLayout.WEST);
        var lines = Ui.stack(1);
        var n = Ui.label(name);
        n.setFont(Ui.font(0, Font.BOLD));
        if (!enabled) n.setForeground(Ui.muted());
        lines.add(n);
        var p = Ui.small(path);
        lines.add(p);
        if (!detail.isBlank()) {
            var d = Ui.small(detail);
            if (dirty) d.setForeground(Ui.warning());
            if (!enabled) d.setForeground(Ui.danger());
            lines.add(d);
        }
        row.add(lines, BorderLayout.CENTER);
        if (forget != null) {
            var x = Ui.toolButton(Icons.close(12), "Remove from the recent list", forget);
            var east = Ui.transparent(new java.awt.GridBagLayout());
            east.add(x);
            row.add(east, BorderLayout.EAST);
        }
        if (open != null) {
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.setToolTipText("Open " + path);
            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    row.hover = true;
                    row.repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    row.hover = row.contains(e.getPoint());
                    row.repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    open.run();
                }
            });
        }
        return row;
    }

    private WelcomeView() {
    }
}
