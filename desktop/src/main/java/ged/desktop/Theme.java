package ged.desktop;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import javax.swing.UIManager;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/// Light or dark: the FlatLaf macOS look in either, following the system at
/// launch, switched live by the toolbar's sun/moon.
///
/// The native window chrome (title bar, traffic lights) follows too: before
/// AWT starts through `apple.awt.application.appearance`, which the JDK reads
/// exactly once, and afterwards through `[NSApp setAppearance:]`, called with
/// the FFM API. Both are best-effort; without them only the title bar stays
/// as it was.
enum Theme {
    LIGHT, DARK;

    Theme other() {
        return this == LIGHT ? DARK : LIGHT;
    }

    boolean dark() {
        return this == DARK;
    }

    private static final String AWT_APPEARANCE = "apple.awt.application.appearance";

    /// The system's current choice, or light when it can't be told.
    static Theme fromSystem() {
        return detect(System.getProperty("os.name", "")).orElse(LIGHT);
    }

    /// Must run before any AWT or Swing call (see the type's comment).
    static void applyBeforeAwt(Theme theme) {
        if (isMac()) System.setProperty(AWT_APPEARANCE, theme.dark() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
    }

    /// Installs the look and feel and the app's few adjustments to it.
    void install() {
        if (dark()) FlatMacDarkLaf.setup();
        else FlatMacLightLaf.setup();
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollPane.smoothScrolling", true);
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("List.selectionArc", 10);
        UIManager.put("SplitPaneDivider.style", "plain");
    }

    /// Switches a running app to this theme: the look and feel of every window,
    /// and the native chrome.
    void applyLive() {
        install();
        FlatLaf.updateUI();
        if (isMac()) MacChrome.setAppearance(dark() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    // ---- detecting the system's choice ------------------------------------------------

    static Optional<Theme> detect(String osName) {
        var os = osName.toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                var r = run(List.of("defaults", "read", "-g", "AppleInterfaceStyle"));
                return r == null ? Optional.empty() : Optional.of(parseMac(r.exit(), r.output()));
            }
            if (os.contains("win")) {
                var r = run(List.of("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme"));
                return r == null || r.exit() != 0 ? Optional.empty() : parseWindows(r.output());
            }
            if (os.contains("linux")) {
                var r = run(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"));
                return r == null || r.exit() != 0 ? Optional.empty() : parseGnome(r.output());
            }
        } catch (IOException | RuntimeException e) {
            // no detector: the default applies
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    /// `defaults read -g AppleInterfaceStyle`: the key exists only in dark mode.
    static Theme parseMac(int exit, String output) {
        return exit == 0 && output.strip().equalsIgnoreCase("dark") ? DARK : LIGHT;
    }

    static Optional<Theme> parseWindows(String output) {
        for (var line : output.split("\\R")) {
            var f = line.strip().split("\\s+");
            if (f.length >= 3 && f[0].equalsIgnoreCase("AppsUseLightTheme")) {
                if (f[f.length - 1].equalsIgnoreCase("0x0")) return Optional.of(DARK);
                if (f[f.length - 1].equalsIgnoreCase("0x1")) return Optional.of(LIGHT);
            }
        }
        return Optional.empty();
    }

    static Optional<Theme> parseGnome(String output) {
        var v = output.strip().replace("'", "");
        if (v.equals("prefer-dark")) return Optional.of(DARK);
        if (v.equals("prefer-light")) return Optional.of(LIGHT);
        return Optional.empty();
    }

    private record Result(int exit, String output) {
    }

    private static Result run(List<String> command) throws IOException, InterruptedException {
        var p = new ProcessBuilder(command).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return null;
        }
        try (InputStream in = p.getInputStream()) {
            return new Result(p.exitValue(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ---- the native chrome, live ------------------------------------------------------

    /// `[NSApp performSelectorOnMainThread:@selector(setAppearance:) withObject:… waitUntilDone:NO]`
    /// through the FFM API. One failure turns it off for good.
    private static final class MacChrome {
        private static volatile boolean available;
        private static MethodHandle getClass, selRegister, poolPush, poolPop, send0, send1, sendPerform;

        static {
            try {
                var linker = Linker.nativeLinker();
                var objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global()).or(linker.defaultLookup());
                getClass = linker.downcallHandle(objc.find("objc_getClass").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                selRegister = linker.downcallHandle(objc.find("sel_registerName").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                poolPush = linker.downcallHandle(objc.find("objc_autoreleasePoolPush").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                poolPop = linker.downcallHandle(objc.find("objc_autoreleasePoolPop").orElseThrow(),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                var msgSend = objc.find("objc_msgSend").orElseThrow();
                send0 = linker.downcallHandle(msgSend, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                send1 = linker.downcallHandle(msgSend, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                sendPerform = linker.downcallHandle(msgSend, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }

        static void setAppearance(String name) {
            if (!available) return;
            try {
                var pool = (MemorySegment) poolPush.invokeExact();
                try (var arena = Arena.ofConfined()) {
                    var nsName = (MemorySegment) send1.invokeExact(
                            (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSString")),
                            (MemorySegment) selRegister.invokeExact(arena.allocateFrom("stringWithUTF8String:")),
                            arena.allocateFrom(name));
                    var appearance = (MemorySegment) send1.invokeExact(
                            (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSAppearance")),
                            (MemorySegment) selRegister.invokeExact(arena.allocateFrom("appearanceNamed:")), nsName);
                    var app = (MemorySegment) send0.invokeExact(
                            (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSApplication")),
                            (MemorySegment) selRegister.invokeExact(arena.allocateFrom("sharedApplication")));
                    if (appearance.address() == 0 || app.address() == 0) {
                        available = false;
                        return;
                    }
                    sendPerform.invokeExact(app,
                            (MemorySegment) selRegister.invokeExact(arena.allocateFrom("performSelectorOnMainThread:withObject:waitUntilDone:")),
                            (MemorySegment) selRegister.invokeExact(arena.allocateFrom("setAppearance:")),
                            appearance, false);
                } finally {
                    poolPop.invokeExact(pool);
                }
            } catch (Throwable t) {
                available = false;
            }
        }
    }
}
