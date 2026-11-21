package ged.desktop;

import ged.client.DaemonClient;
import ged.daemon.Home;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/// A development tool, not a test: renders the main window, the charts and the
/// dialogs off-screen to PNGs, in the light and the dark theme, against a
/// running daemon (started if needed, from `-Dgedcom.shell.home`).
///
/// `java -Dgedcom.shell.home=… -Dgedcom.desktop.noPrefs=true -cp … ged.desktop.Screenshots OUT_DIR FILE.ged [WARNINGS.ged]`
public final class Screenshots {

    public static void main(String[] args) throws Exception {
        var out = new File(args[0]);
        out.mkdirs();
        var gedFile = Path.of(args[1]).toAbsolutePath().toString();
        var warningsFile = args.length > 2 ? Path.of(args[2]).toAbsolutePath().toString() : null;
        Prefs.enabled = false;
        System.setProperty("apple.awt.application.name", MainWindow.APP_NAME);
        if (Theme.isMac()) System.setProperty("apple.laf.useScreenMenuBar", "true");
        Theme.LIGHT.install();
        var home = Home.fromEnvironment();
        var client = DaemonClient.ensure(home, Duration.ofSeconds(25));
        var conn = new Connection(home, client);
        var api = new Api(conn);
        var info = api.open(gedFile);
        var id = info.id();

        MainWindow[] w = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new MainWindow(Theme.LIGHT, conn);
            w[0].setSize(1280, 820);
            w[0].pack();
            w[0].setSize(1280, 820);
            w[0].start(gedFile);
        });
        var win = w[0];
        for (var theme : new Theme[] {Theme.LIGHT, Theme.DARK}) {
            var t = theme.name().toLowerCase(java.util.Locale.ROOT);
            SwingUtilities.invokeAndWait(() -> win.setTheme(theme));
            settle(2500);
            // let the pictures arrive, then show the page again with them
            nav(win, new Place.PersonPage("I2"));
            settle(2000);
            nav(win, new Place.PersonPage(info.home()));
            shoot(win, new File(out, "main-" + t + ".png"));
            nav(win, new Place.Ancestors(info.home(), 4));
            shoot(win, new File(out, "pedigree-" + t + ".png"));
            nav(win, new Place.Descendants("I8", 3));
            shoot(win, new File(out, "descendants-" + t + ".png"));
            nav(win, new Place.FamilyPage("F2"));
            shoot(win, new File(out, "family-" + t + ".png"));
            nav(win, new Place.RecordPage("M3"));
            shoot(win, new File(out, "media-" + t + ".png"));
            nav(win, new Place.RecordPage("S1"));
            shoot(win, new File(out, "source-" + t + ".png"));
            nav(win, new Place.RecordList("media"));
            settle(800);
            shoot(win, new File(out, "media-list-" + t + ".png"));
            nav(win, new Place.RecordList("families"));
            shoot(win, new File(out, "families-" + t + ".png"));
            nav(win, new Place.Welcome());
            shoot(win, new File(out, "welcome-" + t + ".png"));

            // search with a nickname hit, and a toast
            SwingUtilities.invokeAndWait(() -> win.go(new Place.PersonPage("I2")));
            SwingUtilities.invokeAndWait(() -> win.sidebar().searchField().setText("mimi"));
            settle(1500);
            SwingUtilities.invokeAndWait(() -> win.toast().show(
                    "added Ana Bianchi I7 · given: Ana · surname: Bianchi · sex: F\nAna Bianchi I7 is now a child of Marco Bianchi in F2",
                    false, "Open Ana", () -> { }));
            settle(1500);
            shoot(win, new File(out, "search-toast-" + t + ".png"));
            SwingUtilities.invokeAndWait(() -> {
                win.sidebar().searchField().setText("");
                win.toast().setVisible(false);
            });

            // the edit dialogs: a person's with a refused date, a family's, a source's and a note's
            var defs = api.fields();
            var person = api.person(id, "I1");
            EditDialog[] d = new EditDialog[1];
            SwingUtilities.invokeAndWait(() -> {
                d[0] = new EditDialog(win, "Edit " + person.summary().displayName(),
                        "Changes go to the daemon right away; save the file (⌘S) to write them.",
                        EditDialog.face(win.images(), person.summary()), "person", "person", defs.person(), defs.dates(),
                        EditDialog.Content.of(person), body -> Map.of(), r -> { }, k -> { });
                var resi = person.events().stream().filter(e -> e.tag().equals("RESI")).findFirst();
                d[0].showRefusal(resi.map(e -> e.ref()).orElse("BIRT:0")
                        + ".date: 'sometime in june' is not a GEDCOM date; use 12 JUN 1850, JUN 1850, 1850, ABT 1850, …");
            });
            settle(800);
            shootWindow(d[0], new File(out, "edit-" + t + ".png"));
            SwingUtilities.invokeAndWait(() -> d[0].setSize(d[0].getWidth(), 1500));
            shootWindow(d[0], new File(out, "edit-tall-" + t + ".png"));
            SwingUtilities.invokeAndWait(() -> d[0].dispose());
            var family = api.family(id, "F2");
            SwingUtilities.invokeAndWait(() -> {
                d[0] = new EditDialog(win, "Edit " + family.title(), "Family F2.",
                        new javax.swing.JLabel(Icons.tinted(Icons.family(32), Ui.accent())), "family", "family", defs.family(),
                        defs.dates(), EditDialog.Content.of(family), body -> Map.of(), r -> { }, k -> { });
                d[0].addNewEvent("EVEN");
            });
            settle(500);
            shootWindow(d[0], new File(out, "edit-family-" + t + ".png"));
            SwingUtilities.invokeAndWait(() -> d[0].dispose());
            for (var key : new String[] {"S1", "N1"}) {
                var rec = api.record(id, key);
                RecordEditDialog[] rd = new RecordEditDialog[1];
                SwingUtilities.invokeAndWait(() -> rd[0] = new RecordEditDialog(win, rec, body -> Map.of(), r -> { }));
                settle(500);
                shootWindow(rd[0], new File(out, "edit-" + key + "-" + t + ".png"));
                SwingUtilities.invokeAndWait(() -> rd[0].dispose());
            }

            // add a relative, link an existing person
            SwingUtilities.invokeAndWait(() -> {
                var add = new RelativeDialogs.AddRelative(win, win.images(), person, "child", defs.dates(), (a, b, c, e, f) -> null, o -> { });
                shootWindowNow(add, new File(out, "add-relative-" + t + ".png"));
                add.dispose();
            });
            RelativeDialogs.Link[] link = new RelativeDialogs.Link[1];
            SwingUtilities.invokeAndWait(() -> link[0] = new RelativeDialogs.Link(win, win.images(), person,
                    q -> api.people(id, q, "name", 300), (a, b) -> null, o -> { }));
            settle(1500);
            shootWindow(link[0], new File(out, "link-" + t + ".png"));
            SwingUtilities.invokeAndWait(() -> link[0].dispose());

            if (person.photos().isEmpty()) continue;
            PhotoViewer[] pv = new PhotoViewer[1];
            SwingUtilities.invokeAndWait(() -> pv[0] = new PhotoViewer(win, win.images(), person.photos(), 1, person.summary().displayName()));
            settle(1500);
            shootWindow(pv[0], new File(out, "photo-" + t + ".png"));
            SwingUtilities.invokeAndWait(() -> pv[0].dispose());
        }
        if (warningsFile != null) {
            SwingUtilities.invokeAndWait(() -> win.setTheme(Theme.LIGHT));
            SwingUtilities.invokeAndWait(() -> win.openFile(warningsFile));
            settle(2000);
            nav(win, new Place.Warnings());
            shoot(win, new File(out, "warnings-light.png"));
        }
        System.exit(0);
    }

    private static void nav(MainWindow w, Place p) throws Exception {
        SwingUtilities.invokeAndWait(() -> w.go(p));
        settle(1800);
    }

    private static void settle(long ms) throws Exception {
        Thread.sleep(ms);
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void shoot(Window w, File f) throws Exception {
        SwingUtilities.invokeAndWait(() -> shootWindowNow(w, f));
    }

    private static void shootWindow(Window w, File f) throws Exception {
        SwingUtilities.invokeAndWait(() -> shootWindowNow(w, f));
    }

    /// Paints the window's content (not the native title bar) into a PNG.
    static void shootWindowNow(Window w, File f) {
        if (!w.isDisplayable()) {
            var size = w.getSize();
            w.pack();
            if (size.width > 0 && size.height > 0) w.setSize(size);
        }
        if (w instanceof MainWindow) w.setSize(1280, 820);
        w.validate();
        JComponent root = (JComponent) ((javax.swing.RootPaneContainer) w).getRootPane();
        root.setSize(w.getWidth() - w.getInsets().left - w.getInsets().right, w.getHeight() - w.getInsets().top - w.getInsets().bottom);
        root.validate();
        layoutAll(root);
        var img = new BufferedImage(root.getWidth() * 2, root.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.scale(2, 2);
        root.paint(g);
        g.dispose();
        try {
            ImageIO.write(img, "png", f);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void layoutAll(java.awt.Container c) {
        c.doLayout();
        for (var k : c.getComponents()) {
            if (k instanceof java.awt.Container ct) layoutAll(ct);
        }
    }
}
