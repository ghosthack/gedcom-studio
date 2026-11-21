package ged.desktop;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/// The edit form for a note, source or media record: a note's text, the
/// value of each line that can be edited, removing lines (with what lies
/// under them) and adding new ones at the end (`POST records/KEY/edit`).
///
/// Saving sends only what changed, with the values the form showed; a
/// conflict (`409`) moves the form onto the latest version, keeping the
/// user's own changes, or reloads it when lines were added or removed
/// meanwhile; a refusal (`422`) is shown with the line marked.
final class RecordEditDialog extends JDialog {

    interface Saver {
        Map<String, Object> save(Map<String, Object> body) throws IOException;
    }

    /// A line of the record as a row: `field` is null for a line added here.
    private final class Line {
        Model.RecordField field;
        final JPanel row = Ui.transparent(new GridBagLayout());
        JTextComponent value;
        JComboBox<String> tag;
        JButton remove;
        boolean removed;

        String key() {
            return "line:" + field.ref();
        }
    }

    private static final Pattern LINE_ERROR = Pattern.compile("^line ([0-9.]+)\\b.*", Pattern.DOTALL);

    private final String kind;
    private final Saver saver;
    private final Consumer<Map<String, Object>> onSaved;
    private final Ui.Banner banner = new Ui.Banner();
    private final JButton save = new JButton("Save");
    private final JButton cancel = new JButton("Cancel");
    private final JPanel linesPanel = Ui.stack(0);
    private final List<Line> lines = new ArrayList<>();
    private JTextArea text;
    private EditSession session;
    private int labelWidth = 130;
    private boolean busy;
    /// The version of the record the form shows (the latest after a conflict).
    private Model.RecordDetail shown;

    RecordEditDialog(Window owner, Model.RecordDetail r, Saver saver, Consumer<Map<String, Object>> onSaved) {
        super(owner, "Edit " + Place.Kinds.singular(r.kind()).toLowerCase(Locale.ROOT) + " " + r.key(), ModalityType.DOCUMENT_MODAL);
        this.kind = r.kind();
        this.saver = saver;
        this.onSaved = onSaved;

        var root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        var head = Ui.transparent(new BorderLayout(14, 0));
        head.add(new JLabel(Icons.tinted(Icons.forKind(r.kind(), 30), Ui.accent())), BorderLayout.WEST);
        var headText = Ui.stack(3);
        var t = Ui.heading(r.title().isBlank() ? r.key() : r.title());
        t.setFont(Ui.font(5, Font.BOLD));
        headText.add(t);
        headText.add(Ui.mutedLabel(Place.Kinds.singular(r.kind()) + " " + r.key()
                + ". Changes go to the daemon right away; save the file (⌘S) to write them."));
        head.add(headText, BorderLayout.CENTER);
        var north = Ui.stack(12);
        north.add(head);
        north.add(banner);
        north.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        root.add(north, BorderLayout.NORTH);

        var form = Ui.stack(0);
        if (r.kind().equals("notes")) {
            form.add(header("Text", true));
            text = new JTextArea(r.text(), Math.clamp(r.text().lines().count() + 1, 3, 12), 30);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setFont(Ui.base());
            text.getAccessibleContext().setAccessibleName("Note text");
            text.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
            text.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
            text.setCaretPosition(0);
            form.add(new JScrollPane(text, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
        }
        form.add(header("Details", text == null));
        form.add(linesPanel);
        build(r);
        var add = new JButton("Add line", Icons.plus(14));
        Ui.styleToolButton(add, "Add a line at the end: a tag (such as PUBL) and its value");
        add.setFocusable(true);
        add.addActionListener(e -> addLine());
        var addRow = Ui.transparent(new BorderLayout());
        addRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        addRow.add(add, BorderLayout.WEST);
        form.add(addRow);

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
        setSize(new Dimension(Math.max(640, getWidth()), Math.min(getHeight(), Math.max(440, (int) (screen.height * 0.86)))));
        setLocationRelativeTo(owner);
    }

    private static JLabel header(String title, boolean first) {
        var h = Ui.overline(title);
        h.setBorder(BorderFactory.createEmptyBorder(first ? 0 : 18, 0, 6, 0));
        return h;
    }

    /// (Re)builds the rows from the record, and what the form showed.
    private void build(Model.RecordDetail r) {
        shown = r;
        // one column for the values, however deep their lines
        var fm = getFontMetrics(Ui.base());
        labelWidth = 130;
        for (var f : r.fields()) {
            labelWidth = Math.max(labelWidth, fm.stringWidth(f.label().isBlank() ? f.tag() : f.label()) + f.depth() * 18 + 6);
        }
        labelWidth = Math.min(labelWidth, 260);
        lines.clear();
        linesPanel.removeAll();
        var seen = new LinkedHashMap<String, String>();
        if (text != null) seen.put("text", r.text());
        for (var f : r.fields()) {
            var line = new Line();
            line.field = f;
            lines.add(line);
            if (f.editable() && f.link() == null) seen.put(line.key(), f.value());
            layoutLine(line);
        }
        session = new EditSession(seen);
        nameLines();
        linesPanel.revalidate();
    }

    private void layoutLine(Line line) {
        var f = line.field;
        var label = Ui.label(f.label().isBlank() ? f.tag() : f.label());
        label.setForeground(Ui.muted());
        label.setHorizontalAlignment(SwingConstants.LEFT);
        label.setToolTipText(f.tag());
        label.setBorder(BorderFactory.createEmptyBorder(0, f.depth() * 18, 0, 0));
        label.setPreferredSize(new Dimension(labelWidth, label.getPreferredSize().height));
        line.row.add(label, new GridBagConstraints(0, 0, 1, 1, 0, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                new Insets(8, 0, 4, 12), 0, 0));
        JComponent shown;
        if (f.link() != null || !f.editable()) {
            var v = Ui.paragraph(f.value());
            v.setForeground(Ui.muted());
            v.setToolTipText(f.link() != null ? "A link to " + f.link() + ": it can be removed, not changed here" : "Kept by the program; it can't be changed here");
            v.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
            shown = v;
        } else if (multiline(f.tag(), f.value())) {
            var area = area(f.value());
            line.value = area;
            shown = new JScrollPane(area, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        } else {
            var field = new JTextField(f.value(), 20);
            field.setCaretPosition(0);
            line.value = field;
            shown = field;
        }
        line.row.add(shown, new GridBagConstraints(1, 0, 1, 1, 1, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL,
                new Insets(4, 0, 4, 4), 0, 0));
        line.remove = Ui.toolButton(Icons.close(14), "Remove (with any lines under it)", () -> remove(line));
        line.remove.setFocusable(true);
        line.row.add(line.remove, new GridBagConstraints(2, 0, 1, 1, 0, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                new Insets(4, 0, 4, 0), 0, 0));
        linesPanel.add(line.row);
    }

    private static boolean multiline(String tag, String value) {
        return value.contains("\n") || tag.equals("TEXT") || tag.equals("NOTE") || value.length() > 90;
    }

    private static JTextArea area(String value) {
        var area = new JTextArea(value, Math.clamp(value.lines().count() + 1, 2, 8), 20);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Ui.base());
        area.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        area.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        area.setCaretPosition(0);
        return area;
    }

    /// Tags worth offering for a new line of this kind of record; any other can be typed.
    private List<String> suggestedTags() {
        return switch (kind) {
            case "sources" -> List.of("TITL", "AUTH", "PUBL", "ABBR", "TEXT", "NOTE", "REFN");
            case "media" -> List.of("TITL", "FILE", "FORM", "NOTE", "REFN");
            default -> List.of("REFN", "RIN");
        };
    }

    private void addLine() {
        var line = new Line();
        var combo = new JComboBox<>(suggestedTags().toArray(new String[0]));
        combo.setEditable(true);
        combo.setToolTipText("The line's GEDCOM tag, such as PUBL (publication) or NOTE");
        combo.setPreferredSize(new Dimension(130, combo.getPreferredSize().height));
        line.tag = combo;
        var value = new JTextField(20);
        value.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Value");
        line.value = value;
        line.row.add(combo, new GridBagConstraints(0, 0, 1, 1, 0, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                new Insets(4, 0, 4, 12), 0, 0));
        line.row.add(value, new GridBagConstraints(1, 0, 1, 1, 1, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL,
                new Insets(4, 0, 4, 4), 0, 0));
        line.remove = Ui.toolButton(Icons.close(14), "Remove", () -> remove(line));
        line.remove.setFocusable(true);
        line.row.add(line.remove, new GridBagConstraints(2, 0, 1, 1, 0, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                new Insets(4, 0, 4, 0), 0, 0));
        lines.add(line);
        linesPanel.add(line.row);
        nameLines();
        linesPanel.revalidate();
        linesPanel.repaint();
        SwingUtilities.invokeLater(() -> {
            value.requestFocusInWindow();
            line.row.scrollRectToVisible(new java.awt.Rectangle(0, 0, line.row.getWidth(), line.row.getHeight() + 40));
        });
    }

    /// Accessible names: "Title", "Note 2", "Remove Author"; new lines "New line tag", "New line 2 value".
    private void nameLines() {
        var counts = new HashMap<String, Integer>();
        for (var l : lines) counts.merge(lineName(l), 1, Integer::sum);
        var seen = new HashMap<String, Integer>();
        for (var l : lines) {
            var base = lineName(l);
            int n = seen.merge(base, 1, Integer::sum);
            var name = counts.get(base) > 1 ? base + " " + n : base;
            if (l.value != null) l.value.getAccessibleContext().setAccessibleName(l.field == null ? name + " value" : name);
            if (l.tag != null) l.tag.getAccessibleContext().setAccessibleName(name + " tag");
            l.remove.getAccessibleContext().setAccessibleName("Remove " + name);
        }
    }

    private static String lineName(Line l) {
        if (l.field == null) return "New line";
        return l.field.label().isBlank() ? l.field.tag() : l.field.label();
    }

    private void remove(Line line) {
        line.removed = true;
        line.row.setVisible(false);
        if (line.field != null) {
            // what lies under it goes with it
            for (var l : lines) {
                if (l.field != null && l.field.ref().startsWith(line.field.ref() + ".")) {
                    l.removed = true;
                    l.row.setVisible(false);
                }
            }
        }
        linesPanel.revalidate();
        linesPanel.repaint();
    }

    // ---- saving -----------------------------------------------------------------

    private Map<String, String> values() {
        var out = new LinkedHashMap<String, String>();
        if (text != null) out.put("text", text.getText());
        for (var l : lines) {
            if (l.field != null && l.value != null && !l.removed) out.put(l.key(), l.value.getText());
        }
        return out;
    }

    private boolean underRemoved(Line line) {
        for (var l : lines) {
            if (l != line && l.removed && l.field != null && line.field.ref().startsWith(l.field.ref() + ".")) return true;
        }
        return false;
    }

    Map<String, Object> request() {
        var body = new LinkedHashMap<String, Object>();
        var changes = session.changes(values());
        if (changes.containsKey("text")) {
            body.put("seenText", session.seen().get("text"));
            body.put("text", text.getText());
        }
        var ops = new ArrayList<Map<String, Object>>();
        for (var l : lines) {
            if (l.field == null) {
                if (l.removed) continue;
                var tag = String.valueOf(l.tag.getEditor().getItem()).strip().toUpperCase(Locale.ROOT);
                var value = l.value.getText().strip();
                if (tag.isEmpty() && value.isEmpty()) continue;
                ops.add(Map.of("add", tag, "set", Map.of("value", value, "under", "")));
            } else if (l.removed) {
                if (!underRemoved(l)) ops.add(Map.of("ref", l.field.ref(), "seen", Map.of("tag", l.field.tag()), "remove", true));
            } else if (changes.containsKey(l.key())) {
                ops.add(Map.of("ref", l.field.ref(), "seen", Map.of("value", session.seen().get(l.key())),
                        "set", Map.of("value", l.value.getText())));
            }
        }
        if (!ops.isEmpty()) body.put("lines", ops);
        return body;
    }

    private void save() {
        if (busy) return;
        lines.forEach(l -> mark(l, null));
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

    private void mark(Line l, String outline) {
        if (l.value == null) return;
        JComponent c = l.value.getParent() != null && l.value.getParent().getParent() instanceof JScrollPane sp ? sp : l.value;
        c.putClientProperty(FlatClientProperties.OUTLINE, outline);
        c.repaint();
    }

    private void failed(Exception e) {
        setBusy(false);
        int status = Api.status(e);
        if (status == 409 && e instanceof ged.client.DaemonClient.ApiException a && Boolean.TRUE.equals(a.body.get("conflict"))) {
            rebase(Model.RecordDetail.from(Model.map(a.body, "record")));
            return;
        }
        if (status == 422) {
            var m = LINE_ERROR.matcher(e.getMessage() == null ? "" : e.getMessage());
            if (m.matches()) {
                for (var l : lines) {
                    if (l.field != null && l.field.ref().equals(m.group(1))) {
                        mark(l, FlatClientProperties.OUTLINE_ERROR);
                        if (l.value != null) l.value.requestFocusInWindow();
                    }
                }
            }
            banner.show(Ui.Banner.Kind.ERROR, e.getMessage());
            return;
        }
        banner.show(Ui.Banner.Kind.ERROR, "Couldn't save: " + Api.message(e)
                + (status == 0 ? "\nYour edits are still in this form; try again once the daemon is back." : ""));
    }

    /// After a conflict: the latest version, keeping the user's changes when
    /// the lines are still the same ones; otherwise the form is reloaded.
    private void rebase(Model.RecordDetail latest) {
        shown = latest;
        var shape = lines.stream().filter(l -> l.field != null).map(l -> l.field.ref() + " " + l.field.tag()).toList();
        var latestShape = latest.fields().stream().map(f -> f.ref() + " " + f.tag()).toList();
        if (!shape.equals(latestShape)) {
            var added = lines.stream().filter(l -> l.field == null && !l.removed).toList();
            if (text != null) {
                text.setText(latest.text());
                text.setCaretPosition(0);
            }
            build(latest);
            for (var l : added) {
                lines.add(l);
                linesPanel.add(l.row);
            }
            nameLines();
            banner.show(Ui.Banner.Kind.WARNING, "Someone changed this record while you were editing: lines were added or removed. "
                    + "Nothing was saved yet. The form now shows the latest version; your changes to existing lines weren't kept "
                    + "(new lines were). Make them again, then save.");
            return;
        }
        var latestValues = new LinkedHashMap<String, String>();
        if (text != null) latestValues.put("text", latest.text());
        for (int i = 0; i < latest.fields().size(); i++) {
            var f = latest.fields().get(i);
            if (f.editable() && f.link() == null) latestValues.put("line:" + f.ref(), f.value());
        }
        var mine = new LinkedHashMap<String, String>();
        if (text != null) mine.put("text", text.getText());
        for (var l : lines) if (l.field != null && l.value != null) mine.put(l.key(), l.value.getText());
        var rb = session.rebase(mine, latestValues);
        boolean clash = !rb.clashes().isEmpty();
        if (text != null && rb.values().containsKey("text") && !text.getText().equals(rb.values().get("text"))) {
            text.setText(rb.values().get("text"));
            text.setCaretPosition(0);
        }
        for (var l : lines) {
            if (l.field == null || l.value == null) continue;
            var v = rb.values().get(l.key());
            if (v != null && !v.equals(l.value.getText())) {
                l.value.setText(v);
                l.value.setCaretPosition(0);
            }
            if (rb.changedElsewhere().contains(l.key())) {
                if (l.removed) {
                    l.removed = false;
                    l.row.setVisible(true);
                }
                mark(l, rb.clashes().contains(l.key()) ? FlatClientProperties.OUTLINE_ERROR : FlatClientProperties.OUTLINE_WARNING);
            }
        }
        banner.show(Ui.Banner.Kind.WARNING, "Someone changed this record while you were editing. Nothing was saved yet.\n"
                + "The form now shows the latest values, with your own changes kept. "
                + (clash ? "Lines marked in red were changed by you and by them; check them, then save again."
                        : "Lines marked in orange changed meanwhile; check them, then save again."));
        linesPanel.revalidate();
    }

    private void setBusy(boolean b) {
        busy = b;
        save.setEnabled(!b);
        save.setText(b ? "Saving…" : "Save");
        setCursor(b ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR) : null);
    }

    private void cancel() {
        if (busy) return;
        if (!request().isEmpty()) {
            int r = JOptionPane.showOptionDialog(this, "Discard your changes to this record?", "Discard changes",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                    new Object[] {"Discard", "Keep editing"}, "Keep editing");
            if (r != 0) return;
        }
        dispose();
    }

    /// The file changed elsewhere while the form is open: when this record's
    /// text or lines changed (`latest` is the record now, null when unknown),
    /// say so, keeping the form.
    void externalChange(Model.RecordDetail latest) {
        if (latest != null && latest.text().equals(shown.text()) && latest.fields().equals(shown.fields())) return;
        if (banner.isVisible()) return;
        banner.show(Ui.Banner.Kind.INFO, "This record was just changed elsewhere (another window, the shell or the web app). "
                + "Your edits here are kept; saving checks them against the latest version and points out anything that clashes.");
        getContentPane().revalidate();
    }
}
