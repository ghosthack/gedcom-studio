package ged.desktop;

import java.awt.Rectangle;
import java.util.Optional;
import java.util.prefs.Preferences;

/// What the app remembers between runs (java.util.prefs): the last file, the
/// people list's sort, the window's place, the sidebar's width, and the theme
/// if the user chose one (otherwise the app follows the system's).
final class Prefs {

    private static final Preferences NODE = Preferences.userRoot().node("com/github/ghosthack/gedcom-shell/desktop");

    /// Off for screenshots and tests, so they leave the user's settings alone.
    static volatile boolean enabled = !Boolean.getBoolean("gedcom.desktop.noPrefs");

    static Optional<String> lastFile() {
        return enabled ? Optional.ofNullable(NODE.get("lastFile", null)) : Optional.empty();
    }

    static void lastFile(String path) {
        if (!enabled) return;
        if (path == null) NODE.remove("lastFile");
        else NODE.put("lastFile", path);
    }

    static String sort() {
        return enabled ? NODE.get("sort", "name") : "name";
    }

    static void sort(String sort) {
        if (enabled) NODE.put("sort", sort);
    }

    static int sidebarWidth() {
        return enabled ? NODE.getInt("sidebarWidth", 290) : 290;
    }

    static void sidebarWidth(int w) {
        if (enabled) NODE.putInt("sidebarWidth", w);
    }

    static Optional<Rectangle> bounds() {
        if (!enabled) return Optional.empty();
        var s = NODE.get("bounds", null);
        if (s == null) return Optional.empty();
        try {
            var p = s.split(",");
            return Optional.of(new Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static void bounds(Rectangle r) {
        if (enabled) NODE.put("bounds", r.x + "," + r.y + "," + r.width + "," + r.height);
    }

    /// The theme the user chose, or empty to follow the system.
    static Optional<Theme> theme() {
        if (!enabled) return Optional.empty();
        var v = NODE.get("theme", null);
        try {
            return v == null ? Optional.empty() : Optional.of(Theme.valueOf(v));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /// Keeps the chosen theme; null goes back to following the system.
    static void theme(Theme t) {
        if (!enabled) return;
        if (t == null) NODE.remove("theme");
        else NODE.put("theme", t.name());
    }

    static String lastDirectory() {
        return enabled ? NODE.get("lastDirectory", System.getProperty("user.home")) : System.getProperty("user.home");
    }

    static void lastDirectory(String dir) {
        if (enabled) NODE.put("lastDirectory", dir);
    }

    private Prefs() {
    }
}
