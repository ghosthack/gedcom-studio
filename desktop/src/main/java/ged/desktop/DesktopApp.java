package ged.desktop;

import ged.client.DaemonClient;
import ged.daemon.Home;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.time.Duration;

/// Entry point of the desktop app, a client of the gedcom-shell daemon.
///
/// Order matters on macOS: the application name and the window appearance
/// are read once while AWT starts, so they are set before anything touches
/// AWT; then the FlatLaf macOS look (the theme the user chose, else the
/// system's). Then
/// the app finds the running daemon, or starts one from this same jar, and
/// opens the file named on the command line (or the last one).
public final class DesktopApp {

    public static void main(String[] args) {
        var fileArg = args.length > 0 && !args[0].isBlank() ? args[0] : null;
        System.setProperty("apple.awt.application.name", MainWindow.APP_NAME);
        // the menu goes in the screen's menu bar on macOS, in the window elsewhere
        if (Theme.isMac()) System.setProperty("apple.laf.useScreenMenuBar", "true");
        var theme = Prefs.theme().orElseGet(Theme::fromSystem);
        Theme.applyBeforeAwt(theme);
        theme.install();
        var home = Home.fromEnvironment();
        SwingUtilities.invokeLater(() -> connect(home, theme, fileArg));
    }

    /// Finds or starts the daemon off the EDT; shows a small progress window if
    /// that takes a moment, and a friendly error (with Retry) if it fails.
    private static void connect(Home home, Theme theme, String fileArg) {
        var splash = splash();
        var showSplash = new Timer(350, e -> splash.setVisible(true));
        showSplash.setRepeats(false);
        showSplash.start();
        Bg.run(() -> DaemonClient.ensure(home, Duration.ofSeconds(25)), client -> {
            showSplash.stop();
            splash.dispose();
            var window = new MainWindow(theme, new Connection(home, client));
            window.setVisible(true);
            window.start(fileArg);
        }, e -> {
            showSplash.stop();
            splash.dispose();
            var text = new JTextArea("The gedcom-shell daemon keeps your GEDCOM files open and your edits safe; "
                    + "the app needs it running.\n\n" + Api.message(e)
                    + "\n\nDaemon folder: " + home.dir() + "\nLog: " + home.logFile());
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setOpaque(false);
            text.setFont(Ui.base());
            var scroll = new JScrollPane(text);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setPreferredSize(new Dimension(480, 200));
            int r = JOptionPane.showOptionDialog(null, scroll, "Couldn't start the daemon", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.ERROR_MESSAGE, icon(64), new Object[] {"Try Again", "Quit"}, "Try Again");
            if (r == 0) connect(home, theme, fileArg);
            else System.exit(1);
        });
    }

    private static JDialog splash() {
        var d = new JDialog((java.awt.Frame) null, MainWindow.APP_NAME, false);
        d.setUndecorated(true);
        var p = new JPanel(new BorderLayout(16, 0));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.hairline()),
                BorderFactory.createEmptyBorder(20, 22, 20, 26)));
        p.add(new JLabel(icon(56)), BorderLayout.WEST);
        var text = Ui.stack(6);
        var title = Ui.label(MainWindow.APP_NAME);
        title.setFont(Ui.font(4, Font.BOLD));
        text.add(title);
        text.add(Ui.mutedLabel("Connecting to the daemon…"));
        var bar = new JProgressBar();
        bar.setIndeterminate(true);
        text.add(bar);
        p.add(text, BorderLayout.CENTER);
        d.setContentPane(p);
        d.setSize(380, 112);
        d.setLocationRelativeTo(null);
        d.setIconImages(AppIcon.images());
        return d;
    }

    private static ImageIcon icon(int size) {
        return new ImageIcon(AppIcon.render(size * 2).getScaledInstance(size, size, Image.SCALE_SMOOTH));
    }

    private DesktopApp() {
    }
}
