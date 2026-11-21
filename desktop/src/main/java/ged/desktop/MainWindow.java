package ged.desktop;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// The app's window: a toolbar, the sidebar (file, search, people, library),
/// the main area showing one [Place] at a time with back/forward history, and
/// a status bar. It owns every action pages and menus can take, and keeps
/// all daemon calls off the EDT.
final class MainWindow extends JFrame implements Actions {

    static final String APP_NAME = "gedcom-shell";
    private static final int MENU = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    private final Api api;
    private final Images images;
    private final Sidebar sidebar;
    private final StatusBar statusBar = new StatusBar();
    private final Toast toast = new Toast();
    private final JPanel pageHolder = new JPanel(new BorderLayout());
    private final JPanel toolbar = new JPanel();
    private final JSplitPane split;
    private final History<Place> history = new History<>();
    private final ChangeWatcher watcher;
    private final Map<String, javax.swing.JDialog> openEditors = new HashMap<>();

    private Theme theme;
    private Theme currentLaf;
    private boolean followSystem = Prefs.theme().isEmpty();
    private boolean checkingTheme;
    private Model.FileInfo file;
    private Place current = new Place.Welcome();
    private Model.Person currentPerson;
    private int generation;

    // toolbar
    private final JButton backButton, forwardButton, homeButton, editButton, addButton, saveButton, themeButton;
    private final JToggleButton personView, ancestorsView, descendantsView;
    private final JLabel crumb = Ui.label("");
    private int dirtyCount;

    // menu items whose state follows the page
    private final List<Runnable> menuState = new ArrayList<>();

    MainWindow(Theme theme, Connection conn) {
        super(APP_NAME);
        this.theme = theme;
        this.currentLaf = theme;
        this.api = new Api(conn);
        this.images = new Images(api);
        AppIcon.applyTo(this);
        getRootPane().putClientProperty("apple.awt.windowTitleVisible", true);

        // ---- toolbar ----------------------------------------------------------------
        backButton = Ui.toolButton(Icons.back(16), "Back (⌘[)", this::back);
        forwardButton = Ui.toolButton(Icons.forward(16), "Forward (⌘])", this::forward);
        homeButton = Ui.toolButton(Icons.home(16), "Home person (⇧⌘H)", this::goHome);
        personView = viewToggle(Icons.user(16), "Person", "Person page (⌘1)", () -> switchView(0));
        ancestorsView = viewToggle(Icons.ancestors(16), "Ancestors", "Ancestors chart (⌘2)", () -> switchView(1));
        descendantsView = viewToggle(Icons.descendants(16), "Descendants", "Descendants chart (⌘3)", () -> switchView(2));
        var views = new ButtonGroup();
        views.add(personView);
        views.add(ancestorsView);
        views.add(descendantsView);
        editButton = Ui.toolButton(Icons.edit(16), "Edit (⌘E)", this::editCurrent);
        editButton.setText("Edit");
        addButton = Ui.toolButton(Icons.userPlus(16), "Add or link a relative", this::showAddMenu);
        addButton.setText("Relatives");
        saveButton = Ui.toolButton(new BadgeIcon(Icons.save(16)), "Save (⌘S)", this::save);
        saveButton.setText("Save");
        themeButton = Ui.toolButton(null, null, this::toggleTheme);
        buildToolbar();

        // ---- main area -----------------------------------------------------------------
        sidebar = new Sidebar(api, images, this, this::showFileMenu);
        sidebar.setMinimumSize(new Dimension(220, 100));
        pageHolder.setBackground(Ui.pageBackground());
        var stage = new JLayeredPane() {
            @Override
            public void doLayout() {
                pageHolder.setBounds(0, 0, getWidth(), getHeight());
                if (toast.isVisible()) {
                    var d = toast.sizeFor(getWidth() - 48);
                    toast.setBounds((getWidth() - d.width) / 2, getHeight() - d.height - 22, d.width, d.height);
                }
            }
        };
        stage.add(pageHolder, JLayeredPane.DEFAULT_LAYER);
        stage.add(toast, JLayeredPane.POPUP_LAYER);
        toast.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                stage.doLayout();
            }
        });
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, stage);
        split.setDividerLocation(Prefs.sidebarWidth());
        split.setDividerSize(1);
        split.setContinuousLayout(true);
        split.setBorder(null);

        var root = new JPanel(new BorderLayout());
        root.add(toolbar, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(statusBar, BorderLayout.SOUTH);
        setContentPane(root);
        setJMenuBar(menuBar());
        applyChrome();

        conn.onStatus(statusBar::connection);
        statusBar.connection(conn.status());
        conn.onStatus(s -> {
            if (s.state() == Connection.State.LOST) {
                toast.show("Lost the connection to the daemon; reconnecting… Nothing here is lost while it is away.", true, null, null);
            }
        });
        watcher = new ChangeWatcher(api, new ChangeWatcher.Listener() {
            @Override
            public void changed(String fileId, long revision) {
                if (file != null && file.id().equals(fileId)) changedElsewhere();
            }

            @Override
            public void gone(String fileId, String message) {
                if (file != null && file.id().equals(fileId)) {
                    toast.show(file.name() + " was closed in the daemon (by another window or the shell).", true, "Reopen",
                            () -> openFile(file.path()));
                }
            }

            @Override
            public void reconnected() {
                images.clear();
                toast.show("Reconnected to the daemon.", false, null, null);
                if (file != null) openFile(file.path());
                else show(current, true);
            }
        });

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestQuit(MainWindow.this::quitNow, () -> { });
            }
        });
        installDesktopHandlers();
        if (Prefs.enabled) {
            var themeTimer = new javax.swing.Timer(10_000, e -> checkSystemTheme());
            themeTimer.start();
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowActivated(WindowEvent e) {
                    checkSystemTheme();
                }
            });
        } else {
            followSystem = false;
        }
        setMinimumSize(new Dimension(900, 560));
        Prefs.bounds().ifPresentOrElse(this::setBounds, () -> {
            setSize(1280, 820);
            setLocationRelativeTo(null);
        });
        updateToolbar();
    }

    // ---- start -----------------------------------------------------------------------

    /// Shows the file given on the command line, else the last one, else the welcome page.
    void start(String fileArg) {
        watcher.start();
        if (fileArg != null) {
            openFile(Path.of(fileArg).toAbsolutePath().normalize().toString());
            return;
        }
        var last = Prefs.lastFile();
        if (last.isPresent() && Files.isRegularFile(Path.of(last.get()))) {
            openFile(last.get());
        } else {
            go(new Place.Welcome());
        }
    }

    // ---- toolbar ---------------------------------------------------------------------

    private JToggleButton viewToggle(Icon icon, String text, String tip, Runnable r) {
        var b = new JToggleButton(text, icon);
        Ui.styleToolButton(b, tip);
        b.addActionListener(e -> r.run());
        return b;
    }

    private void buildToolbar() {
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        toolbar.add(backButton);
        toolbar.add(Box.createHorizontalStrut(2));
        toolbar.add(forwardButton);
        toolbar.add(separator());
        toolbar.add(homeButton);
        toolbar.add(separator());
        toolbar.add(personView);
        toolbar.add(Box.createHorizontalStrut(2));
        toolbar.add(ancestorsView);
        toolbar.add(Box.createHorizontalStrut(2));
        toolbar.add(descendantsView);
        toolbar.add(Box.createHorizontalStrut(14));
        crumb.setFont(Ui.font(-0.5f, Font.PLAIN));
        toolbar.add(crumb);
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(editButton);
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(addButton);
        toolbar.add(separator());
        toolbar.add(saveButton);
        toolbar.add(separator());
        toolbar.add(themeButton);
        applyThemeButtonFace();
    }

    private JComponent separator() {
        var s = new JSeparator(SwingConstants.VERTICAL);
        s.setMaximumSize(new Dimension(1, 20));
        s.setPreferredSize(new Dimension(1, 20));
        var p = Ui.transparent(new java.awt.GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        p.add(s);
        p.setMaximumSize(new Dimension(17, 28));
        return p;
    }

    private void applyChrome() {
        toolbar.setBackground(Ui.chromeBackground());
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.hairline()),
                BorderFactory.createEmptyBorder(6, 8, 6, 10)));
        crumb.setForeground(Ui.muted());
        pageHolder.setBackground(Ui.pageBackground());
        sidebar.applyColors();
        statusBar.applyColors();
        split.setBackground(Ui.hairline());
        for (var b : List.<AbstractButton>of(personView, ancestorsView, descendantsView)) {
            b.putClientProperty(FlatClientProperties.STYLE,
                    "toolbar.selectedBackground: " + hex(Ui.mix(Ui.chromeBackground(), Ui.accent(), Ui.dark() ? 0.35 : 0.18))
                            + "; selectedForeground: " + hex(Ui.dark() ? Color.WHITE : Ui.mix(Ui.accent(), Color.BLACK, 0.3)));
        }
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void applyThemeButtonFace() {
        boolean dark = theme.dark();
        themeButton.setIcon(dark ? Icons.sun(16) : Icons.moon(16));
        themeButton.setToolTipText(dark ? "Switch to the light theme" : "Switch to the dark theme");
    }

    private void toggleTheme() {
        setTheme(theme.other());
    }

    /// The user chose a theme: use it, and keep it for next time.
    void setTheme(Theme t) {
        followSystem = false;
        Prefs.theme(t);
        applyTheme(t);
        updateToolbar();
    }

    /// Back to following the system's appearance, now and live.
    private void followSystemTheme() {
        followSystem = true;
        Prefs.theme(null);
        checkSystemTheme();
    }

    /// While following the system, picks up a change of its appearance: on a
    /// slow timer and whenever the window comes to the front (a cheap check).
    private void checkSystemTheme() {
        if (!followSystem || checkingTheme) return;
        checkingTheme = true;
        Bg.run(() -> Theme.detect(System.getProperty("os.name", "")), detected -> {
            checkingTheme = false;
            if (followSystem && detected.isPresent() && detected.get() != theme) applyTheme(detected.get());
        }, e -> checkingTheme = false);
    }

    private void applyTheme(Theme t) {
        if (t == theme && theme == currentLaf) return;
        theme = t;
        currentLaf = t;
        theme.applyLive();
        applyChrome();
        applyThemeButtonFace();
        show(current, true);
    }

    Theme theme() {
        return theme;
    }

    private void updateToolbar() {
        boolean hasFile = file != null;
        backButton.setEnabled(history.canGoBack());
        forwardButton.setEnabled(history.canGoForward());
        homeButton.setEnabled(hasFile && !file.home().isBlank());
        var key = current.personKey();
        boolean person = hasFile && key != null;
        personView.setEnabled(person);
        ancestorsView.setEnabled(person);
        descendantsView.setEnabled(person);
        personView.setSelected(current instanceof Place.PersonPage);
        ancestorsView.setSelected(current instanceof Place.Ancestors);
        descendantsView.setSelected(current instanceof Place.Descendants);
        if (!person) {
            // a ButtonGroup keeps one selected; clearing it shows none
            ((javax.swing.DefaultButtonModel) personView.getModel()).getGroup().clearSelection();
        }
        editButton.setEnabled(person || current instanceof Place.FamilyPage || current instanceof Place.RecordPage);
        editButton.setToolTipText(current instanceof Place.FamilyPage ? "Edit this family (⌘E)"
                : current instanceof Place.RecordPage ? "Edit this record (⌘E)" : "Edit this person (⌘E)");
        addButton.setEnabled(person);
        saveButton.setEnabled(hasFile);
        saveButton.setToolTipText(dirtyCount == 0 ? "Nothing to save (⌘S)"
                : dirtyCount == 1 ? "Save 1 unsaved edit (⌘S)" : "Save " + dirtyCount + " unsaved edits (⌘S)");
        crumb.setText(crumbText());
        menuState.forEach(Runnable::run);
        setTitle(file == null ? APP_NAME : file.name() + (dirtyCount > 0 ? " — edited" : "") + " — " + APP_NAME);
        getRootPane().putClientProperty("Window.documentModified", dirtyCount > 0);
        if (file != null) getRootPane().putClientProperty("Window.documentFile", new java.io.File(file.path()));
    }

    private String crumbText() {
        if (file == null) return "";
        return switch (current) {
            case Place.PersonPage p -> currentPerson != null && currentPerson.key().equalsIgnoreCase(p.key()) ? currentPerson.summary().displayName() : "";
            case Place.FamilyPage f -> "Family " + f.key();
            case Place.RecordPage r -> "Record " + r.key();
            case Place.RecordList r -> Place.Kinds.plural(r.kind());
            case Place.Warnings w -> "Warnings";
            default -> "";
        };
    }

    /// The save icon with the number of unsaved edits on its corner.
    private final class BadgeIcon implements Icon {
        private final Icon base;

        BadgeIcon(Icon base) {
            this.base = base;
        }

        @Override public int getIconWidth() { return base.getIconWidth() + (dirtyCount > 0 ? 6 : 0); }
        @Override public int getIconHeight() { return base.getIconHeight(); }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            base.paintIcon(c, g, x, y);
            if (dirtyCount <= 0) return;
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            var text = dirtyCount > 99 ? "99+" : String.valueOf(dirtyCount);
            g2.setFont(Ui.base().deriveFont(Font.BOLD, 9.5f));
            var fm = g2.getFontMetrics();
            int h = 14, w = Math.max(h, fm.stringWidth(text) + 8);
            int bx = x + base.getIconWidth() - w / 2 + 3, by = y - 5;
            g2.setColor(Ui.chromeBackground());
            g2.fillRoundRect(bx - 2, by - 2, w + 4, h + 4, h + 4, h + 4);
            g2.setColor(new Color(0xFF453A));
            g2.fillRoundRect(bx, by, w, h, h, h);
            g2.setColor(Color.WHITE);
            g2.drawString(text, bx + (w - fm.stringWidth(text)) / 2f, by + (h + fm.getAscent() - fm.getDescent()) / 2f);
            g2.dispose();
        }
    }

    // ---- menus -----------------------------------------------------------------------

    private JMenuBar menuBar() {
        var bar = new JMenuBar();
        boolean mac = Theme.isMac();

        var fileMenu = new JMenu("File");
        fileMenu.add(item("Open…", KeyEvent.VK_O, 0, this::chooseFile));
        var recent = new JMenu("Open Recent");
        fileMenu.add(recent);
        fileMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                fillRecent(recent);
            }

            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        });
        fillRecent(recent);
        fileMenu.addSeparator();
        var save = item("Save", KeyEvent.VK_S, 0, this::save);
        var saveAs = item("Save As…", KeyEvent.VK_S, KeyEvent.SHIFT_DOWN_MASK, this::saveAs);
        var close = item("Close File", KeyEvent.VK_W, KeyEvent.SHIFT_DOWN_MASK, this::closeFile);
        fileMenu.add(save);
        fileMenu.add(saveAs);
        fileMenu.addSeparator();
        fileMenu.add(close);
        menuState.add(() -> {
            save.setEnabled(file != null);
            saveAs.setEnabled(file != null);
            close.setEnabled(file != null);
        });
        if (!mac) {
            fileMenu.addSeparator();
            fileMenu.add(item("Quit", KeyEvent.VK_Q, 0, () -> requestQuit(this::quitNow, () -> { })));
        }
        bar.add(fileMenu);

        var edit = new JMenu("Edit");
        var editItem = item("Edit Person…", KeyEvent.VK_E, 0, this::editCurrent);
        edit.add(editItem);
        var add = new JMenu("Add Relative");
        for (var r : RelativeDialogs.RELATIONS) {
            add.add(item(r.label() + "…", 0, 0, () -> addRelative(current.personKey(), r.code())));
        }
        edit.add(add);
        var link = item("Link Existing Person…", KeyEvent.VK_L, 0, () -> linkExisting(current.personKey()));
        edit.add(link);
        var unlink = new JMenu("Unlink");
        edit.add(unlink);
        unlink.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                fillUnlink(unlink);
            }

            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        });
        edit.addSeparator();
        var newPerson = item("New Person…", KeyEvent.VK_N, KeyEvent.SHIFT_DOWN_MASK, this::newPerson);
        edit.add(newPerson);
        menuState.add(() -> {
            boolean person = file != null && current.personKey() != null;
            editItem.setText(current instanceof Place.FamilyPage ? "Edit Family…"
                    : current instanceof Place.RecordPage ? "Edit Record…" : "Edit Person…");
            editItem.setEnabled(person || current instanceof Place.FamilyPage || current instanceof Place.RecordPage);
            add.setEnabled(person);
            link.setEnabled(person);
            unlink.setEnabled(current instanceof Place.PersonPage && currentPerson != null && !currentPerson.relatives().isEmpty());
            newPerson.setEnabled(file != null);
        });
        bar.add(edit);

        var view = new JMenu("View");
        var v1 = item("Person", KeyEvent.VK_1, 0, () -> switchView(0));
        var v2 = item("Ancestors", KeyEvent.VK_2, 0, () -> switchView(1));
        var v3 = item("Descendants", KeyEvent.VK_3, 0, () -> switchView(2));
        view.add(v1);
        view.add(v2);
        view.add(v3);
        view.addSeparator();
        var lists = new ArrayList<JMenuItem>();
        lists.add(item("Families", 0, 0, () -> go(new Place.RecordList("families"))));
        lists.add(item("Notes", 0, 0, () -> go(new Place.RecordList("notes"))));
        lists.add(item("Sources", 0, 0, () -> go(new Place.RecordList("sources"))));
        lists.add(item("Media", 0, 0, () -> go(new Place.RecordList("media"))));
        lists.add(item("Warnings", 0, 0, () -> go(new Place.Warnings())));
        lists.forEach(view::add);
        view.addSeparator();
        var reload = item("Reload", KeyEvent.VK_R, 0, () -> refreshAll(true));
        view.add(reload);
        var themeMenu = new JMenu("Appearance");
        var themes = new ButtonGroup();
        var system = new javax.swing.JRadioButtonMenuItem("Follow the System");
        var light = new javax.swing.JRadioButtonMenuItem("Light");
        var dark = new javax.swing.JRadioButtonMenuItem("Dark");
        system.addActionListener(e -> followSystemTheme());
        light.addActionListener(e -> setTheme(Theme.LIGHT));
        dark.addActionListener(e -> setTheme(Theme.DARK));
        for (var i : List.of(system, light, dark)) {
            themes.add(i);
            themeMenu.add(i);
        }
        view.add(themeMenu);
        var themeItem = item("Switch Theme", KeyEvent.VK_D, KeyEvent.SHIFT_DOWN_MASK, this::toggleTheme);
        view.add(themeItem);
        menuState.add(() -> {
            boolean person = file != null && current.personKey() != null;
            v1.setEnabled(person);
            v2.setEnabled(person);
            v3.setEnabled(person);
            lists.forEach(i -> i.setEnabled(file != null));
            reload.setEnabled(file != null);
            themeItem.setText(theme.dark() ? "Switch to Light" : "Switch to Dark");
            system.setSelected(followSystem);
            light.setSelected(!followSystem && theme == Theme.LIGHT);
            dark.setSelected(!followSystem && theme == Theme.DARK);
        });
        bar.add(view);

        var goMenu = new JMenu("Go");
        var back = item("Back", KeyEvent.VK_OPEN_BRACKET, 0, this::back);
        var fwd = item("Forward", KeyEvent.VK_CLOSE_BRACKET, 0, this::forward);
        var home = item("Home Person", KeyEvent.VK_H, KeyEvent.SHIFT_DOWN_MASK, this::goHome);
        var find = item("Find Person", KeyEvent.VK_F, 0, sidebar::focusSearch);
        goMenu.add(back);
        goMenu.add(fwd);
        goMenu.addSeparator();
        goMenu.add(home);
        goMenu.add(find);
        goMenu.add(item("Welcome", 0, 0, () -> go(new Place.Welcome())));
        menuState.add(() -> {
            back.setEnabled(history.canGoBack());
            fwd.setEnabled(history.canGoForward());
            home.setEnabled(file != null && !file.home().isBlank());
            find.setEnabled(file != null);
        });
        bar.add(goMenu);

        if (!mac) {
            var help = new JMenu("Help");
            help.add(item("About " + APP_NAME, 0, 0, this::about));
            bar.add(help);
        }
        return bar;
    }

    private static JMenuItem item(String text, int key, int extraMask, Runnable r) {
        var i = new JMenuItem(text);
        if (key != 0) i.setAccelerator(KeyStroke.getKeyStroke(key, MENU | extraMask));
        i.addActionListener(e -> r.run());
        return i;
    }

    private void fillRecent(JMenu menu) {
        Bg.run(api::files, files -> {
            menu.removeAll();
            for (var f : files.open()) {
                var i = new JMenuItem(f.name() + (f.dirtyCount() > 0 ? "  •" : ""));
                i.setToolTipText(f.path());
                i.addActionListener(e -> openFile(f.path()));
                menu.add(i);
            }
            var closed = files.recent().stream().filter(r -> !r.open()).toList();
            if (!files.open().isEmpty() && !closed.isEmpty()) menu.addSeparator();
            for (var r : closed) {
                var i = new JMenuItem(r.name());
                i.setToolTipText(r.path());
                i.setEnabled(r.exists());
                i.addActionListener(e -> openFile(r.path()));
                menu.add(i);
            }
            if (menu.getItemCount() == 0) {
                var none = new JMenuItem("No recent files");
                none.setEnabled(false);
                menu.add(none);
            }
        }, e -> { });
    }

    private void fillUnlink(JMenu menu) {
        menu.removeAll();
        var p = currentPerson;
        if (p == null || !(current instanceof Place.PersonPage)) return;
        for (var r : p.relatives()) {
            var i = new JMenuItem(r.role() + ": " + r.person().displayName() + "…");
            i.addActionListener(e -> unlink(p, r));
            menu.add(i);
        }
    }

    /// The sidebar's file button: the open files, recent ones, and Open….
    private void showFileMenu() {
        Bg.run(api::files, files -> {
            var menu = new JPopupMenu();
            for (var f : files.open()) {
                var i = new JMenuItem(f.name() + (f.dirtyCount() > 0 ? "   (" + f.dirtyCount() + " unsaved)" : ""),
                        Icons.file(14));
                i.setToolTipText(f.path());
                i.setFont(file != null && f.id().equals(file.id()) ? i.getFont().deriveFont(Font.BOLD) : i.getFont());
                i.addActionListener(e -> openFile(f.path()));
                menu.add(i);
            }
            var closed = files.recent().stream().filter(r -> !r.open() && r.exists()).limit(8).toList();
            if (!closed.isEmpty()) {
                menu.addSeparator();
                var h = new JMenuItem("Recent");
                h.setEnabled(false);
                menu.add(h);
                for (var r : closed) {
                    var i = new JMenuItem(r.name());
                    i.setToolTipText(r.path());
                    i.addActionListener(e -> openFile(r.path()));
                    menu.add(i);
                }
            }
            menu.addSeparator();
            menu.add(item("Open…", 0, 0, this::chooseFile));
            menu.add(item("Welcome page", 0, 0, () -> go(new Place.Welcome())));
            var button = sidebar.getComponent(0);
            menu.show(sidebar, 12, button.getY() + 40);
        }, this::showError);
    }

    // ---- files ---------------------------------------------------------------------------

    @Override
    public void chooseFile() {
        var chooser = new JFileChooser(Prefs.lastDirectory());
        chooser.setDialogTitle("Open a GEDCOM file");
        chooser.setFileFilter(new FileNameExtensionFilter("GEDCOM files (*.ged)", "ged", "GED"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        var f = chooser.getSelectedFile();
        Prefs.lastDirectory(f.getParent());
        openFile(f.getAbsolutePath());
    }

    @Override
    public void openFile(String path) {
        Bg.run(() -> api.open(path), this::setFile, e -> {
            if (Api.status(e) == 422 || Api.status(e) == 404) {
                JOptionPane.showMessageDialog(this, "Couldn't open " + path + ":\n" + Api.message(e), "Can't open the file",
                        JOptionPane.WARNING_MESSAGE);
                if (file == null) go(new Place.Welcome());
            } else {
                showError(e);
            }
        });
    }

    @Override
    public void forget(String id) {
        Bg.run(() -> {
            api.forget(id);
            return null;
        }, x -> show(current, true), this::showError);
    }

    private void setFile(Model.FileInfo info) {
        boolean same = file != null && file.id().equals(info.id());
        file = info;
        dirtyCount = (int) info.dirtyCount();
        Prefs.lastFile(info.path());
        statusBar.file(info);
        watcher.watch(info.id(), info.revision());
        if (same) {
            sidebar.updateInfo(info);
            refreshAll(false);
            return;
        }
        history.clear();
        currentPerson = null;
        sidebar.setFile(info);
        if (!info.home().isBlank()) go(new Place.PersonPage(info.home()));
        else go(new Place.Warnings());
    }

    private void save() {
        if (file == null) return;
        doSave(file, false, null);
    }

    private void doSave(Model.FileInfo f, boolean force, String as) {
        Bg.run(() -> api.save(f.id(), force, as), reply -> {
            boolean saved = Boolean.TRUE.equals(reply.get("saved"));
            watcher.saw(Model.num(reply, "revision"));
            toast.show(saved ? "Saved " + Path.of(Model.str(reply, "path")).getFileName() + "." : "Nothing to save: " + f.name() + " has no unsaved edits.",
                    false, null, null);
            refreshFileInfo();
        }, e -> {
            if (e instanceof ged.client.DaemonClient.ApiException a && a.status == 409) {
                if (Boolean.TRUE.equals(a.body.get("changedOnDisk"))) {
                    int r = JOptionPane.showOptionDialog(this,
                            f.name() + " was changed by another program since it was opened.\n\n"
                                    + "Saving now replaces those changes with the file as it is here, including your edits.\n"
                                    + "To keep the other program's changes instead, close the file without saving and open it again.",
                            "The file changed on disk", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                            new Object[] {"Overwrite", "Cancel"}, "Cancel");
                    if (r == 0) doSave(f, true, as);
                    return;
                }
                if (Boolean.TRUE.equals(a.body.get("exists"))) {
                    int r = JOptionPane.showOptionDialog(this, as + " already exists.\nReplace it?", "Replace the file?",
                            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, new Object[] {"Replace", "Cancel"}, "Cancel");
                    if (r == 0) doSave(f, true, as);
                    return;
                }
            }
            showError(e);
        });
    }

    private void saveAs() {
        if (file == null) return;
        var chooser = new JFileChooser(Path.of(file.path()).getParent().toFile());
        chooser.setDialogTitle("Save a copy as");
        chooser.setSelectedFile(new java.io.File(file.path()));
        chooser.setFileFilter(new FileNameExtensionFilter("GEDCOM files (*.ged)", "ged", "GED"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        var path = chooser.getSelectedFile().getAbsolutePath();
        if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".ged")) path += ".ged";
        doSave(file, false, path);
    }

    private void closeFile() {
        if (file == null) return;
        var f = file;
        if (dirtyCount > 0) {
            int r = JOptionPane.showOptionDialog(this,
                    f.name() + " has " + plural(dirtyCount, "unsaved edit") + ".\n\nSave them before closing the file, or close it and lose them?",
                    "Close " + f.name(), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                    new Object[] {"Save and Close", "Close Without Saving", "Cancel"}, "Save and Close");
            if (r == 2 || r < 0) return;
            if (r == 0) {
                Bg.run(() -> {
                    api.save(f.id(), false, null);
                    api.close(f.id(), false);
                    return null;
                }, x -> afterClose(), this::showError);
                return;
            }
        }
        Bg.run(() -> {
            api.close(f.id(), dirtyCount > 0);
            return null;
        }, x -> afterClose(), this::showError);
    }

    private void afterClose() {
        file = null;
        dirtyCount = 0;
        currentPerson = null;
        history.clear();
        watcher.watch(null, 0);
        Prefs.lastFile(null);
        sidebar.setFile(null);
        statusBar.file(null);
        go(new Place.Welcome());
    }

    private void refreshFileInfo() {
        if (file == null) return;
        var id = file.id();
        Bg.run(() -> api.fileInfo(id), info -> {
            if (file == null || !file.id().equals(info.id())) return;
            file = info;
            dirtyCount = (int) info.dirtyCount();
            // not watcher.saw(info.revision()): a newer edit may have landed after the page
            // began loading, and only the watcher reporting it brings the page up to date
            sidebar.updateInfo(info);
            statusBar.file(info);
            updateToolbar();
            saveButton.repaint();
        }, e -> { });
    }

    // ---- quitting --------------------------------------------------------------------------

    /// Asks before quitting when files have unsaved edits; they stay safe in the
    /// daemon either way, so quitting never loses them.
    void requestQuit(Runnable quit, Runnable cancel) {
        Bg.run(api::files, files -> {
            var dirty = files.open().stream().filter(f -> f.dirtyCount() > 0).toList();
            if (dirty.isEmpty()) {
                quit.run();
                return;
            }
            var names = new StringBuilder();
            for (var f : dirty) names.append("   • ").append(f.name()).append(": ").append(plural((int) f.dirtyCount(), "unsaved edit")).append('\n');
            int r = JOptionPane.showOptionDialog(this,
                    "Some files have edits that are not saved yet:\n\n" + names
                            + "\nThey stay safe in the gedcom-shell daemon after you quit: open the app (or the shell, or the web app) "
                            + "again and they are still there, ready to save. Save them now?",
                    "Unsaved edits", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new Object[] {"Save and Quit", "Quit", "Cancel"}, "Save and Quit");
            if (r == 1) {
                quit.run();
            } else if (r == 0) {
                Bg.run(() -> {
                    for (var f : dirty) api.save(f.id(), false, null);
                    return null;
                }, x -> quit.run(), e -> {
                    showError(e);
                    cancel.run();
                });
            } else {
                cancel.run();
            }
        }, e -> quit.run()); // no daemon: nothing here to lose
    }

    private void quitNow() {
        Prefs.bounds(getBounds());
        Prefs.sidebarWidth(split.getDividerLocation());
        watcher.stop();
        dispose();
        System.exit(0);
    }

    private void installDesktopHandlers() {
        if (!Desktop.isDesktopSupported()) return;
        var d = Desktop.getDesktop();
        try {
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                d.setQuitHandler((e, response) -> requestQuit(response::performQuit, response::cancelQuit));
            }
            if (d.isSupported(Desktop.Action.APP_ABOUT)) d.setAboutHandler(e -> about());
            if (d.isSupported(Desktop.Action.APP_OPEN_FILE)) {
                d.setOpenFileHandler(e -> e.getFiles().stream().findFirst()
                        .ifPresent(f -> SwingUtilities.invokeLater(() -> openFile(f.getAbsolutePath()))));
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // the window's own menu covers these
        }
    }

    private void about() {
        Bg.run(() -> api.connection().get("daemon"), d -> JOptionPane.showMessageDialog(this,
                APP_NAME + " desktop\nBrowse and edit GEDCOM family trees.\n\nDaemon " + Model.str(d, "version")
                        + " (pid " + Model.num(d, "pid") + ") at " + Model.str(d, "bind") + ":" + Model.num(d, "port")
                        + "\nHome: " + Model.str(d, "home"),
                "About " + APP_NAME, JOptionPane.INFORMATION_MESSAGE,
                new javax.swing.ImageIcon(AppIcon.render(128).getScaledInstance(64, 64, java.awt.Image.SCALE_SMOOTH))), this::showError);
    }

    // ---- navigation ------------------------------------------------------------------------

    @Override
    public void go(Place place) {
        if (file == null && !(place instanceof Place.Welcome)) return;
        history.visit(place);
        show(place, false);
    }

    private void back() {
        history.back().ifPresent(p -> show(p, false));
    }

    private void forward() {
        history.forward().ifPresent(p -> show(p, false));
    }

    private void goHome() {
        if (file != null && !file.home().isBlank()) go(new Place.PersonPage(file.home()));
    }

    /// 0 person, 1 ancestors, 2 descendants: the same person, another view.
    private void switchView(int view) {
        var key = current.personKey();
        if (key == null) {
            updateToolbar();
            return;
        }
        go(switch (view) {
            case 1 -> new Place.Ancestors(key, current instanceof Place.Ancestors a ? a.depth() : 4);
            case 2 -> new Place.Descendants(key, current instanceof Place.Descendants d ? d.depth() : 3);
            default -> new Place.PersonPage(key);
        });
    }

    /// Loads and shows `place`. With `refresh`, keeps the scroll position (the
    /// same page, updated).
    private void show(Place place, boolean refresh) {
        int gen = ++generation;
        current = place;
        if (place.personKey() != null) sidebar.select(place.personKey());
        sidebar.setPlace(place);
        updateToolbar();
        var f = file;
        Bg.run(() -> load(f, place), data -> {
            if (gen != generation) return;
            if (data instanceof Model.Person p) currentPerson = p;
            setPage(build(place, data), refresh);
            updateToolbar();
        }, e -> {
            if (gen != generation) return;
            setPage(errorPage(place, e), false);
        });
    }

    private Object load(Model.FileInfo f, Place place) throws IOException {
        if (place instanceof Place.Welcome) return api.files();
        if (f == null) throw new IOException("no file is open");
        var id = f.id();
        return switch (place) {
            case Place.PersonPage p -> api.person(id, p.key());
            case Place.Ancestors a -> api.pedigree(id, a.key(), a.depth());
            case Place.Descendants d -> api.descendants(id, d.key(), d.depth());
            case Place.FamilyPage fp -> api.family(id, fp.key());
            case Place.RecordList r -> r.kind().equals("families")
                    ? RecordViews.familyItems(api.families(id)) : RecordViews.recordItems(api.records(id, r.kind(), null));
            case Place.RecordPage r -> api.record(id, r.key());
            case Place.Warnings w -> api.fileInfo(id);
            case Place.Welcome w -> api.files();
        };
    }

    @SuppressWarnings("unchecked")
    private JComponent build(Place place, Object data) {
        return switch (place) {
            case Place.Welcome w -> WelcomeView.build((Model.Files) data, this);
            case Place.PersonPage p -> PersonView.build((Model.Person) data, this);
            case Place.Ancestors a -> ChartView.ancestors(a, (Model.Pedigree) data, this);
            case Place.Descendants d -> ChartView.descendants(d, (Model.Descendants) data, this);
            case Place.FamilyPage fp -> FamilyView.build((Model.Family) data, this);
            case Place.RecordList r -> RecordViews.list(r.kind(), (List<RecordViews.Item>) data, this);
            case Place.RecordPage r -> RecordViews.record((Model.RecordDetail) data, this);
            case Place.Warnings w -> RecordViews.warnings((Model.FileInfo) data, this);
        };
    }

    private JComponent errorPage(Place place, Exception e) {
        var s = Ui.stack(10);
        var title = Ui.title(Api.status(e) == 404 ? "Not found" : "Couldn't show this page");
        s.add(title);
        var msg = Ui.paragraph(Api.status(e) == 404
                ? "The daemon has no " + place.title().toLowerCase(java.util.Locale.ROOT) + " in this file. "
                + "It may have been removed or renamed elsewhere." : Api.message(e));
        msg.setForeground(Ui.muted());
        s.add(msg);
        var row = Ui.row(14);
        if (history.canGoBack()) row.add(Ui.link("Go back", this::back));
        row.add(Ui.link("Try again", () -> show(place, false)));
        if (file != null && !file.home().isBlank()) row.add(Ui.link("Home person", this::goHome));
        s.add(row);
        return new Ui.Page(s, 720);
    }

    private void setPage(JComponent page, boolean keepScroll) {
        Point old = null;
        if (keepScroll) {
            var sp = findScroll(pageHolder);
            if (sp != null) old = sp.getViewport().getViewPosition();
        }
        JComponent view = page;
        if (page instanceof Ui.Page) {
            var scroll = new JScrollPane(page);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(24);
            scroll.getViewport().setBackground(Ui.pageBackground());
            view = scroll;
        }
        pageHolder.removeAll();
        pageHolder.add(view, BorderLayout.CENTER);
        pageHolder.setBackground(Ui.pageBackground());
        pageHolder.revalidate();
        pageHolder.repaint();
        if (old != null) {
            var restore = old;
            SwingUtilities.invokeLater(() -> {
                var sp = findScroll(pageHolder);
                if (sp != null) {
                    sp.getViewport().validate();
                    sp.getViewport().setViewPosition(restore);
                }
            });
        }
    }

    private static JScrollPane findScroll(java.awt.Container c) {
        for (var k : c.getComponents()) {
            if (k instanceof JScrollPane sp) return sp;
            if (k instanceof java.awt.Container ct) {
                var s = findScroll(ct);
                if (s != null) return s;
            }
        }
        return null;
    }

    /// Something changed in the file (here or elsewhere): reload what is shown.
    private void refreshAll(boolean forceImages) {
        if (forceImages) images.clear();
        sidebar.refresh();
        refreshFileInfo();
        show(current, true);
    }

    /// Tells each open form whose record changed (not those whose didn't), and refreshes the page.
    private void changedElsewhere() {
        var f = file;
        openEditors.forEach((id, d) -> {
            var kind = id.substring(0, id.indexOf(':'));
            var key = id.substring(id.indexOf(':') + 1);
            Bg.run(() -> switch (kind) {
                case "person" -> EditDialog.Content.of(api.person(f.id(), key));
                case "family" -> EditDialog.Content.of(api.family(f.id(), key));
                default -> api.record(f.id(), key);
            }, latest -> tell(d, latest), e -> tell(d, null)); // gone, or unreachable: say it may have changed
        });
        refreshAll(false);
    }

    private static void tell(javax.swing.JDialog d, Object latest) {
        if (!d.isDisplayable()) return;
        if (d instanceof EditDialog e) e.externalChange(latest instanceof EditDialog.Content c ? c : null);
        if (d instanceof RecordEditDialog r) r.externalChange(latest instanceof Model.RecordDetail rd ? rd : null);
    }

    // ---- editing ---------------------------------------------------------------------------

    private void editCurrent() {
        if (current instanceof Place.FamilyPage f) editFamily(f.key());
        else if (current instanceof Place.RecordPage r) editRecord(r.key());
        else if (current.personKey() != null) editPerson(current.personKey());
    }

    @Override
    public void editPerson(String key) {
        if (file == null || key == null) return;
        var f = file;
        Bg.run(() -> new Object[] {api.fields(), api.person(f.id(), key)}, r -> {
            var defs = (Model.FieldDefs) r[0];
            var p = (Model.Person) r[1];
            images.learn(p);
            var dialog = new EditDialog(this, "Edit " + p.summary().displayName(),
                    "Changes go to the daemon right away; save the file (⌘S) to write them.",
                    EditDialog.face(images, p.summary()), "person", "person", defs.person(), defs.dates(), EditDialog.Content.of(p),
                    body -> api.editPerson(f.id(), key, body),
                    reply -> edited(reply, p.summary().displayName()), this::openRecord);
            openEditor("person:" + key, dialog);
        }, this::showError);
    }

    @Override
    public void editFamily(String key) {
        if (file == null || key == null) return;
        var f = file;
        Bg.run(() -> new Object[] {api.fields(), api.family(f.id(), key)}, r -> {
            var defs = (Model.FieldDefs) r[0];
            var fam = (Model.Family) r[1];
            var face = new JLabel(Icons.tinted(Icons.family(32), Ui.accent()));
            var dialog = new EditDialog(this, "Edit " + (fam.title().isBlank() ? "family " + key : fam.title()),
                    "Family " + key + ". Changes go to the daemon right away; save the file (⌘S) to write them.",
                    face, "family", "family", defs.family(), defs.dates(), EditDialog.Content.of(fam),
                    body -> api.editFamily(f.id(), key, body),
                    reply -> edited(reply, "the family"), this::openRecord);
            openEditor("family:" + key, dialog);
        }, this::showError);
    }

    @Override
    public void editRecord(String key) {
        if (file == null || key == null) return;
        var f = file;
        Bg.run(() -> api.record(f.id(), key), r -> {
            var dialog = new RecordEditDialog(this, r, body -> api.editRecord(f.id(), key, body),
                    reply -> edited(reply, r.title().isBlank() ? r.key() : r.title()));
            openEditor("record:" + key, dialog);
        }, this::showError);
    }

    /// A shared note's link in an edit form: its page.
    private void openRecord(String key) {
        go(new Place.RecordPage(key));
    }

    private void openEditor(String id, javax.swing.JDialog dialog) {
        openEditors.put(id, dialog);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                openEditors.remove(id, dialog);
            }
        });
        presenter.accept(dialog);
    }

    /// How an edit form is shown: modally, on screen; tests keep it off screen.
    Consumer<javax.swing.JDialog> presenter = d -> d.setVisible(true);

    private void edited(Map<String, Object> reply, String who) {
        watcher.saw(Model.num(reply, "revision"));
        var changes = Model.list(reply, "changes", m -> Model.str(m, "field"));
        toast.show(changes.isEmpty() ? "No changes." : "Updated " + who + ": " + String.join(", ", labels(changes))
                + ". Save the file (⌘S) to write it.", false, null, null);
        refreshAll(false);
    }

    private List<String> labels(List<String> keys) {
        var out = new java.util.LinkedHashSet<String>();
        Model.FieldDefs defs = null;
        try {
            defs = api.fields();
        } catch (IOException ignored) {
            // cached after the first edit; keys are fine otherwise
        }
        for (var k : keys) {
            String label = EventKinds.describeChange(k);
            if (defs != null) {
                for (var d : defs.person()) if (d.key().equals(k)) label = d.label();
                for (var d : defs.family()) if (d.key().equals(k)) label = d.label();
            }
            out.add(label.toLowerCase(java.util.Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private void withPerson(String key, Consumer<Model.Person> then) {
        if (file == null || key == null) return;
        if (currentPerson != null && currentPerson.key().equalsIgnoreCase(key)) {
            then.accept(currentPerson);
            return;
        }
        var f = file;
        Bg.run(() -> api.person(f.id(), key), then, this::showError);
    }

    @Override
    public void addRelative(String key, String relation) {
        if (file == null || key == null) return;
        var f = file;
        withPerson(key, p -> Bg.run(api::fields, defs -> new RelativeDialogs.AddRelative(this, images, p, relation, defs.dates(),
                (rel, name, sex, fields, fam) -> api.addRelative(f.id(), key, rel, name, sex, fields, fam),
                this::related).setVisible(true), this::showError));
    }

    private void newPerson() {
        if (file == null) return;
        var f = file;
        Bg.run(api::fields, defs -> new RelativeDialogs.AddRelative(this, images, null, null, defs.dates(),
                (rel, name, sex, fields, fam) -> api.addPerson(f.id(), name, sex, fields),
                this::related).setVisible(true), this::showError);
    }

    @Override
    public void linkExisting(String key) {
        if (file == null || key == null) return;
        var f = file;
        withPerson(key, p -> new RelativeDialogs.Link(this, images, p,
                q -> api.people(f.id(), q, "name", 300),
                (person, as) -> api.link(f.id(), key, person, as),
                this::related).setVisible(true));
    }

    @Override
    public void unlink(Model.Person person, Model.Relative relative) {
        if (file == null) return;
        var f = file;
        int r = JOptionPane.showOptionDialog(this, RelativeDialogs.unlinkQuestion(person.summary(), relative),
                "Unlink " + relative.person().displayName() + "?", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, new Object[] {"Unlink", "Cancel"}, "Cancel");
        if (r != 0) return;
        Bg.run(() -> api.unlink(f.id(), person.key(), relative.unlinkPath()), this::related, this::showError);
    }

    /// After add, link or unlink: say what happened and refresh.
    private void related(Model.Outcome o) {
        watcher.saw(o.revision());
        var newPerson = o.person();
        boolean added = newPerson != null && (currentPerson == null || !newPerson.key().equalsIgnoreCase(currentPerson.key()));
        toast.show(o.message().isBlank() ? "Done." : o.message(), false,
                added ? "Open " + newPerson.summary().given() : null,
                added ? () -> openPerson(newPerson.key()) : null);
        refreshAll(false);
    }

    private void showAddMenu() {
        var key = current.personKey();
        if (key == null) return;
        var menu = new JPopupMenu();
        for (var r : RelativeDialogs.RELATIONS) {
            menu.add(item("Add " + r.label().toLowerCase(java.util.Locale.ROOT) + "…", 0, 0, () -> addRelative(key, r.code())));
        }
        menu.addSeparator();
        var link = item("Link an existing person…", 0, 0, () -> linkExisting(key));
        link.setIcon(Icons.link(14));
        menu.add(link);
        var p = currentPerson;
        if (current instanceof Place.PersonPage && p != null && !p.relatives().isEmpty()) {
            var unlink = new JMenu("Unlink");
            unlink.setIcon(Icons.unlink(14));
            for (var r : p.relatives()) {
                var i = new JMenuItem(r.role() + ": " + r.person().displayName() + "…");
                i.addActionListener(e -> unlink(p, r));
                unlink.add(i);
            }
            menu.add(unlink);
        }
        menu.show(addButton, 0, addButton.getHeight());
    }

    // ---- other page actions ------------------------------------------------------------------

    @Override
    public void showPhotos(List<Model.Photo> photos, int index, String title) {
        if (photos.isEmpty()) return;
        new PhotoViewer(this, images, photos, index, title).setVisible(true);
    }

    @Override
    public void openExternally(String url, String name) {
        Bg.run(() -> {
            var dir = Path.of(System.getProperty("java.io.tmpdir"), "gedcom-shell-media");
            Files.createDirectories(dir);
            var safe = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
            var target = dir.resolve(safe.isBlank() ? "media" : safe);
            Files.write(target, api.bytes(url));
            Desktop.getDesktop().open(target.toFile());
            return null;
        }, x -> { }, this::showError);
    }

    @Override
    public void resolve(String key) {
        if (file == null) return;
        var id = file.id();
        Bg.run(() -> {
            try {
                api.person(id, key);
                return (Place) new Place.PersonPage(key);
            } catch (ged.client.DaemonClient.ApiException e) {
                if (e.status != 404) throw e;
            }
            try {
                api.family(id, key);
                return (Place) new Place.FamilyPage(key);
            } catch (ged.client.DaemonClient.ApiException e) {
                if (e.status != 404) throw e;
            }
            api.record(id, key);
            return (Place) new Place.RecordPage(key);
        }, this::go, e -> toast.show(Api.status(e) == 404 ? "There is no person, family or record " + key + " in this file." : Api.message(e),
                true, null, null));
    }

    @Override
    public void personMenu(Component c, int x, int y, Model.PersonSummary p, Model.Person context, Model.Relative relative) {
        var menu = new JPopupMenu();
        var first = p.given().isBlank() ? p.displayName() : p.given().strip().split("\\s+")[0];
        menu.add(item("Open " + p.displayName(), 0, 0, () -> openPerson(p.key())));
        menu.add(item("Ancestors of " + first, 0, 0, () -> go(new Place.Ancestors(p.key(), 4))));
        menu.add(item("Descendants of " + first, 0, 0, () -> go(new Place.Descendants(p.key(), 3))));
        menu.addSeparator();
        menu.add(item("Edit " + first + "…", 0, 0, () -> editPerson(p.key())));
        menu.add(item("Add a relative of " + first + "…", 0, 0, () -> addRelative(p.key(), null)));
        if (context != null && relative != null) {
            menu.addSeparator();
            var u = item("Unlink " + first + " from " + context.summary().given() + "…", 0, 0, () -> unlink(context, relative));
            u.setIcon(Icons.unlink(14));
            menu.add(u);
        }
        menu.show(c, x, y);
    }

    @Override
    public Images images() {
        return images;
    }

    private void showError(Exception e) {
        toast.show(Api.message(e), true, null, null);
    }

    private static String plural(int n, String word) {
        return n == 1 ? "1 " + word : n + " " + word + "s";
    }

    @Override
    public void dispose() {
        watcher.stop();
        super.dispose();
    }

    // ---- for the screenshot harness ---------------------------------------------------------

    Toast toast() {
        return toast;
    }

    Sidebar sidebar() {
        return sidebar;
    }

    Place current() {
        return current;
    }

    Model.FileInfo file() {
        return file;
    }

    /// The page shown in the main area (for tests).
    JComponent page() {
        return pageHolder;
    }

    /// The toolbar's location text (for tests).
    String crumb() {
        return crumb.getText();
    }
}
