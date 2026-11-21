package ged.desktop;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;

/// The family page: the two partners side by side, the family's events,
/// its children, notes and sources.
final class FamilyView {

    static JComponent build(Model.Family f, Actions actions) {
        var page = Ui.stack(22);

        var head = Ui.stack(6);
        var kicker = Ui.overline("Family " + f.key());
        kicker.setIcon(Icons.tinted(Icons.family(14), Ui.muted()));
        kicker.setIconTextGap(6);
        head.add(kicker);
        head.add(Ui.title(f.title().isBlank() ? "Family " + f.key() : f.title()));
        var facts = new ArrayList<String>();
        if (!f.married().isBlank()) facts.add("Married " + f.married());
        facts.add(f.children().isEmpty() ? "No children" : f.children().size() == 1 ? "1 child" : f.children().size() + " children");
        if ("yes".equals(f.fields().get("divorced"))) facts.add("Divorced");
        var line = Ui.label(String.join("   ·   ", facts));
        line.setFont(Ui.font(1, Font.PLAIN));
        line.setForeground(Ui.muted());
        head.add(line);
        var buttons = Ui.row(8, Ui.button("Edit family", Icons.edit(14), () -> actions.editFamily(f.key())));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        head.add(buttons);
        page.add(head);

        var partners = new JPanel(new GridLayout(1, 2, 16, 0));
        partners.setOpaque(false);
        partners.add(partner("Husband", f.husband(), actions));
        partners.add(partner("Wife", f.wife(), actions));
        page.add(partners);

        var left = Ui.stack(16);
        left.add(Ui.section("Events", Icons.calendar(14), events(f)));
        if (!f.notes().isEmpty()) {
            var notes = Ui.stack(12);
            for (var n : f.notes()) notes.add(Ui.paragraph(n));
            left.add(Ui.section("Notes", Icons.note(14), notes));
        }
        if (!f.records().isEmpty()) left.add(Ui.section("Sources and records", Icons.source(14), PersonView.records(f.records(), actions)));

        var right = Ui.stack(16);
        var kids = Ui.stack(6);
        if (f.children().isEmpty()) {
            kids.add(Ui.mutedLabel("No children recorded."));
        } else {
            kids.add(PersonChip.grid(actions, f.children(), 1, x -> null, null, x -> null));
        }
        var anchor = f.husband() != null ? f.husband() : f.wife();
        if (anchor != null) {
            var add = Ui.row(14, Ui.link("Add child", () -> actions.addRelative(anchor.key(), "child:" + f.key())));
            add.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 0));
            kids.add(add);
        }
        right.add(Ui.section(f.children().size() == 1 ? "Child" : "Children", Icons.users(14), kids));

        page.add(new Ui.Columns(left, right, 0.5, 16, 720));
        return new Ui.Page(page, 1040);
    }

    private static JComponent partner(String role, Model.PersonSummary p, Actions actions) {
        var card = new Ui.Card(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        if (p == null) {
            var none = Ui.mutedLabel("No " + role.toLowerCase(java.util.Locale.ROOT) + " recorded");
            none.setBorder(BorderFactory.createEmptyBorder(14, 10, 14, 10));
            card.add(none);
        } else {
            card.add(new PersonChip(actions, p, role, null, null, 52));
        }
        return card;
    }

    private static JComponent events(Model.Family f) {
        var s = Ui.stack(0);
        if (f.events().isEmpty()) {
            s.add(Ui.mutedLabel("No events recorded yet."));
            return s;
        }
        for (int i = 0; i < f.events().size(); i++) {
            s.add(new PersonView.TimelineRow(f.events().get(i), null, i == 0, i == f.events().size() - 1));
        }
        return s;
    }

    private FamilyView() {
    }
}
