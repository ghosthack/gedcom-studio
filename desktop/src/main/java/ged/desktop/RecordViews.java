package ged.desktop;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/// The lists of families, notes, sources and media; a record's own page; and
/// the file's warnings.
final class RecordViews {

    /// One row of a list page.
    record Item(String key, String title, String detail, Icon kindIcon, Place target, String thumbnail) {
        enum Icon { FAMILY, NOTE, SOURCE, MEDIA }
    }

    static List<Item> familyItems(List<Model.FamilySummary> families) {
        var out = new ArrayList<Item>();
        for (var f : families) {
            var d = new ArrayList<String>();
            if (!f.married().isBlank()) d.add("Married " + f.married());
            d.add(f.children() == 1 ? "1 child" : f.children() + " children");
            out.add(new Item(f.key(), f.title().isBlank() ? "Family " + f.key() : f.title(), String.join("  ·  ", d),
                    Item.Icon.FAMILY, new Place.FamilyPage(f.key()), null));
        }
        return out;
    }

    static List<Item> recordItems(List<Model.RecordSummary> records) {
        var out = new ArrayList<Item>();
        for (var r : records) {
            var icon = switch (r.kind()) {
                case "notes" -> Item.Icon.NOTE;
                case "media" -> Item.Icon.MEDIA;
                default -> Item.Icon.SOURCE;
            };
            out.add(new Item(r.key(), r.title().isBlank() ? r.key() : r.title(),
                    r.usedBy() == 1 ? "Used once" : r.usedBy() == 0 ? "Not used" : "Used " + r.usedBy() + " times",
                    icon, new Place.RecordPage(r.key()), r.thumbnail()));
        }
        return out;
    }

    /// Items whose title or key contains every word of `q` (case- and accent-blind).
    static List<Item> filter(List<Item> items, String q) {
        if (q == null || q.isBlank()) return items;
        var words = fold(q).split("\\s+");
        var out = new ArrayList<Item>();
        for (var it : items) {
            var hay = fold(it.title() + " " + it.key());
            boolean all = true;
            for (var w : words) all &= hay.contains(w);
            if (all) out.add(it);
        }
        return out;
    }

    static String fold(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    // ---- list page --------------------------------------------------------------

    static JComponent list(String kind, List<Item> items, Actions actions) {
        var page = new JPanel(new BorderLayout());
        page.setBackground(Ui.pageBackground());
        var head = Ui.stack(6);
        head.setBorder(BorderFactory.createEmptyBorder(Ui.PAGE_PAD - 4, Ui.PAGE_PAD, 12, Ui.PAGE_PAD));
        var kicker = Ui.overline(items.size() == 1 ? "1 record" : items.size() + " records");
        head.add(kicker);
        var titleRow = Ui.transparent(new BorderLayout(12, 0));
        var title = Ui.title(Place.Kinds.plural(kind));
        title.setIcon(Icons.forKind(kind, 26));
        title.setIconTextGap(12);
        titleRow.add(title, BorderLayout.WEST);
        var filter = new JTextField(18);
        filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter " + Place.Kinds.plural(kind).toLowerCase(Locale.ROOT));
        filter.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.search(14));
        filter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        var filterWrap = Ui.transparent(new GridBagLayout());
        filterWrap.add(filter);
        titleRow.add(filterWrap, BorderLayout.EAST);
        head.add(titleRow);
        page.add(head, BorderLayout.NORTH);

        var model = new DefaultListModel<Item>();
        items.forEach(model::addElement);
        var list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ItemRenderer(actions.images()));
        list.setBackground(Ui.pageBackground());
        list.setFixedCellHeight(-1);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) actions.go(model.get(i).target());
            }
        });
        list.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER && list.getSelectedValue() != null) {
                    actions.go(list.getSelectedValue().target());
                }
            }
        });
        filter.getDocument().addDocumentListener(new DocumentListener() {
            void update() {
                model.clear();
                filter(items, filter.getText()).forEach(model::addElement);
            }

            @Override public void insertUpdate(DocumentEvent e) { update(); }
            @Override public void removeUpdate(DocumentEvent e) { update(); }
            @Override public void changedUpdate(DocumentEvent e) { update(); }
        });
        var scroll = new javax.swing.JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, Ui.PAGE_PAD - 8, 0, Ui.PAGE_PAD - 8));
        scroll.getViewport().setBackground(Ui.pageBackground());
        scroll.setBackground(Ui.pageBackground());
        if (items.isEmpty()) {
            var empty = Ui.mutedLabel("This file has no " + Place.Kinds.plural(kind).toLowerCase(Locale.ROOT) + ".");
            empty.setHorizontalAlignment(SwingConstants.CENTER);
            page.add(empty, BorderLayout.CENTER);
        } else {
            page.add(scroll, BorderLayout.CENTER);
        }
        return page;
    }

    private static final class ItemRenderer extends JPanel implements javax.swing.ListCellRenderer<Item> {
        private final JLabel icon = new JLabel();
        private final JLabel title = Ui.label("");
        private final JLabel detail = Ui.label("");
        private final JLabel key = Ui.label("");
        private boolean selected;
        private final Images images;

        ItemRenderer(Images images) {
            this.images = images;
            super(new BorderLayout(12, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
            var lines = Ui.stack(1);
            title.setFont(Ui.font(0, Font.BOLD));
            detail.setFont(Ui.font(-1.5f, Font.PLAIN));
            lines.add(title);
            lines.add(detail);
            add(icon, BorderLayout.WEST);
            add(lines, BorderLayout.CENTER);
            key.setFont(Ui.font(-1.5f, Font.PLAIN));
            add(key, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Item> list, Item it, int index, boolean sel, boolean focus) {
            selected = sel;
            var fg = sel ? java.awt.Color.WHITE : Ui.foreground();
            var muted = sel ? Ui.alpha(java.awt.Color.WHITE, 200) : Ui.muted();
            var ic = switch (it.kindIcon()) {
                case FAMILY -> Icons.family(20);
                case NOTE -> Icons.note(20);
                case SOURCE -> Icons.source(20);
                case MEDIA -> Icons.media(20);
            };
            icon.setIcon(it.thumbnail() != null ? new Thumb(images, it.thumbnail(), Icons.tinted(ic, muted))
                    : new Padded(Icons.tinted(ic, muted), THUMB));
            title.setText(it.title());
            title.setForeground(fg);
            detail.setText(it.detail());
            detail.setForeground(muted);
            key.setText(it.key());
            key.setForeground(muted);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g2.setColor(Ui.accent());
                g2.fillRoundRect(2, 1, getWidth() - 4, getHeight() - 2, 12, 12);
            } else {
                g2.setColor(Ui.hairline());
                g2.drawLine(14, getHeight() - 1, getWidth() - 14, getHeight() - 1);
            }
            g2.dispose();
        }
    }

    static final int THUMB = 44;

    /// A media record's picture, cropped to a rounded square; its kind's icon
    /// while it loads or if it can't be read.
    private record Thumb(Images images, String url, javax.swing.Icon fallback) implements javax.swing.Icon {
        @Override public int getIconWidth() { return THUMB; }
        @Override public int getIconHeight() { return THUMB; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            var img = images.cover(url, null, true, THUMB, THUMB, c);
            if (img == null) {
                new Padded(fallback, THUMB).paintIcon(c, g, x, y);
                return;
            }
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            var shape = new java.awt.geom.RoundRectangle2D.Float(x, y, THUMB, THUMB, 10, 10);
            g2.setClip(shape);
            g2.drawImage(img, x, y, THUMB, THUMB, null);
            g2.setClip(null);
            g2.setColor(Ui.alpha(java.awt.Color.BLACK, 30));
            g2.draw(new java.awt.geom.RoundRectangle2D.Float(x + 0.5f, y + 0.5f, THUMB - 1, THUMB - 1, 10, 10));
            g2.dispose();
        }
    }

    /// An icon centred in a larger square, so rows with and without pictures line up.
    private record Padded(javax.swing.Icon icon, int size) implements javax.swing.Icon {
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            icon.paintIcon(c, g, x + (size - icon.getIconWidth()) / 2, y + (size - icon.getIconHeight()) / 2);
        }
    }

    // ---- a record's page --------------------------------------------------------

    static JComponent record(Model.RecordDetail r, Actions actions) {
        var page = Ui.stack(22);
        var head = Ui.stack(6);
        var kicker = Ui.overline(Place.Kinds.singular(r.kind()) + " " + r.key());
        kicker.setIcon(Icons.tinted(Icons.forKind(r.kind(), 14), Ui.muted()));
        kicker.setIconTextGap(6);
        head.add(kicker);
        head.add(Ui.title(r.title().isBlank() ? r.key() : r.title()));
        var used = Ui.label(r.usedBy() == 0 ? "Not used by anyone" : r.usedBy() == 1 ? "Used once" : "Used " + r.usedBy() + " times");
        used.setFont(Ui.font(1, Font.PLAIN));
        used.setForeground(Ui.muted());
        head.add(used);
        var buttons = Ui.row(8, Ui.button("Edit " + Place.Kinds.singular(r.kind()).toLowerCase(Locale.ROOT), Icons.edit(14),
                () -> actions.editRecord(r.key())));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        head.add(buttons);
        page.add(head);

        if (r.file() != null) page.add(mediaPreview(r, actions));
        if (!r.text().isBlank()) {
            var t = Ui.paragraph(r.text());
            t.setFont(Ui.font(1, Font.PLAIN));
            page.add(Ui.section("Text", Icons.note(14), t));
        }
        if (!r.fields().isEmpty()) page.add(Ui.section("Details", Icons.file(14), fields(r.fields(), actions)));
        if (!r.users().isEmpty()) page.add(Ui.section("Used by", Icons.link(14), users(r.users(), actions)));
        return new Ui.Page(page, 900);
    }

    private static JComponent mediaPreview(Model.RecordDetail r, Actions actions) {
        var holder = Ui.stack(10);
        var image = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                var img = actions.images().original(r.file(), this);
                if (img == null) return new Dimension(10, actions.images().failed(r.file()) ? 0 : 240);
                int w = getParent() == null ? 640 : Math.max(100, getParent().getWidth());
                return PhotoMath.fit(img.getWidth(), img.getHeight(), w, 520);
            }

            @Override
            protected void paintComponent(Graphics g) {
                var img = actions.images().original(r.file(), this);
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (img == null) {
                    g2.setColor(Ui.card());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                } else {
                    var d = PhotoMath.fit(img.getWidth(), img.getHeight(), getWidth(), getHeight());
                    var clip = new java.awt.geom.RoundRectangle2D.Float(0, 0, d.width, d.height, 14, 14);
                    g2.setClip(clip);
                    g2.drawImage(img, 0, 0, d.width, d.height, null);
                }
                g2.dispose();
                revalidateOnce();
            }

            private boolean sized;

            private void revalidateOnce() {
                if (!sized && actions.images().original(r.file(), this) != null) {
                    sized = true;
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        invalidate();
                        var p = javax.swing.SwingUtilities.getAncestorOfClass(Ui.Page.class, this);
                        if (p != null) {
                            p.revalidate();
                            p.repaint();
                        }
                    });
                }
            }
        };
        image.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        image.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                actions.showPhotos(List.of(new Model.Photo(r.file(), fileValue(r), r.title(), "", null)), 0, r.title());
            }
        });
        holder.add(image);
        var open = Ui.link("Open in the default app", () -> actions.openExternally(r.file(), PersonView.fileName(fileValue(r))));
        open.setIcon(Icons.tinted(Icons.external(14), Ui.link()));
        open.setIconTextGap(6);
        holder.add(Ui.row(0, open));
        return holder;
    }

    private static String fileValue(Model.RecordDetail r) {
        return r.fields().stream().filter(f -> f.tag().equals("FILE")).map(Model.RecordField::value).findFirst().orElse(r.key());
    }

    private static JComponent fields(List<Model.RecordField> fields, Actions actions) {
        var grid = Ui.transparent(new GridBagLayout());
        int row = 0;
        for (var f : fields) {
            var label = Ui.label(f.label().isBlank() ? f.tag() : f.label());
            label.setForeground(Ui.muted());
            label.setBorder(BorderFactory.createEmptyBorder(0, f.depth() * 18, 0, 0));
            var gc = new GridBagConstraints(0, row, 1, 1, 0, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                    new Insets(4, 0, 4, 18), 0, 0);
            grid.add(label, gc);
            JComponent value;
            if (f.link() != null) {
                var target = f.link();
                var l = Ui.link(f.value().isBlank() ? target : f.value(), () -> actions.resolve(target));
                l.setIcon(Icons.tinted(Icons.forKind(f.linkKind() == null ? "" : f.linkKind(), 14), Ui.link()));
                l.setIconTextGap(6);
                value = l;
            } else {
                value = Ui.paragraph(f.value());
            }
            grid.add(value, new GridBagConstraints(1, row, 1, 1, 1, 0, GridBagConstraints.NORTHWEST,
                    GridBagConstraints.HORIZONTAL, new Insets(4, 0, 4, 0), 0, 0));
            row++;
        }
        return grid;
    }

    private static JComponent users(List<Model.RecordUser> users, Actions actions) {
        var s = Ui.stack(4);
        var people = users.stream().filter(u -> u.person() != null).map(Model.RecordUser::person).toList();
        if (!people.isEmpty()) s.add(PersonChip.grid(actions, people, 2, x -> null, null, x -> null));
        for (var u : users) {
            if (u.person() != null) continue;
            Place target = u.kind().equals("family") ? new Place.FamilyPage(u.key()) : new Place.RecordPage(u.key());
            var l = Ui.link(u.title().isBlank() ? u.key() : u.title(), () -> actions.go(target));
            l.setIcon(Icons.tinted(Icons.forKind(u.kind(), 14), Ui.link()));
            l.setIconTextGap(8);
            var row = Ui.transparent(new BorderLayout());
            row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            row.add(l, BorderLayout.WEST);
            row.add(Ui.small(Place.Kinds.singular(u.kind()) + " " + u.key()), BorderLayout.EAST);
            s.add(row);
        }
        return s;
    }

    // ---- warnings ---------------------------------------------------------------

    private static final Pattern ID = Pattern.compile("@([^@\\s]+)@");

    static JComponent warnings(Model.FileInfo info, Actions actions) {
        var page = Ui.stack(18);
        var head = Ui.stack(6);
        head.add(Ui.overline(info.name()));
        var title = Ui.title("Warnings");
        title.setIcon(Icons.warning(26));
        title.setIconTextGap(12);
        head.add(title);
        var sub = Ui.label(info.warnings().isEmpty()
                ? "Nothing to report: the file reads cleanly."
                : "What the daemon noticed while reading " + info.name() + ". The file is still usable; these are worth a look.");
        sub.setForeground(Ui.muted());
        head.add(sub);
        page.add(head);
        if (!info.warnings().isEmpty()) {
            var list = Ui.stack(0);
            for (var w : info.warnings()) list.add(warningRow(w, actions));
            var card = new Ui.Card(new BorderLayout());
            card.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
            card.add(list);
            page.add(card);
        }
        return new Ui.Page(page, 900);
    }

    private static JComponent warningRow(String text, Actions actions) {
        var row = Ui.transparent(new BorderLayout(12, 0));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.hairline()),
                BorderFactory.createEmptyBorder(10, 0, 10, 0)));
        var icon = new JLabel(Icons.tinted(Icons.warning(16), Ui.warning()));
        icon.setVerticalAlignment(SwingConstants.TOP);
        row.add(icon, BorderLayout.WEST);
        var body = Ui.stack(4);
        body.add(Ui.paragraph(text));
        var m = ID.matcher(text);
        var links = new ArrayList<JComponent>();
        var seen = new java.util.HashSet<String>();
        while (m.find()) {
            var key = m.group(1);
            if (seen.add(key)) links.add(Ui.link("Show " + key, () -> actions.resolve(key)));
        }
        if (!links.isEmpty()) body.add(Ui.row(14, links.toArray(new Component[0])));
        row.add(body, BorderLayout.CENTER);
        return row;
    }

    private RecordViews() {
    }
}
