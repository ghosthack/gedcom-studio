package ged.desktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;

/// The person page: a header with their photo and names, their life as a
/// timeline, their family (parents, siblings, partners, children), notes,
/// sources and photos. Every relative is a link.
final class PersonView {

    static JComponent build(Model.Person p, Actions actions) {
        actions.images().learn(p);
        var page = Ui.stack(22);
        page.add(header(p, actions));

        var left = Ui.stack(16);
        left.add(Ui.section("Life", Icons.calendar(14), timeline(p)));
        if (!p.notes().isEmpty()) left.add(Ui.section("Notes", Icons.note(14), notes(p.notes())));
        if (!p.records().isEmpty()) left.add(Ui.section("Sources and records", Icons.source(14), records(p.records(), actions)));

        var right = Ui.stack(16);
        right.add(parents(p, actions));
        if (!p.halfSiblings().isEmpty()) {
            right.add(Ui.section("Half-siblings", Icons.users(14),
                    PersonChip.grid(actions, p.halfSiblings(), 1, s -> null, p, s -> null)));
        }
        // A family marked hidden (`1 _HIDE Y`) is left out until the link after the
        // others asks for it; its own page and every list still show it.
        var hidden = new ArrayList<JComponent>();
        for (var f : p.families()) {
            var v = family(p, f, actions);
            right.add(v);
            if (f.hidden()) {
                v.setVisible(false);
                hidden.add(v);
            }
        }
        if (!hidden.isEmpty()) {
            var reveal = Ui.transparent(new BorderLayout());
            reveal.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            reveal.add(Ui.link(hiddenLabel(hidden.size()), () -> {
                hidden.forEach(v -> v.setVisible(true));
                reveal.setVisible(false);
                right.revalidate();
                right.repaint();
            }), BorderLayout.WEST);
            right.add(reveal);
        }
        right.add(addFamilyRow(p, actions));

        page.add(new Ui.Columns(left, right, 0.5, 16, 720));
        if (!p.photos().isEmpty()) page.add(Ui.section("Photos", Icons.media(14), photoStrip(p, actions)));
        return new Ui.Page(page, 1040);
    }

    // ---- header -----------------------------------------------------------------

    private static JComponent header(Model.Person p, Actions actions) {
        var s = p.summary();
        var h = Ui.transparent(new BorderLayout(22, 0));
        var first = p.photos().stream().filter(ph -> ph.url() != null).findFirst().orElse(null);
        var portrait = new Avatar.Portrait(actions.images(), s, first == null ? null : first.url(),
                first == null ? null : first.crop(), 112, 136);
        if (first != null) {
            portrait.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            portrait.setToolTipText("Show photos");
            portrait.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    actions.showPhotos(p.photos(), p.photos().indexOf(first), s.displayName());
                }
            });
        }
        h.add(portrait, BorderLayout.WEST);

        var text = Ui.stack(6);
        var name = Ui.title(headerName(s, p.suffix()));
        text.add(name);
        var facts = new ArrayList<String>();
        if (!s.lifespan().isBlank()) facts.add(s.lifespan());
        facts.add(switch (s.sex()) {
            case "M" -> "Male";
            case "F" -> "Female";
            default -> "Sex unknown";
        });
        if (s.deceased() && s.died().isBlank()) facts.add("Deceased");
        facts.add(s.key());
        var line = Ui.label(String.join("   ·   ", facts));
        line.setFont(Ui.font(1, Font.PLAIN));
        line.setForeground(Ui.muted());
        text.add(line);

        var chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        chips.setOpaque(false);
        for (var n : p.nicknames()) chips.add(Ui.chip("“" + n + "”"));
        for (var o : p.otherNames()) chips.add(Ui.chip((o.type().isBlank() ? "also" : o.type()) + "  " + o.name()));
        if (chips.getComponentCount() > 0) {
            var wrap = Ui.transparent(new BorderLayout());
            wrap.setBorder(BorderFactory.createEmptyBorder(0, -6, 0, 0));
            wrap.add(chips);
            text.add(wrap);
        }
        var buttons = Ui.row(8,
                Ui.button("Edit", Icons.edit(14), () -> actions.editPerson(s.key())),
                Ui.button("Add relative", Icons.userPlus(14), () -> actions.addRelative(s.key(), null)),
                Ui.button("Ancestors", Icons.ancestors(14), () -> actions.go(new Place.Ancestors(s.key(), 4))),
                Ui.button("Descendants", Icons.descendants(14), () -> actions.go(new Place.Descendants(s.key(), 3))));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        text.add(buttons);
        var top = Ui.transparent(new BorderLayout());
        top.add(text, BorderLayout.NORTH);
        h.add(top, BorderLayout.CENTER);
        return h;
    }

    /// The name with its suffix ("Jr."): the daemon's `name` carries it already;
    /// one that doesn't gets it added.
    static String headerName(Model.PersonSummary s, String suffix) {
        var name = s.displayName();
        return suffix.isBlank() || name.endsWith(suffix) ? name : name + " " + suffix;
    }

    // ---- life -------------------------------------------------------------------

    private static JComponent timeline(Model.Person p) {
        var list = Ui.stack(0);
        var events = p.events();
        if (events.isEmpty()) {
            list.add(Ui.mutedLabel("No events recorded yet."));
            return list;
        }
        Integer born = events.stream().filter(e -> e.tag().equals("BIRT") && e.year() != null).map(Model.Event::year).findFirst().orElse(null);
        for (int i = 0; i < events.size(); i++) {
            list.add(new TimelineRow(events.get(i), born, i == 0, i == events.size() - 1));
        }
        return list;
    }

    /// One event: its year on the left, a dot on the rail, and what and where.
    static final class TimelineRow extends JPanel {
        private final boolean first, last;
        private static final int YEAR_W = 46, RAIL_W = 22;

        TimelineRow(Model.Event e, Integer born, boolean first, boolean last) {
            super(new BorderLayout(0, 0));
            this.first = first;
            this.last = last;
            setOpaque(false);
            var year = Ui.label(e.year() == null ? "" : String.valueOf(e.year()));
            year.setFont(Ui.font(0, Font.BOLD));
            year.setForeground(Ui.muted());
            year.setPreferredSize(new Dimension(YEAR_W, 18));
            year.setVerticalAlignment(JLabel.TOP);
            year.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 0));
            var west = Ui.transparent(new BorderLayout());
            west.add(year, BorderLayout.WEST);
            west.add(Box.createHorizontalStrut(RAIL_W), BorderLayout.EAST);
            add(west, BorderLayout.WEST);

            var body = Ui.stack(2);
            body.setBorder(BorderFactory.createEmptyBorder(0, 0, last ? 0 : 14, 0));
            var what = new StringBuilder(e.label());
            if (!e.value().isBlank()) what.append(": ").append(e.value());
            if (!e.type().isBlank() && !e.type().equalsIgnoreCase(e.label())) what.append(" (").append(e.type()).append(")");
            var title = Ui.label(what.toString());
            title.setFont(Ui.font(0, Font.BOLD));
            var titleRow = Ui.transparent(new BorderLayout(8, 0));
            titleRow.add(title, BorderLayout.CENTER);
            var age = age(e, born);
            if (age != null) titleRow.add(Ui.small(age), BorderLayout.EAST);
            body.add(titleRow);
            var detail = new ArrayList<String>();
            if (!e.date().isBlank()) detail.add(e.date());
            if (!e.place().isBlank()) detail.add(e.place());
            if (!detail.isEmpty()) {
                var d = Ui.paragraph(String.join("  ·  ", detail));
                d.setForeground(Ui.muted());
                body.add(d);
            }
            add(body, BorderLayout.CENTER);
        }

        static String age(Model.Event e, Integer born) {
            if (born == null || e.year() == null || e.tag().equals("BIRT")) return null;
            int a = e.year() - born;
            if (a <= 0 || a > 130) return null;
            return (e.tag().equals("DEAT") ? "aged " : "age ") + a;
        }

        @Override
        protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float cx = YEAR_W + RAIL_W / 2f - 3;
            float cy = 9;
            g2.setColor(Ui.mix(Ui.card(), Ui.foreground(), 0.2));
            g2.setStroke(new BasicStroke(2f));
            if (!first) g2.drawLine((int) cx, 0, (int) cx, (int) cy);
            if (!last) g2.drawLine((int) cx, (int) cy, (int) cx, getHeight());
            g2.setColor(Ui.pageBackground());
            g2.fill(new Ellipse2D.Float(cx - 6, cy - 6, 12, 12));
            g2.setColor(Ui.accent());
            g2.setStroke(new BasicStroke(2.4f));
            g2.draw(new Ellipse2D.Float(cx - 4.5f, cy - 4.5f, 9, 9));
            g2.dispose();
        }
    }

    // ---- family -----------------------------------------------------------------

    private static JComponent parents(Model.Person p, Actions actions) {
        var s = p.summary();
        var body = Ui.stack(6);
        if (p.parents().isEmpty()) {
            var both = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
            both.setOpaque(false);
            both.add(PersonChip.placeholder("Add father", () -> actions.addRelative(s.key(), "father")));
            both.add(PersonChip.placeholder("Add mother", () -> actions.addRelative(s.key(), "mother")));
            body.add(both);
            return Ui.section("Parents", Icons.users(14), body);
        }
        for (var pf : p.parents()) {
            var pair = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
            pair.setOpaque(false);
            pair.add(pf.father() != null
                    ? new PersonChip(actions, pf.father(), "Father", p, new Model.Relative(pf.father(), "Father", "father"))
                    : PersonChip.placeholder("Add father", () -> actions.addRelative(s.key(), "father")));
            pair.add(pf.mother() != null
                    ? new PersonChip(actions, pf.mother(), "Mother", p, new Model.Relative(pf.mother(), "Mother", "mother"))
                    : PersonChip.placeholder("Add mother", () -> actions.addRelative(s.key(), "mother")));
            body.add(pair);
            if (!pf.siblings().isEmpty()) {
                var sh = Ui.overline(pf.siblings().size() == 1 ? "Sibling" : "Siblings");
                sh.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 0));
                body.add(sh);
                body.add(PersonChip.grid(actions, pf.siblings(), 2, x -> null, p,
                        x -> new Model.Relative(x, "Sibling", "siblings/" + x.key())));
            }
            var links = Ui.row(14,
                    Ui.link("Family " + pf.family(), () -> actions.go(new Place.FamilyPage(pf.family()))),
                    Ui.link("Add sibling", () -> actions.addRelative(s.key(), "sibling")));
            links.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 0));
            body.add(links);
        }
        return Ui.section(p.parents().size() > 1 ? "Parents (" + p.parents().size() + " families)" : "Parents", Icons.users(14), body);
    }

    private static JComponent family(Model.Person p, Model.OwnFamily f, Actions actions) {
        var body = Ui.stack(6);
        if (f.partner() != null) {
            var role = switch (f.role()) {
                case "husband" -> f.partner().sex().equals("M") ? "Partner" : "Wife";
                case "wife" -> f.partner().sex().equals("F") ? "Partner" : "Husband";
                default -> "Partner";
            };
            body.add(new PersonChip(actions, f.partner(), role, p,
                    new Model.Relative(f.partner(), "Spouse", "spouses/" + f.partner().key()), 40));
        } else {
            body.add(PersonChip.placeholder("Add partner", () -> actions.addRelative(p.key(), "spouse")));
        }
        var m = f.married();
        var marriage = new ArrayList<String>();
        if (m != null) {
            marriage.add(m.label().isBlank() ? "Married" : m.label());
            if (!m.date().isBlank()) marriage.add(m.date());
            if (!m.place().isBlank()) marriage.add(m.place());
        }
        if (f.divorced()) marriage.add("Divorced");
        if (!marriage.isEmpty()) {
            var ml = Ui.label(String.join("  ·  ", marriage));
            ml.setIcon(Icons.tinted(Icons.calendar(14), Ui.muted()));
            ml.setIconTextGap(8);
            ml.setForeground(Ui.muted());
            ml.setBorder(BorderFactory.createEmptyBorder(2, 10, 4, 0));
            body.add(ml);
        }
        if (!f.children().isEmpty()) {
            var ch = Ui.overline(f.children().size() == 1 ? "Child" : f.children().size() + " children");
            ch.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 0));
            body.add(ch);
            body.add(PersonChip.grid(actions, f.children(), 2, x -> null, p,
                    x -> new Model.Relative(x, "Child", "children/" + x.key())));
        }
        var links = Ui.row(14,
                Ui.link("Family " + f.family(), () -> actions.go(new Place.FamilyPage(f.family()))),
                Ui.link("Add child", () -> actions.addRelative(p.key(), "child:" + f.family())),
                Ui.link("Edit family", () -> actions.editFamily(f.family())));
        links.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 0));
        body.add(links);
        var title = f.partner() == null ? "Family " + f.family() : "Family with " + f.partner().given();
        return Ui.section(title, Icons.family(14), body);
    }

    static String hiddenLabel(int n) {
        return n == 1 ? "Show 1 hidden family" : "Show " + n + " hidden families";
    }

    private static JComponent addFamilyRow(Model.Person p, Actions actions) {
        var row = Ui.row(14,
                Ui.link(p.families().isEmpty() ? "Add a partner" : "Add another partner", () -> actions.addRelative(p.key(), "spouse")),
                Ui.link("Link an existing person…", () -> actions.linkExisting(p.key())));
        row.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        return row;
    }

    // ---- notes, records, photos -------------------------------------------------

    private static JComponent notes(List<String> notes) {
        var s = Ui.stack(12);
        for (var n : notes) s.add(Ui.paragraph(n));
        return s;
    }

    static JComponent records(List<Model.RecordRef> refs, Actions actions) {
        var s = Ui.stack(6);
        for (var r : refs) {
            var link = Ui.link(r.title().isBlank() ? r.key() : r.title(), () -> actions.go(new Place.RecordPage(r.key())));
            link.setIcon(Icons.tinted(Icons.forKind(r.kind(), 14), Ui.muted()));
            link.setIconTextGap(8);
            var row = Ui.transparent(new BorderLayout(8, 0));
            row.add(link, BorderLayout.CENTER);
            row.add(Ui.small(Place.Kinds.singular(r.kind()) + " " + r.key()), BorderLayout.EAST);
            s.add(row);
        }
        return s;
    }

    private static JComponent photoStrip(Model.Person p, Actions actions) {
        var strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        strip.setOpaque(false);
        var photos = p.photos();
        for (int i = 0; i < photos.size(); i++) {
            var ph = photos.get(i);
            int index = i;
            var cell = Ui.stack(6);
            var thumb = new Avatar.Portrait(actions.images(), p.summary(), ph.url(), ph.crop(), 120, 120);
            thumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            thumb.setToolTipText(ph.file());
            thumb.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    actions.showPhotos(photos, index, p.summary().displayName());
                }
            });
            cell.add(thumb);
            var cap = Ui.small(caption(ph));
            cap.setPreferredSize(new Dimension(120, cap.getPreferredSize().height));
            cell.add(cap);
            strip.add(cell);
        }
        var wrap = Ui.transparent(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(0, -12, 0, 0));
        wrap.add(strip);
        return wrap;
    }

    static String caption(Model.Photo ph) {
        var t = ph.title().isBlank() ? fileName(ph.file()) : ph.title();
        return ph.url() == null ? t + " (missing)" : t;
    }

    static String fileName(String path) {
        var p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private PersonView() {
    }
}
