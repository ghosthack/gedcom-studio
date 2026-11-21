package ged.desktop;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/// The edit form for a person or a family: everything their page shows.
///
/// - **Name** (a person): given names, surname, suffix and sex from `GET
///   fields`, further names (given, surname, type) and nicknames;
/// - **Events**: one row per event, in the page's (date) order, each with
///   its type, date, place and, for facts, its value; rows can be removed
///   and "Add event…" appends one of the tags the record can have;
/// - **Notes**: each note as text; a shared note is only shown, and can be
///   unlinked.
///
/// Saving sends one `edit` with the changed name fields (`set`/`seen`) and
/// only the changed items of each list, each with the values the form
/// showed. A refused value (`422`) is shown at its row; a conflict (`409`)
/// moves the form onto the latest version, keeping the user's own changes
/// and marking what changed meanwhile, so the user can look and save again.
/// When a list changed shape meanwhile (an item added or removed elsewhere),
/// that list is reloaded instead, and the banner says so.
final class EditDialog extends JDialog {

    /// Sends one save: the request body → the reply.
    interface Saver {
        Map<String, Object> save(Map<String, Object> body) throws IOException;
    }

    /// What the form edits, read from a `PERSON` or a `FAMILY`. `names` and
    /// `nicknames` are null for a family.
    record Content(Map<String, String> fields, List<Model.Event> events, List<Model.NoteItem> notes,
                   List<Model.OtherName> names, List<String> nicknames) {
        static Content of(Model.Person p) {
            return new Content(p.fields(), p.events(), p.noteItems(), p.otherNames(), p.nicknames());
        }

        static Content of(Model.Family f) {
            return new Content(f.fields(), f.events(), f.noteItems(), null, null);
        }

        /// The latest version in a reply (`person` or `family`).
        static Content from(String replyKey, Map<String, Object> m) {
            return replyKey.equals("family") ? of(Model.Family.from(m)) : of(Model.Person.from(m));
        }
    }

    /// A name field's editor: reads and writes the value as the API spells it.
    private record Editor(Model.FieldDef def, JComponent component, Supplier<String> get, Consumer<String> set) {
    }

    /// One editable part of an item (an event's date): the text it is typed
    /// in, and the component laid out (a note's text sits in a scroll pane).
    private record Part(JTextComponent text, JComponent shown) {
        String get() {
            return text.getText();
        }

        void set(String v) {
            text.setText(v);
            text.setCaretPosition(0);
        }
    }

    private enum ListKind {
        EVENTS("events"), NOTES("notes"), NAMES("names");

        final String key;

        ListKind(String key) {
            this.key = key;
        }
    }

    /// An event, a note or a further name, as a row of the form. `ref` is
    /// null for an item added here; `session` holds what the form showed.
    private final class Item {
        final ListKind kind;
        final String tag;
        final String label;
        String ref;
        boolean details;
        String shared;
        EditSession session;
        final Map<String, Part> parts = new LinkedHashMap<>();
        final JPanel row = Ui.transparent(new GridBagLayout());
        JButton remove;
        /// The "+ Type" button while the type field is hidden.
        JButton revealType;
        String name;
        boolean removed;

        Item(ListKind kind, String tag, String ref, Map<String, String> seen) {
            this.kind = kind;
            this.tag = tag;
            this.ref = ref;
            this.label = switch (kind) {
                case EVENTS -> EventKinds.label(tag);
                case NOTES -> "Note";
                case NAMES -> "Other name";
            };
            this.session = new EditSession(seen);
        }

        Map<String, String> values() {
            var out = new LinkedHashMap<String, String>();
            parts.forEach((k, p) -> out.put(k, p.get()));
            return out;
        }

        boolean isNew() {
            return ref == null;
        }

        boolean changed() {
            if (removed) return !isNew();
            if (isNew()) return kind == ListKind.EVENTS || values().values().stream().anyMatch(v -> !v.isBlank());
            return !session.changes(values()).isEmpty();
        }

        /// This item's entry in the request, or null when there is nothing to send.
        Map<String, Object> request() {
            if (removed) {
                if (isNew()) return null;
                var m = new LinkedHashMap<String, Object>();
                m.put("ref", ref);
                m.put("seen", session.seen());
                m.put("remove", true);
                return m;
            }
            if (isNew()) {
                var set = new LinkedHashMap<String, String>();
                values().forEach((k, v) -> {
                    if (!v.isBlank()) set.put(k, kind == ListKind.NOTES ? v : v.strip());
                });
                // an event with nothing in it still says it happened (`1 DEAT Y`)
                if (set.isEmpty() && kind != ListKind.EVENTS) return null;
                var m = new LinkedHashMap<String, Object>();
                m.put("add", kind == ListKind.EVENTS ? tag : "");
                m.put("set", set);
                return m;
            }
            var r = session.request(values());
            if (r.isEmpty()) return null;
            var m = new LinkedHashMap<String, Object>();
            m.put("ref", ref);
            m.put("seen", r.seen());
            m.put("set", kind == ListKind.NOTES ? Map.of("text", parts.get("text").get()) : r.set());
            return m;
        }

        void mark(String part, String outline) {
            var p = parts.get(part);
            if (p == null) return;
            if (outline != null && part.equals("type") && revealType != null) revealType.doClick();
            p.shown().putClientProperty(FlatClientProperties.OUTLINE, outline);
            p.shown().repaint();
        }

        void unmark() {
            parts.keySet().forEach(k -> mark(k, null));
        }

        /// The part to focus first: the type an event needs, a fact's value, else the date.
        Part first() {
            if (kind != ListKind.EVENTS) return parts.values().iterator().next();
            if (EventKinds.NEED_TYPE.contains(tag)) return parts.get("type");
            if (EventKinds.attribute(tag)) return parts.get("value");
            return parts.get("date");
        }
    }

    private static final int DATE_W = 130;
    /// The type/value column's starting width; it grows with the place, the date doesn't.
    private static final int TYPE_W = 140;
    private static final int TYPE_NAME_W = 110;

    private final String noun;
    private final String replyKey;
    private final Saver saver;
    private final Consumer<Map<String, Object>> onSaved;
    private final Consumer<String> openRecord;
    private final EditSession session;
    private final List<Model.FieldDef> nameDefs;
    private final List<EventKinds.Group> addable;
    private final Map<String, Editor> editors = new LinkedHashMap<>();
    private final Map<ListKind, List<Item>> items = new LinkedHashMap<>();
    private final Map<ListKind, JPanel> lists = new LinkedHashMap<>();
    private List<String> seenNicknames;
    private JTextField nicknames;
    private final Ui.Banner banner = new Ui.Banner();
    private final JButton save = new JButton("Save");
    private final JButton cancel = new JButton("Cancel");
    private final JButton addEvent = new JButton("Add event…", Icons.plus(14));
    private final JPanel form = Ui.stack(0);
    private final String datesHint;
    private final int labelWidth;
    private boolean busy;
    /// The version of the record the form shows (the latest after a conflict).
    private Content shown;

    /// @param noun       "person" or "family", for messages
    /// @param replyKey   where the reply carries the latest record: `person` or `family`
    /// @param defs       the field definitions; the form uses the `name` group's
    /// @param openRecord opens a record's page (a shared note's), or null
    EditDialog(Window owner, String title, String subtitle, JComponent face, String noun, String replyKey,
               List<Model.FieldDef> defs, String datesHint, Content content, Saver saver,
               Consumer<Map<String, Object>> onSaved, Consumer<String> openRecord) {
        super(owner, title, ModalityType.DOCUMENT_MODAL);
        this.noun = noun;
        this.replyKey = replyKey;
        this.saver = saver;
        this.onSaved = onSaved;
        this.openRecord = openRecord;
        this.datesHint = datesHint;
        this.shown = content;
        this.nameDefs = defs.stream().filter(d -> d.group().equals("name")).toList();
        var nameFields = new LinkedHashMap<String, String>();
        for (var d : nameDefs) nameFields.put(d.key(), content.fields().getOrDefault(d.key(), ""));
        this.session = new EditSession(nameFields);
        this.addable = replyKey.equals("family") ? EventKinds.FAMILY : EventKinds.PERSON;
        this.labelWidth = labelWidth(content);
        for (var k : ListKind.values()) {
            items.put(k, new ArrayList<>());
            var p = Ui.stack(0);
            lists.put(k, p);
        }

        var root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

        var head = Ui.transparent(new BorderLayout(14, 0));
        if (face != null) head.add(face, BorderLayout.WEST);
        var headText = Ui.stack(3);
        var t = Ui.heading(title);
        t.setFont(Ui.font(5, Font.BOLD));
        headText.add(t);
        headText.add(Ui.mutedLabel(subtitle));
        var headWrap = Ui.transparent(new GridBagLayout());
        headWrap.add(headText, new GridBagConstraints(0, 0, 1, 1, 1, 1, GridBagConstraints.WEST,
                GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 0), 0, 0));
        head.add(headWrap, BorderLayout.CENTER);
        var north = Ui.stack(12);
        north.add(head);
        north.add(banner);
        north.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        root.add(north, BorderLayout.NORTH);

        buildForm(content);
        var formWrap = Ui.transparent(new BorderLayout());
        formWrap.add(form, BorderLayout.NORTH);
        formWrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
        var scroll = new JScrollPane(formWrap, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        root.add(scroll, BorderLayout.CENTER);

        var buttons = Ui.transparent(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        buttons.add(Ui.row(8, cancel, save), BorderLayout.EAST);
        root.add(buttons, BorderLayout.SOUTH);

        save.addActionListener(e -> save());
        cancel.addActionListener(e -> cancel());
        getRootPane().setDefaultButton(save);
        var im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "save");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "save");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                cancel();
            }
        });
        getRootPane().getActionMap().put("save", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                save();
            }
        });
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cancel();
            }
        });
        setContentPane(root);
        pack();
        var screen = owner == null ? Toolkit.getDefaultToolkit().getScreenSize() : owner.getSize();
        setSize(new Dimension(Math.max(760, getWidth()), Math.min(getHeight(), Math.max(520, (int) (screen.height * 0.86)))));
        setLocationRelativeTo(owner);
    }

    /// Wide enough for every label the form may show, so the fields line up.
    private int labelWidth(Content content) {
        var fm = getFontMetrics(Ui.base());
        int w = fm.stringWidth("Other name");
        for (var d : nameDefs) w = Math.max(w, fm.stringWidth(d.label()));
        for (var e : content.events()) w = Math.max(w, fm.stringWidth(EventKinds.label(e.tag())));
        for (var g : addable) for (var tag : g.tags()) w = Math.max(w, fm.stringWidth(EventKinds.label(tag)));
        return Math.min(w + 4, 170);
    }

    // ---- the form -----------------------------------------------------------------

    private void buildForm(Content content) {
        if (content.names() != null) {
            form.add(header("Name", true));
            for (var d : nameDefs) form.add(fieldRow(d, content.fields()));
            var names = lists.get(ListKind.NAMES);
            form.add(names);
            for (var n : content.names()) addName(n.ref(), n.fields());
            form.add(addLink("Add other name", () -> focusNew(addName(null, Map.of()))));
            nicknames = new JTextField(String.join(", ", content.nicknames()), 20);
            seenNicknames = List.copyOf(content.nicknames());
            nicknames.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Separate several with commas");
            nicknames.getAccessibleContext().setAccessibleName("Nicknames");
            nicknames.setCaretPosition(0);
            var row = Ui.transparent(new GridBagLayout());
            row.add(rowLabel("Nicknames"), labelAt(0));
            row.add(nicknames, new GridBagConstraints(1, 0, 1, 1, 1, 0, GridBagConstraints.WEST,
                    GridBagConstraints.HORIZONTAL, new Insets(4, 0, 4, 0), 0, 0));
            form.add(row);
        }

        form.add(header("Events", content.names() == null));
        form.add(lists.get(ListKind.EVENTS));
        for (var e : content.events()) addEvent(e.tag(), e.ref(), e.fields(), e.details());
        addEvent.addActionListener(e -> showAddMenu());
        addEvent.getAccessibleContext().setAccessibleName("Add event");
        Ui.styleToolButton(addEvent, "Add an event or a fact");
        addEvent.setFocusable(true);
        form.add(indented(addEvent));

        form.add(header("Notes", false));
        form.add(lists.get(ListKind.NOTES));
        for (var n : content.notes()) addNote(n.ref(), n.text(), n.shared());
        form.add(addLink("Add note", () -> focusNew(addNote(null, "", null))));
        renameAll();
    }

    private static JLabel header(String title, boolean first) {
        var h = Ui.overline(title);
        h.setBorder(BorderFactory.createEmptyBorder(first ? 0 : 18, 0, 6, 0));
        return h;
    }

    private JComponent indented(JComponent c) {
        var row = Ui.transparent(new BorderLayout());
        row.setBorder(BorderFactory.createEmptyBorder(2, labelWidth + 12 - 6, 0, 0));
        row.add(c, BorderLayout.WEST);
        return row;
    }

    private JComponent addLink(String text, Runnable action) {
        var b = new JButton(text, Icons.plus(14));
        Ui.styleToolButton(b, null);
        b.setFocusable(true);
        b.addActionListener(e -> action.run());
        return indented(b);
    }

    private JLabel rowLabel(String text) {
        var l = Ui.label(text);
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        l.setForeground(Ui.muted());
        l.setPreferredSize(new Dimension(labelWidth, l.getPreferredSize().height));
        l.setToolTipText(text);
        return l;
    }

    private static GridBagConstraints labelAt(int row) {
        return new GridBagConstraints(0, row, 1, 1, 0, 0, GridBagConstraints.NORTHEAST,
                GridBagConstraints.NONE, new Insets(8, 0, 4, 12), 0, 0);
    }

    private static GridBagConstraints cell(int x, int y, int span, double weight, int width, Insets in) {
        var gc = new GridBagConstraints(x, y, span, 1, weight, 0, GridBagConstraints.NORTHWEST,
                weight > 0 || width < 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE, in, 0, 0);
        return gc;
    }

    private JComponent fieldRow(Model.FieldDef def, Map<String, String> fields) {
        var ed = editor(def, fields.getOrDefault(def.key(), ""));
        var row = Ui.transparent(new GridBagLayout());
        row.add(rowLabel(def.label()), labelAt(0));
        boolean grow = !def.kind().equals("sex");
        row.add(ed.component(), cell(1, 0, 1, grow ? 1 : 0, 0, new Insets(4, 0, 4, 0)));
        if (!grow) row.add(Box.createHorizontalGlue(), cell(2, 0, 1, 1, 0, new Insets(0, 0, 0, 0)));
        return row;
    }

    private Editor editor(Model.FieldDef f, String value) {
        Editor ed;
        if (f.kind().equals("sex")) {
            String[] codes = {"M", "F", "U"};
            var combo = new JComboBox<>(new String[] {"Male", "Female", "Unknown"});
            combo.setSelectedIndex(Math.max(0, Arrays.asList(codes).indexOf(value.isBlank() ? "U" : value)));
            combo.getAccessibleContext().setAccessibleName(f.label());
            ed = new Editor(f, combo, () -> codes[Math.max(0, combo.getSelectedIndex())],
                    v -> combo.setSelectedIndex(Math.max(0, Arrays.asList(codes).indexOf(v.isBlank() ? "U" : v))));
        } else {
            var field = textField(value, f.label(), f.label());
            ed = new Editor(f, field, field::getText, v -> {
                field.setText(v);
                field.setCaretPosition(0);
            });
        }
        editors.put(f.key(), ed);
        return ed;
    }

    /// A text field that shows a long value from its start ("Segovia, …"), not its end.
    private static JTextField textField(String value, String placeholder, String accessibleName) {
        var field = new JTextField(value, 10);
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        field.getAccessibleContext().setAccessibleName(accessibleName);
        field.setCaretPosition(0);
        return field;
    }

    private static JTextField sized(JTextField f, int width) {
        f.setPreferredSize(new Dimension(width, f.getPreferredSize().height));
        f.setMinimumSize(new Dimension(Math.min(width, 80), f.getPreferredSize().height));
        return f;
    }

    // ---- events -------------------------------------------------------------------

    private Item addEvent(String tag, String ref, Map<String, String> fields, boolean details) {
        var seen = new LinkedHashMap<String, String>();
        for (var k : List.of("type", "date", "place", "value")) seen.put(k, fields.getOrDefault(k, ""));
        var it = new Item(ListKind.EVENTS, tag, ref, seen);
        it.details = details;
        boolean attribute = EventKinds.attribute(tag);
        boolean needsType = EventKinds.NEED_TYPE.contains(tag);
        var type = sized(textField(seen.get("type"), needsType ? "Type (needed)" : "Type", ""), TYPE_W);
        type.setToolTipText(needsType ? "What it was: First communion, Eye colour…" : "What kind: a degree, a reason… (optional)");
        var date = sized(textField(seen.get("date"), "Date", ""), DATE_W);
        date.setToolTipText("<html>Dates as GEDCOM writes them:<br>" + escape(datesHint) + "</html>");
        var place = textField(seen.get("place"), "Town, region, country", "");
        place.setColumns(8);
        var value = textField(seen.get("value"), EventKinds.valueHint(tag), "");
        it.parts.put("type", new Part(type, type));
        it.parts.put("date", new Part(date, date));
        it.parts.put("place", new Part(place, place));
        it.parts.put("value", new Part(value, value));

        // the first column says what: a fact's value, else the type; the other
        // one goes on a second line when it says something
        JTextField primary = attribute && !needsType ? sized(value, TYPE_W) : type;
        JTextField secondary = primary == value ? type : value;
        boolean showSecondary = secondary == value ? attribute || !seen.get("value").isBlank() : !seen.get("type").isBlank();

        var row = it.row;
        row.add(rowLabel(it.label), labelAt(0));
        // an event with no type shows "+ Type" until asked, as the web app does
        // the same holder in every row, so the columns line up row to row
        var first = column(type.getPreferredSize().height);
        if (primary == type && seen.get("type").isBlank() && !needsType) typeReveal(it, type, first);
        else first.add(primary, BorderLayout.CENTER);
        row.add(first, cell(1, 0, 1, 0.8, 0, new Insets(4, 0, 4, 8)));
        row.add(date, cell(2, 0, 1, 0, 0, new Insets(4, 0, 4, 8)));
        var placeColumn = column(place.getPreferredSize().height);
        placeColumn.add(place, BorderLayout.CENTER);
        row.add(placeColumn, cell(3, 0, 1, 1, 0, new Insets(4, 0, 4, 4)));

        it.remove = removeButton(() -> removeItem(it));
        row.add(it.remove, cell(4, 0, 1, 0, 0, new Insets(4, 0, 4, 0)));
        if (showSecondary) {
            row.add(secondary, cell(1, 1, 3, 1, -1, new Insets(0, 0, 4, 4)));
        } else if (secondary == type) {
            // a fact's type ("Occupation: Engineer", of what kind) is on request too
            var second = Ui.transparent(new BorderLayout());
            typeReveal(it, type, second);
            row.add(second, cell(1, 1, 3, 1, -1, new Insets(0, 0, 4, 4)));
        }
        items.get(ListKind.EVENTS).add(it);
        lists.get(ListKind.EVENTS).add(row);
        return it;
    }

    /// A "+ Type" button in the type's place; clicking it shows the field, focused.
    private static JPanel column(int height) {
        var holder = Ui.transparent(new BorderLayout());
        holder.setPreferredSize(new Dimension(TYPE_W, height));
        holder.setMinimumSize(new Dimension(60, height));
        return holder;
    }

    private void typeReveal(Item it, JTextField type, JPanel holder) {
        var add = new JButton("Type", Icons.plus(12));
        Ui.styleToolButton(add, "Add a type: a degree, a reason, what kind…");
        add.setFocusable(true);
        add.setForeground(Ui.muted());
        it.revealType = add;
        add.addActionListener(e -> {
            holder.removeAll();
            holder.add(type, BorderLayout.CENTER);
            holder.revalidate();
            holder.repaint();
            it.revealType = null;
            SwingUtilities.invokeLater(type::requestFocusInWindow);
        });
        holder.add(add, BorderLayout.WEST);
    }

    private JButton removeButton(Runnable action) {
        var b = Ui.toolButton(Icons.close(14), "Remove", action);
        b.setFocusable(true);
        return b;
    }

    private void showAddMenu() {
        var menu = new JPopupMenu();
        boolean firstGroup = true;
        for (var g : addable) {
            if (!firstGroup) menu.addSeparator();
            firstGroup = false;
            var h = new JMenuItem(g.title());
            h.setEnabled(false);
            menu.add(h);
            for (var tag : g.tags()) {
                var item = new JMenuItem(EventKinds.menuLabel(tag));
                item.addActionListener(e -> addNewEvent(tag));
                menu.add(item);
            }
        }
        menu.show(addEvent, 0, addEvent.getHeight());
    }

    /// Appends a new, empty event of `tag` and puts the cursor in it.
    void addNewEvent(String tag) {
        focusNew(addEvent(tag, null, Map.of(), false));
    }

    private void focusNew(Item it) {
        renameAll();
        form.revalidate();
        form.repaint();
        SwingUtilities.invokeLater(() -> {
            var f = it.first().text();
            f.requestFocusInWindow();
            it.row.scrollRectToVisible(new java.awt.Rectangle(0, 0, it.row.getWidth(), it.row.getHeight() + 40));
        });
    }

    private void removeItem(Item it) {
        if (it.kind == ListKind.EVENTS && it.details && !it.isNew()) {
            int r = JOptionPane.showOptionDialog(this,
                    "This event also has notes or sources, which will be removed too.",
                    "Remove " + it.label.toLowerCase(java.util.Locale.ROOT) + "?",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                    new Object[] {"Remove", "Keep it"}, "Keep it");
            if (r != 0) return;
        }
        it.removed = true;
        it.row.setVisible(false);
        form.revalidate();
        form.repaint();
    }

    // ---- notes and names ---------------------------------------------------------

    private Item addNote(String ref, String text, String shared) {
        var it = new Item(ListKind.NOTES, "NOTE", ref, Map.of("text", text));
        it.shared = shared;
        var row = it.row;
        row.add(rowLabel(shared == null ? "Note" : "Shared note"), labelAt(0));
        if (shared == null) {
            var area = new JTextArea(text, Math.clamp(text.lines().count() + 1, 2, 8), 20);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setFont(Ui.base());
            area.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
            area.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
            area.setCaretPosition(0);
            var sp = new JScrollPane(area, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            it.parts.put("text", new Part(area, sp));
            row.add(sp, cell(1, 0, 1, 1, 0, new Insets(4, 0, 4, 4)));
        } else {
            var box = new Ui.Card(new BorderLayout(0, 6), 10);
            box.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            var shown = Ui.paragraph(text);
            box.add(shown, BorderLayout.CENTER);
            var hint = Ui.transparent(new BorderLayout(8, 0));
            hint.add(Ui.small("Shared note " + shared + ", also used elsewhere: edit it on its own page."), BorderLayout.CENTER);
            if (openRecord != null) hint.add(Ui.link("Open " + shared, () -> openShared(shared)), BorderLayout.EAST);
            box.add(hint, BorderLayout.SOUTH);
            row.add(box, cell(1, 0, 1, 1, 0, new Insets(4, 0, 4, 4)));
        }
        it.remove = removeButton(() -> removeItem(it));
        if (shared != null) it.remove.setToolTipText("Unlink this shared note (the note itself stays)");
        row.add(it.remove, cell(2, 0, 1, 0, 0, new Insets(4, 0, 4, 0)));
        items.get(ListKind.NOTES).add(it);
        lists.get(ListKind.NOTES).add(row);
        return it;
    }

    private void openShared(String key) {
        if (cancel()) openRecord.accept(key);
    }

    private Item addName(String ref, Map<String, String> fields) {
        var seen = new LinkedHashMap<String, String>();
        for (var k : List.of("given", "surname", "type")) seen.put(k, fields.getOrDefault(k, ""));
        var it = new Item(ListKind.NAMES, "NAME", ref, seen);
        var given = textField(seen.get("given"), "Given names", "");
        given.setColumns(6);
        var surname = textField(seen.get("surname"), "Surname", "");
        surname.setColumns(6);
        var type = sized(textField(seen.get("type"), "Type", ""), TYPE_NAME_W);
        type.setToolTipText("aka, birth, married, maiden, immigrant…");
        it.parts.put("given", new Part(given, given));
        it.parts.put("surname", new Part(surname, surname));
        it.parts.put("type", new Part(type, type));
        var row = it.row;
        row.add(rowLabel("Other name"), labelAt(0));
        row.add(given, cell(1, 0, 1, 1, 0, new Insets(4, 0, 4, 8)));
        row.add(surname, cell(2, 0, 1, 1, 0, new Insets(4, 0, 4, 8)));
        row.add(type, cell(3, 0, 1, 0, 0, new Insets(4, 0, 4, 4)));
        it.remove = removeButton(() -> removeItem(it));
        row.add(it.remove, cell(4, 0, 1, 0, 0, new Insets(4, 0, 4, 0)));
        items.get(ListKind.NAMES).add(it);
        lists.get(ListKind.NAMES).add(row);
        return it;
    }

    /// Gives every row's fields their accessible names: "Residence 2 date",
    /// "Note", "Other name surname". Rows sharing a label are numbered.
    private void renameAll() {
        for (var list : items.values()) {
            var counts = new HashMap<String, Integer>();
            for (var it : list) counts.merge(it.label, 1, Integer::sum);
            var seen = new HashMap<String, Integer>();
            for (var it : list) {
                int n = seen.merge(it.label, 1, Integer::sum);
                it.name = counts.get(it.label) > 1 ? it.label + " " + n : it.label;
                it.parts.forEach((k, p) -> p.text().getAccessibleContext()
                        .setAccessibleName(it.kind == ListKind.NOTES ? it.name : it.name + " " + k));
                if (it.remove != null) it.remove.getAccessibleContext().setAccessibleName("Remove " + it.name);
                if (it.revealType != null) it.revealType.getAccessibleContext().setAccessibleName("Add type to " + it.name);
            }
        }
    }

    // ---- saving -----------------------------------------------------------------------

    private Map<String, String> values() {
        var out = new LinkedHashMap<String, String>();
        editors.forEach((k, e) -> out.put(k, e.get().get()));
        return out;
    }

    private List<String> nicknameList() {
        if (nicknames == null) return List.of();
        return Arrays.stream(nicknames.getText().split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /// The whole request: changed name fields and the changed items of each list.
    Map<String, Object> request() {
        var body = new LinkedHashMap<String, Object>();
        var r = session.request(values());
        if (!r.isEmpty()) {
            body.put("set", r.set());
            body.put("seen", r.seen());
        }
        items.forEach((kind, list) -> {
            var ops = new ArrayList<Map<String, Object>>();
            for (var it : list) {
                var op = it.request();
                if (op != null) ops.add(op);
            }
            if (!ops.isEmpty()) body.put(kind.key, ops);
        });
        if (nicknames != null && !nicknameList().equals(seenNicknames)) {
            body.put("nicknames", Map.of("seen", seenNicknames, "set", nicknameList()));
        }
        return body;
    }

    private boolean changed() {
        return !request().isEmpty();
    }

    private void unmarkAll() {
        editors.values().forEach(e -> {
            e.component().putClientProperty(FlatClientProperties.OUTLINE, null);
            e.component().repaint();
        });
        items.values().forEach(l -> l.forEach(Item::unmark));
        if (nicknames != null) nicknames.putClientProperty(FlatClientProperties.OUTLINE, null);
    }

    private void save() {
        if (busy) return;
        unmarkAll();
        var body = request();
        if (body.isEmpty()) {
            dispose();
            return;
        }
        setBusy(true);
        Bg.run(() -> saver.save(body), reply -> {
            setBusy(false);
            dispose();
            onSaved.accept(reply);
        }, this::failed);
    }

    private void failed(Exception e) {
        setBusy(false);
        int status = Api.status(e);
        if (status == 409 && e instanceof ged.client.DaemonClient.ApiException a && Boolean.TRUE.equals(a.body.get("conflict"))) {
            rebase(a.getMessage(), Content.from(replyKey, Model.map(a.body, replyKey)));
            return;
        }
        if (status == 422) {
            showRefusal(e.getMessage());
            return;
        }
        banner.show(Ui.Banner.Kind.ERROR, "Couldn't save: " + Api.message(e)
                + (status == 0 ? "\nYour edits are still in this form; try again once the daemon is back." : ""));
    }

    // ---- refusals -------------------------------------------------------------------

    /// `RESI:1.date: …`, `EVEN: an event needs a type…`, `NAME:1.given: …`.
    private static final Pattern ITEM_ERROR = Pattern.compile("^([A-Z_][A-Z0-9_]*)(?::(\\d+))?(?:\\.(\\w+))?:\\s*(.*)$", Pattern.DOTALL);

    /// Shows a refused edit (`422`) in the banner, marking the field it is about.
    void showRefusal(String message) {
        var key = EditSession.fieldOf(message, editors.keySet());
        if (key != null) {
            var ed = editors.get(key);
            banner.show(Ui.Banner.Kind.ERROR, ed.def().label() + ": " + message.substring(message.indexOf(':') + 1).strip());
            ed.component().putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            ed.component().requestFocusInWindow();
            return;
        }
        var m = ITEM_ERROR.matcher(message == null ? "" : message);
        if (m.matches()) {
            var it = itemFor(m.group(1), m.group(2));
            if (it != null) {
                var part = m.group(3) != null ? m.group(3)
                        : it.kind == ListKind.EVENTS && EventKinds.NEED_TYPE.contains(it.tag) ? "type" : null;
                banner.show(Ui.Banner.Kind.ERROR, it.name + (part == null || it.kind == ListKind.NOTES ? "" : " " + part) + ": " + m.group(4));
                var p = part == null ? it.first() : it.parts.get(part);
                if (part != null) it.mark(part, FlatClientProperties.OUTLINE_ERROR);
                if (p != null) {
                    p.text().requestFocusInWindow();
                    it.row.scrollRectToVisible(new java.awt.Rectangle(0, 0, it.row.getWidth(), it.row.getHeight()));
                }
                return;
            }
        }
        banner.show(Ui.Banner.Kind.ERROR, message);
    }

    /// The row a message names: `RESI:1` by its ref, a bare `EVEN` the first new one.
    private Item itemFor(String tag, String index) {
        for (var list : items.values()) {
            for (var it : list) {
                if (it.removed) continue;
                if (index != null && (tag + ":" + index).equals(it.ref)) return it;
                if (index == null && it.isNew() && it.tag.equals(tag)) return it;
            }
        }
        return null;
    }

    // ---- conflicts ------------------------------------------------------------------

    /// Moves the form onto `latest`, keeping the user's own changes, and says what happened.
    private void rebase(String server, Content latest) {
        shown = latest;
        var marked = new LinkedHashSet<String>();
        boolean clash = false;
        var nameFields = new LinkedHashMap<String, String>();
        for (var d : nameDefs) nameFields.put(d.key(), latest.fields().getOrDefault(d.key(), ""));
        var rb = session.rebase(values(), nameFields);
        rb.values().forEach((k, v) -> {
            var ed = editors.get(k);
            if (ed != null) ed.set().accept(v);
        });
        for (var k : rb.changedElsewhere()) {
            var ed = editors.get(k);
            if (ed == null) continue;
            boolean c = rb.clashes().contains(k);
            clash |= c;
            ed.component().putClientProperty(FlatClientProperties.OUTLINE, c ? FlatClientProperties.OUTLINE_ERROR : FlatClientProperties.OUTLINE_WARNING);
            ed.component().setToolTipText("Changed elsewhere meanwhile; now “" + nameFields.getOrDefault(k, "") + "”");
            marked.add(ed.def().label());
        }

        var reloaded = new ArrayList<String>();
        var latestLists = new LinkedHashMap<ListKind, Map<String, Map<String, String>>>();
        latestLists.put(ListKind.EVENTS, byRef(latest.events(), Model.Event::ref, Model.Event::fields));
        latestLists.put(ListKind.NOTES, byRef(latest.notes(), Model.NoteItem::ref, n -> Map.of("text", n.text())));
        if (latest.names() != null) latestLists.put(ListKind.NAMES, byRef(latest.names(), Model.OtherName::ref, Model.OtherName::fields));
        for (var e : latestLists.entrySet()) {
            var kind = e.getKey();
            var theirs = e.getValue();
            var list = items.get(kind);
            var mine = new LinkedHashSet<String>();
            for (var it : list) if (!it.isNew()) mine.add(it.ref);
            if (!mine.equals(theirs.keySet())) {
                reload(kind, latest);
                reloaded.add(kind.key);
                continue;
            }
            for (var it : list) {
                if (it.isNew()) continue;
                var latestParts = new LinkedHashMap<String, String>();
                it.parts.keySet().forEach(k -> latestParts.put(k, theirs.get(it.ref).getOrDefault(k, "")));
                if (it.shared != null) continue;
                var r = it.session.rebase(it.values(), latestParts);
                r.values().forEach((k, v) -> {
                    var p = it.parts.get(k);
                    if (p != null && !p.get().equals(v)) p.set(v);
                });
                if (!r.changedElsewhere().isEmpty() && it.removed) {
                    // changed meanwhile: show it again rather than remove what nobody here saw
                    it.removed = false;
                    it.row.setVisible(true);
                }
                for (var k : r.changedElsewhere()) {
                    boolean c = r.clashes().contains(k);
                    clash |= c;
                    it.mark(k, c ? FlatClientProperties.OUTLINE_ERROR : FlatClientProperties.OUTLINE_WARNING);
                    marked.add(it.name);
                }
            }
        }
        if (nicknames != null && latest.nicknames() != null && !latest.nicknames().equals(seenNicknames)) {
            boolean userChanged = !nicknameList().equals(seenNicknames);
            boolean c = userChanged && !nicknameList().equals(latest.nicknames());
            if (!userChanged) {
                nicknames.setText(String.join(", ", latest.nicknames()));
                nicknames.setCaretPosition(0);
            }
            clash |= c;
            nicknames.putClientProperty(FlatClientProperties.OUTLINE, c ? FlatClientProperties.OUTLINE_ERROR : FlatClientProperties.OUTLINE_WARNING);
            marked.add("Nicknames");
        }
        if (nicknames != null && latest.nicknames() != null) seenNicknames = List.copyOf(latest.nicknames());
        renameAll();
        form.revalidate();
        form.repaint();
        banner.show(Ui.Banner.Kind.WARNING, conflictMessage(server, clash, reloaded));
    }

    private static <T> Map<String, Map<String, String>> byRef(List<T> list, java.util.function.Function<T, String> ref,
                                                             java.util.function.Function<T, Map<String, String>> parts) {
        var out = new LinkedHashMap<String, Map<String, String>>();
        for (var x : list) out.put(ref.apply(x), parts.apply(x));
        return out;
    }

    /// Replaces a list's rows by the latest ones, keeping the rows added here.
    private void reload(ListKind kind, Content latest) {
        var list = items.get(kind);
        var added = list.stream().filter(it -> it.isNew() && !it.removed).toList();
        list.clear();
        lists.get(kind).removeAll();
        switch (kind) {
            case EVENTS -> latest.events().forEach(e -> addEvent(e.tag(), e.ref(), e.fields(), e.details()));
            case NOTES -> latest.notes().forEach(n -> addNote(n.ref(), n.text(), n.shared()));
            case NAMES -> latest.names().forEach(n -> addName(n.ref(), n.fields()));
        }
        for (var it : added) {
            list.add(it);
            lists.get(kind).add(it.row);
        }
    }

    private String conflictMessage(String server, boolean clash, List<String> reloaded) {
        var what = server == null ? "" : server.replace("changed elsewhere meanwhile: ", "").replace("; nothing was applied", "");
        var sb = new StringBuilder("Someone changed this ").append(noun).append(" while you were editing");
        if (!what.isBlank()) sb.append(" (").append(what).append(")");
        sb.append(". Nothing was saved yet.\n");
        sb.append("The form now shows the latest values, with your own changes kept. ");
        if (clash) sb.append("Fields marked in red were changed by you and by them; check them, then save again.");
        else sb.append("Fields marked in orange changed meanwhile; check them, then save again.");
        if (!reloaded.isEmpty()) {
            sb.append("\nThe ").append(String.join(" and ", reloaded))
                    .append(reloaded.size() == 1 ? " list" : " lists")
                    .append(" gained or lost items meanwhile, so it was reloaded: your changes to existing ")
                    .append(String.join(" and ", reloaded))
                    .append(" weren't kept (new ones were). Make them again, then save.");
        }
        return sb.toString();
    }

    private void setBusy(boolean b) {
        busy = b;
        save.setEnabled(!b);
        save.setText(b ? "Saving…" : "Save");
        setCursor(b ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR) : null);
    }

    /// Closes the form, asking first if that would lose changes; false if the user kept editing.
    private boolean cancel() {
        if (busy) return false;
        if (changed()) {
            int r = JOptionPane.showOptionDialog(this, "Discard your changes to this " + noun + "?", "Discard changes",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                    new Object[] {"Discard", "Keep editing"}, "Keep editing");
            if (r != 0) return false;
        }
        dispose();
        return true;
    }

    /// The file changed elsewhere while the form is open: when what the form
    /// shows changed (`latest` is the record now, null when unknown), say so,
    /// keeping the form; edits to other records aren't this form's business.
    void externalChange(Content latest) {
        if (latest != null && latest.equals(shown)) return;
        if (banner.isVisible()) return;
        banner.show(Ui.Banner.Kind.INFO, "This " + noun + " was just changed elsewhere (another window, the shell or the web app). "
                + "Your edits here are kept; saving checks them against the latest version and points out anything that clashes.");
        getContentPane().revalidate();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static JLabel face(Images images, Model.PersonSummary p) {
        return new JLabel(new Avatar(images, p, 44));
    }
}
