package ged.desktop;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/// The left side of the window: which file, a search over its people, the
/// people themselves, and the file's other lists (families, notes, sources,
/// media, warnings).
final class Sidebar extends JPanel {

    private static final int LIMIT = 5000;

    private final Api api;
    private final Actions actions;

    private final JButton fileButton = new JButton();
    private final JLabel fileDetail = Ui.small("");
    private final JTextField search = new JTextField();
    private final JButton sortButton;
    private final DefaultListModel<Model.PersonSummary> model = new DefaultListModel<>();
    private final JList<Model.PersonSummary> list = new JList<>(model);
    private final JLabel count = Ui.small("");
    private final JLabel empty = Ui.mutedLabel("");
    private final JPanel listHolder = new JPanel(new java.awt.CardLayout());
    private final List<NavRow> nav = new ArrayList<>();
    private final Timer debounce;

    private Model.FileInfo file;
    private String sort = Prefs.sort();
    private int generation;
    private boolean adjusting;
    private String selectedKey;

    Sidebar(Api api, Images images, Actions actions, Runnable fileMenu) {
        super(new BorderLayout());
        this.api = api;
        this.actions = actions;

        // ---- the file ------------------------------------------------------------
        var top = Ui.stack(8);
        top.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        Ui.styleToolButton(fileButton, "Switch file, or open another (⌘O)");
        fileButton.setHorizontalTextPosition(SwingConstants.LEFT);
        fileButton.setIcon(Icons.chevronDown(12));
        fileButton.setFont(Ui.font(2, Font.BOLD));
        fileButton.setHorizontalAlignment(SwingConstants.LEFT);
        fileButton.setIconTextGap(6);
        fileButton.addActionListener(e -> fileMenu.run());
        var fileRow = Ui.transparent(new BorderLayout());
        fileRow.add(fileButton, BorderLayout.WEST);
        top.add(fileRow);
        fileDetail.setBorder(BorderFactory.createEmptyBorder(0, 7, 0, 0));
        top.add(fileDetail);

        // ---- search and sort -----------------------------------------------------
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search people");
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.search(14));
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        search.setToolTipText("Names, other names and nicknames; each word on its own (⌘F)");
        debounce = new Timer(220, e -> reload(false));
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        search.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN && model.getSize() > 0) {
                    list.requestFocusInWindow();
                    if (list.getSelectedIndex() < 0) list.setSelectedIndex(0);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER && model.getSize() > 0) {
                    actions.openPerson(model.get(Math.max(0, list.getSelectedIndex())).key());
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    search.setText("");
                }
            }
        });
        sortButton = Ui.toolButton(Icons.sort(14), "Sort people", this::showSortMenu);
        var searchRow = Ui.transparent(new BorderLayout(4, 0));
        searchRow.add(search, BorderLayout.CENTER);
        searchRow.add(sortButton, BorderLayout.EAST);
        top.add(searchRow);
        add(top, BorderLayout.NORTH);

        // ---- people --------------------------------------------------------------
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new PersonCellRenderer(images));
        list.setOpaque(false);
        list.setFixedCellHeight(46);
        list.putClientProperty(FlatClientProperties.STYLE, "selectionBackground: null; selectionInactiveBackground: null");
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || adjusting) return;
            var p = list.getSelectedValue();
            if (p != null && !p.key().equals(selectedKey)) {
                selectedKey = p.key();
                actions.openPerson(p.key());
            }
        });
        var hover = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                Integer v = i >= 0 && list.getCellBounds(i, i).contains(e.getPoint()) ? i : null;
                if (!java.util.Objects.equals(v, list.getClientProperty(PersonCellRenderer.HOVER))) {
                    list.putClientProperty(PersonCellRenderer.HOVER, v);
                    list.repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                list.putClientProperty(PersonCellRenderer.HOVER, null);
                list.repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) actions.personMenu(list, e.getX(), e.getY(), model.get(i), null, null);
            }
        };
        list.addMouseListener(hover);
        list.addMouseMotionListener(hover);
        list.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { list.repaint(); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { list.repaint(); }
        });
        var scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        listHolder.setOpaque(false);
        listHolder.add(scroll, "list");
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setVerticalAlignment(SwingConstants.TOP);
        empty.setBorder(BorderFactory.createEmptyBorder(24, 16, 0, 16));
        listHolder.add(empty, "empty");
        add(listHolder, BorderLayout.CENTER);

        // ---- the rest of the file ---------------------------------------------------
        var bottom = Ui.stack(0);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.hairline()),
                BorderFactory.createEmptyBorder(6, 6, 8, 6)));
        count.setBorder(BorderFactory.createEmptyBorder(2, 14, 6, 8));
        bottom.add(count);
        nav.add(new NavRow(Icons.family(16), "Families", new Place.RecordList("families"), this::go));
        nav.add(new NavRow(Icons.note(16), "Notes", new Place.RecordList("notes"), this::go));
        nav.add(new NavRow(Icons.source(16), "Sources", new Place.RecordList("sources"), this::go));
        nav.add(new NavRow(Icons.media(16), "Media", new Place.RecordList("media"), this::go));
        nav.add(new NavRow(Icons.warning(16), "Warnings", new Place.Warnings(), this::go));
        nav.forEach(bottom::add);
        add(bottom, BorderLayout.SOUTH);
        applyColors();
        setFile(null);
    }

    private void go(Place p) {
        actions.go(p);
    }

    void applyColors() {
        setBackground(Ui.chromeBackground());
        listHolder.setBackground(Ui.chromeBackground());
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (list != null) applyColors();
    }

    // ---- the file -----------------------------------------------------------------

    void setFile(Model.FileInfo info) {
        boolean changed = info == null || file == null || !info.id().equals(file.id());
        file = info;
        fileButton.setText(info == null ? "No file" : info.name());
        fileButton.setToolTipText(info == null ? "Open a file (⌘O)" : info.path());
        updateInfo(info);
        search.setEnabled(info != null);
        sortButton.setEnabled(info != null);
        nav.forEach(n -> n.setEnabled(info != null));
        if (changed) {
            search.setText("");
            selectedKey = null;
            model.clear();
        }
        reload(!changed);
    }

    void updateInfo(Model.FileInfo info) {
        if (info == null) {
            fileDetail.setText("Choose a file to start");
            nav.forEach(n -> n.setCount(-1, false));
            return;
        }
        file = info;
        var d = new ArrayList<String>();
        d.add("GEDCOM " + info.gedcom());
        if (info.dirtyCount() > 0) d.add(info.dirtyCount() == 1 ? "1 unsaved edit" : info.dirtyCount() + " unsaved edits");
        fileDetail.setText(String.join("  ·  ", d));
        fileDetail.setForeground(info.dirtyCount() > 0 ? Ui.warning() : Ui.muted());
        nav.get(0).setCount(info.families(), false);
        nav.get(1).setCount(info.notes(), false);
        nav.get(2).setCount(info.sources(), false);
        nav.get(3).setCount(info.media(), false);
        nav.get(4).setCount(info.warnings().size(), !info.warnings().isEmpty());
    }

    /// Loads the people again (after an edit, or a change from elsewhere),
    /// keeping the selection and scroll position.
    void refresh() {
        reload(true);
    }

    private void reload(boolean keepPosition) {
        var f = file;
        if (f == null) {
            model.clear();
            count.setText("");
            showEmpty("");
            return;
        }
        int gen = ++generation;
        var q = search.getText();
        var s = sort;
        Bg.run(() -> api.people(f.id(), q, s, LIMIT), people -> {
            if (gen != generation) return;
            fill(people, q, keepPosition);
        }, e -> {
            if (gen != generation) return;
            count.setText("Couldn't load people: " + Api.message(e));
        });
    }

    private void fill(Model.People people, String q, boolean keepPosition) {
        var vp = ((JScrollPane) listHolder.getComponent(0)).getViewport();
        var pos = vp.getViewPosition();
        adjusting = true;
        try {
            model.clear();
            model.addAll(people.people());
            int i = indexOf(selectedKey);
            if (i >= 0) list.setSelectedIndex(i);
            else list.clearSelection();
        } finally {
            adjusting = false;
        }
        if (keepPosition) vp.setViewPosition(pos);
        else if (list.getSelectedIndex() >= 0) list.ensureIndexIsVisible(list.getSelectedIndex());
        else vp.setViewPosition(new java.awt.Point(0, 0));
        boolean searching = q != null && !q.isBlank();
        var total = people.total();
        if (searching) {
            count.setText(total == 0 ? "No matches" : total == 1 ? "1 person matches" : total + " people match");
        } else {
            count.setText(total == 1 ? "1 person" : total + " people" + (total > people.people().size() ? " (first " + people.people().size() + " shown)" : ""));
        }
        if (people.people().isEmpty()) {
            showEmpty(searching ? "Nobody matches “" + q.strip() + "”." : "This file has no people yet.");
        } else {
            ((java.awt.CardLayout) listHolder.getLayout()).show(listHolder, "list");
        }
    }

    private void showEmpty(String text) {
        empty.setText(text);
        ((java.awt.CardLayout) listHolder.getLayout()).show(listHolder, "empty");
    }

    private int indexOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < model.getSize(); i++) {
            if (model.get(i).key().equalsIgnoreCase(key)) return i;
        }
        return -1;
    }

    /// Shows `key` as selected without going anywhere (the window went there).
    void select(String key) {
        selectedKey = key;
        adjusting = true;
        try {
            int i = indexOf(key);
            if (i >= 0) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
            } else {
                list.clearSelection();
            }
        } finally {
            adjusting = false;
        }
    }

    /// Marks the library row for `place`, if it is one.
    void setPlace(Place place) {
        for (var n : nav) n.setActive(n.place.equals(place));
    }

    void focusSearch() {
        search.requestFocusInWindow();
        search.selectAll();
    }

    String sort() {
        return sort;
    }

    private void showSortMenu() {
        var menu = new JPopupMenu();
        var group = new ButtonGroup();
        String[][] options = {{"name", "Name (surname, then given)"}, {"born", "Birth date"}, {"file", "Order in the file"}};
        for (var o : options) {
            var item = new JRadioButtonMenuItem(o[1], o[0].equals(sort));
            group.add(item);
            item.addActionListener(e -> {
                sort = o[0];
                Prefs.sort(sort);
                reload(false);
            });
            menu.add(item);
        }
        menu.show(sortButton, 0, sortButton.getHeight());
    }

    JList<Model.PersonSummary> peopleList() {
        return list;
    }

    JTextField searchField() {
        return search;
    }

    // ---- a row of the library ----------------------------------------------------------

    private static final class NavRow extends JPanel {
        final Place place;
        private final JLabel label;
        private final JLabel badge = Ui.label("");
        private boolean active, hover, alert;

        NavRow(Icon icon, String text, Place place, Consumer<Place> go) {
            super(new BorderLayout(8, 0));
            this.place = place;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
            label = new JLabel(text, icon, SwingConstants.LEFT);
            label.setIconTextGap(10);
            add(label, BorderLayout.CENTER);
            badge.setFont(Ui.font(-1.5f, Font.PLAIN));
            add(badge, BorderLayout.EAST);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (isEnabled()) go.accept(place);
                }
            });
        }

        void setCount(long n, boolean alert) {
            this.alert = alert;
            badge.setText(n < 0 ? "" : String.valueOf(n));
            repaint();
        }

        void setActive(boolean a) {
            active = a;
            repaint();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            label.setEnabled(enabled);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (active || hover && isEnabled()) {
                g2.setColor(active ? Ui.alpha(Ui.accent(), Ui.dark() ? 70 : 40) : Ui.alpha(Ui.foreground(), Ui.dark() ? 22 : 14));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            }
            g2.dispose();
            label.setForeground(Ui.foreground());
            badge.setForeground(alert ? Ui.warning() : Ui.muted());
        }
    }

    JComponent focusTarget() {
        return list;
    }
}
