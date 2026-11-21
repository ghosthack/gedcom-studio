package ged.desktop;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/// The dialogs that change who is related to whom: add a new relative, and
/// link an existing person. Both run the shell's own `add` and `link`, so
/// their rules and messages are the shell's; a refusal is shown in the dialog.
final class RelativeDialogs {

    record Relation(String code, String label) {
    }

    static final List<Relation> RELATIONS = List.of(
            new Relation("child", "Child"), new Relation("spouse", "Partner"), new Relation("father", "Father"),
            new Relation("mother", "Mother"), new Relation("sibling", "Sibling"));

    /// The name as the API wants it: the surname between slashes.
    static String gedcomName(String given, String surname) {
        var g = given.strip();
        var s = surname.strip();
        if (s.isEmpty()) return g + " //";
        return (g.isEmpty() ? "" : g + " ") + "/" + s + "/";
    }

    /// The sex a new relative most likely has.
    static String likelySex(String relation, String personSex) {
        return switch (relation) {
            case "father" -> "M";
            case "mother" -> "F";
            case "spouse" -> "M".equals(personSex) ? "F" : "F".equals(personSex) ? "M" : "U";
            default -> "U";
        };
    }

    /// The surname a new relative most likely has: a father's, sibling's or
    /// child's follows the family line; partners and mothers start empty.
    static String likelySurname(String relation, Model.Person p, String family) {
        var own = p.summary().surname();
        return switch (relation) {
            case "father", "sibling" -> own;
            case "child" -> {
                if ("M".equals(p.summary().sex())) yield own;
                var f = p.families().stream().filter(x -> family == null || x.family().equals(family)).findFirst();
                yield f.map(x -> x.partner() != null && "M".equals(x.partner().sex()) ? x.partner().surname() : own).orElse(own);
            }
            default -> "";
        };
    }

    // ---- add a relative -----------------------------------------------------------

    static final class AddRelative extends JDialog {
        interface Adder {
            Model.Outcome add(String relation, String name, String sex, java.util.Map<String, String> fields, String family) throws Exception;
        }

        private final Model.Person person;
        private final Ui.Banner banner = new Ui.Banner();
        private final JTextField given = new JTextField(16);
        private final JTextField surname = new JTextField(14);
        private final JComboBox<String> sex = new JComboBox<>(new String[] {"Male", "Female", "Unknown"});
        private final JTextField born = new JTextField(12);
        private final JTextField place = new JTextField(16);
        private final JComboBox<Object> family = new JComboBox<>();
        private final JLabel familyLabel = Ui.label("Family");
        private final JButton add = new JButton("Add");
        private String relation;
        private boolean surnameTouched;

        AddRelative(Window owner, Images images, Model.Person person, String preset, String datesHint, Adder adder,
                    Consumer<Model.Outcome> done) {
            super(owner, person == null ? "Add a person" : "Add a relative", ModalityType.DOCUMENT_MODAL);
            this.person = person;
            String presetFamily = null;
            if (preset != null && preset.startsWith("child:")) {
                presetFamily = preset.substring(6);
                preset = "child";
            }
            relation = preset == null ? "child" : preset;

            var root = new JPanel(new BorderLayout(0, 14));
            root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
            var head = Ui.transparent(new BorderLayout(14, 0));
            head.add(person == null ? new JLabel(Icons.tinted(Icons.userPlus(32), Ui.accent()))
                    : new JLabel(new Avatar(images, person.summary(), 44)), BorderLayout.WEST);
            var ht = Ui.stack(3);
            var title = Ui.heading(person == null ? "Add a person" : "Add a relative of " + person.summary().displayName());
            title.setFont(Ui.font(5, Font.BOLD));
            ht.add(title);
            ht.add(Ui.mutedLabel(person == null ? "A new person, not linked to anyone yet. Link relatives from their page."
                    : "A new person, linked to " + person.summary().given() + " in the right family."));
            head.add(ht, BorderLayout.CENTER);
            var north = Ui.stack(12);
            north.add(head);
            north.add(banner);
            root.add(north, BorderLayout.NORTH);

            var form = Ui.transparent(new GridBagLayout());
            int row = 0;
            var seg = Ui.row(4);
            var group = new ButtonGroup();
            for (var r : RELATIONS) {
                var b = new JToggleButton(r.label(), r.code().equals(relation));
                b.putClientProperty(FlatClientProperties.STYLE, "selectedBackground: $Component.accentColor; selectedForeground: #fff");
                b.setFocusable(false);
                b.addActionListener(e -> setRelation(r.code()));
                group.add(b);
                seg.add(b);
            }
            if (person != null) row = addRow(form, row, "Relation", seg);
            for (var f : person == null ? List.<Model.OwnFamily>of() : person.families()) {
                family.addItem(new FamilyChoice(f.family(), f.partner() == null
                        ? "Family " + f.family() + " (no partner)"
                        : "With " + f.partner().displayName() + " (" + f.family() + ")"));
            }
            family.addItem(new FamilyChoice(null, "A new family (partner unknown)"));
            if (presetFamily != null) {
                for (int i = 0; i < family.getItemCount(); i++) {
                    if (family.getItemAt(i) instanceof FamilyChoice fc && presetFamily.equals(fc.key())) family.setSelectedIndex(i);
                }
            }
            family.addActionListener(e -> suggest());
            familyLabel.setForeground(Ui.muted());
            form.add(familyLabel, labelConstraints(row));
            form.add(family, fieldConstraints(row++));
            given.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Given names");
            surname.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Surname");
            surname.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { if (surname.hasFocus()) surnameTouched = true; }
                @Override public void removeUpdate(DocumentEvent e) { if (surname.hasFocus()) surnameTouched = true; }
                @Override public void changedUpdate(DocumentEvent e) { }
            });
            var names = Ui.transparent(new GridBagLayout());
            names.add(given, new GridBagConstraints(0, 0, 1, 1, 0.55, 0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 8), 0, 0));
            names.add(surname, new GridBagConstraints(1, 0, 1, 1, 0.45, 0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 0), 0, 0));
            row = addRow(form, row, "Name", names);
            var sexRow = Ui.transparent(new BorderLayout());
            sexRow.add(sex, BorderLayout.WEST);
            row = addRow(form, row, "Sex", sexRow);
            born.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Date");
            born.setToolTipText("<html>Dates as GEDCOM writes them:<br>" + datesHint.replace("&", "&amp;").replace("<", "&lt;") + "</html>");
            place.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Birth place");
            var birth = Ui.transparent(new GridBagLayout());
            born.setPreferredSize(new Dimension(170, born.getPreferredSize().height));
            birth.add(born, new GridBagConstraints(0, 0, 1, 1, 0, 0, GridBagConstraints.WEST, GridBagConstraints.NONE, new Insets(0, 0, 0, 8), 0, 0));
            birth.add(place, new GridBagConstraints(1, 0, 1, 1, 1, 0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 0), 0, 0));
            addRow(form, row, "Born", birth);
            root.add(form, BorderLayout.CENTER);

            var cancel = new JButton("Cancel");
            cancel.addActionListener(e -> dispose());
            add.addActionListener(e -> {
                var fields = new LinkedHashMap<String, String>();
                if (!born.getText().isBlank()) fields.put("birth.date", born.getText().strip());
                if (!place.getText().isBlank()) fields.put("birth.place", place.getText().strip());
                if (given.getText().isBlank() && surname.getText().isBlank()) {
                    banner.show(Ui.Banner.Kind.ERROR, "Give the new person a name (a given name, a surname, or both).");
                    given.requestFocusInWindow();
                    return;
                }
                var fam = relation.equals("child") && family.isVisible() && family.getSelectedItem() instanceof FamilyChoice fc ? fc.key() : null;
                var name = gedcomName(given.getText(), surname.getText());
                var s = new String[] {"M", "F", "U"}[sex.getSelectedIndex()];
                add.setEnabled(false);
                add.setText("Adding…");
                Bg.run(() -> adder.add(relation, name, s, fields, fam), outcome -> {
                    dispose();
                    done.accept(outcome);
                }, err -> {
                    add.setEnabled(true);
                    add.setText("Add");
                    banner.show(Ui.Banner.Kind.ERROR, Api.message(err));
                    pack();
                });
            });
            var buttons = Ui.transparent(new BorderLayout());
            buttons.add(Ui.row(8, cancel, add), BorderLayout.EAST);
            root.add(buttons, BorderLayout.SOUTH);
            getRootPane().setDefaultButton(add);
            escapeCloses(this);
            setContentPane(root);
            setRelation(relation);
            pack();
            setSize(Math.max(600, getWidth()), getHeight());
            setLocationRelativeTo(owner);
        }

        private record FamilyChoice(String key, String label) {
            @Override
            public String toString() {
                return label;
            }
        }

        private void setRelation(String r) {
            relation = r;
            if (person == null) {
                family.setVisible(false);
                familyLabel.setVisible(false);
                sex.setSelectedIndex(2);
                return;
            }
            boolean choose = r.equals("child") && !person.families().isEmpty() && person.families().size() > 1;
            family.setVisible(choose);
            familyLabel.setVisible(choose);
            sex.setSelectedIndex(List.of("M", "F", "U").indexOf(likelySex(r, person.summary().sex())));
            suggest();
            getContentPane().revalidate();
            pack();
        }

        private void suggest() {
            if (surnameTouched || person == null) return;
            var fam = family.getSelectedItem() instanceof FamilyChoice fc ? fc.key() : null;
            surname.setText(likelySurname(relation, person, fam));
        }
    }

    // ---- link an existing person -------------------------------------------------------

    static final class Link extends JDialog {
        interface Linker {
            Model.Outcome link(String person, String as) throws Exception;
        }

        interface Search {
            Model.People search(String q) throws Exception;
        }

        private record As(String code, String label) {
            @Override
            public String toString() {
                return label;
            }
        }

        private final Ui.Banner banner = new Ui.Banner();
        private final DefaultListModel<Model.PersonSummary> model = new DefaultListModel<>();
        private final JList<Model.PersonSummary> list = new JList<>(model);
        private final JTextField search = new JTextField(24);
        private final JComboBox<As> as = new JComboBox<>();
        private final JButton link = new JButton("Link");
        private int generation;

        Link(Window owner, Images images, Model.Person person, Search searcher, Linker linker, Consumer<Model.Outcome> done) {
            super(owner, "Link an existing person", ModalityType.DOCUMENT_MODAL);
            var root = new JPanel(new BorderLayout(0, 12));
            root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
            var head = Ui.stack(3);
            var title = Ui.heading("Link someone to " + person.summary().displayName());
            title.setFont(Ui.font(5, Font.BOLD));
            head.add(title);
            head.add(Ui.mutedLabel("Choose a person already in the file and how they are related."));
            head.add(banner);
            root.add(head, BorderLayout.NORTH);

            search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search people");
            search.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.search(14));
            search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
            var timer = new Timer(200, e -> runSearch(searcher, person.key()));
            timer.setRepeats(false);
            search.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { timer.restart(); }
                @Override public void removeUpdate(DocumentEvent e) { timer.restart(); }
                @Override public void changedUpdate(DocumentEvent e) { timer.restart(); }
            });
            list.setCellRenderer(new PersonCellRenderer(images));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setFixedCellHeight(46);
            list.putClientProperty(FlatClientProperties.STYLE, "selectionBackground: null; selectionInactiveBackground: null");
            list.addListSelectionListener(e -> link.setEnabled(list.getSelectedValue() != null));
            var scroll = new JScrollPane(list);
            scroll.setPreferredSize(new Dimension(460, 300));
            var center = Ui.transparent(new BorderLayout(0, 8));
            center.add(search, BorderLayout.NORTH);
            center.add(scroll, BorderLayout.CENTER);
            var asRow = Ui.transparent(new BorderLayout(10, 0));
            asRow.add(Ui.mutedLabel("Link as"), BorderLayout.WEST);
            as.addItem(new As("children", "Child"));
            for (var f : person.families()) {
                if (person.families().size() > 1) {
                    as.addItem(new As("families/" + f.family() + "/children", "Child in the family "
                            + (f.partner() == null ? f.family() : "with " + f.partner().displayName())));
                }
            }
            as.addItem(new As("spouses", "Partner"));
            as.addItem(new As("father", "Father"));
            as.addItem(new As("mother", "Mother"));
            as.addItem(new As("siblings", "Sibling"));
            asRow.add(as, BorderLayout.CENTER);
            center.add(asRow, BorderLayout.SOUTH);
            root.add(center, BorderLayout.CENTER);

            var cancel = new JButton("Cancel");
            cancel.addActionListener(e -> dispose());
            link.setEnabled(false);
            link.addActionListener(e -> {
                var p = list.getSelectedValue();
                if (p == null) return;
                var code = ((As) as.getSelectedItem()).code();
                link.setEnabled(false);
                Bg.run(() -> linker.link(p.key(), code), outcome -> {
                    dispose();
                    done.accept(outcome);
                }, err -> {
                    link.setEnabled(true);
                    banner.show(Ui.Banner.Kind.ERROR, Api.message(err));
                    pack();
                });
            });
            var buttons = Ui.transparent(new BorderLayout());
            buttons.add(Ui.row(8, cancel, link), BorderLayout.EAST);
            root.add(buttons, BorderLayout.SOUTH);
            getRootPane().setDefaultButton(link);
            escapeCloses(this);
            setContentPane(root);
            pack();
            setLocationRelativeTo(owner);
            runSearch(searcher, person.key());
        }

        private void runSearch(Search searcher, String self) {
            int gen = ++generation;
            var q = search.getText();
            Bg.run(() -> searcher.search(q), people -> {
                if (gen != generation) return;
                model.clear();
                people.people().stream().filter(p -> !p.key().equalsIgnoreCase(self)).limit(300).forEach(model::addElement);
            }, err -> banner.show(Ui.Banner.Kind.ERROR, Api.message(err)));
        }
    }

    // ---- helpers ------------------------------------------------------------------

    private static int addRow(JPanel form, int row, String label, JComponent field) {
        var l = Ui.label(label);
        l.setForeground(Ui.muted());
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        form.add(l, labelConstraints(row));
        form.add(field, fieldConstraints(row));
        return row + 1;
    }

    private static GridBagConstraints labelConstraints(int row) {
        return new GridBagConstraints(0, row, 1, 1, 0, 0, GridBagConstraints.EAST, GridBagConstraints.NONE, new Insets(5, 0, 5, 12), 0, 0);
    }

    private static GridBagConstraints fieldConstraints(int row) {
        return new GridBagConstraints(1, row, 1, 1, 1, 0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, new Insets(5, 0, 5, 0), 0, 0);
    }

    static void escapeCloses(JDialog d) {
        d.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        d.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                d.dispose();
            }
        });
    }

    /// The words of the unlink confirmation.
    static String unlinkQuestion(Model.PersonSummary person, Model.Relative r) {
        var who = r.person().displayName();
        var role = r.role().toLowerCase(java.util.Locale.ROOT);
        var relation = switch (r.role()) {
            case "Spouse" -> "partner";
            default -> role;
        };
        return who + " will no longer be linked as " + person.displayName() + "’s " + relation + ".\n\n"
                + "Nobody is deleted: both stay in the file with everything recorded about them. "
                + "This only takes " + who + " out of the family that connects them; a family left connecting nobody is removed. "
                + "You can link them again at any time.";
    }

    private RelativeDialogs() {
    }
}
