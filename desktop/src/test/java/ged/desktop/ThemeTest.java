package ged.desktop;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ThemeTest {

    @Test
    void macDarkOnlyWhenTheKeySaysSo() {
        assertEquals(Theme.DARK, Theme.parseMac(0, "Dark\n"));
        assertEquals(Theme.LIGHT, Theme.parseMac(1, "The domain/default pair does not exist"));
    }

    @Test
    void windowsAndGnome() {
        assertEquals(Optional.of(Theme.DARK), Theme.parseWindows("    AppsUseLightTheme    REG_DWORD    0x0"));
        assertEquals(Optional.of(Theme.LIGHT), Theme.parseWindows("    AppsUseLightTheme    REG_DWORD    0x1"));
        assertEquals(Optional.empty(), Theme.parseWindows("nothing"));
        assertEquals(Optional.of(Theme.DARK), Theme.parseGnome("'prefer-dark'\n"));
        assertEquals(Optional.empty(), Theme.parseGnome("'default'"));
    }

    @Test
    void toggles() {
        assertEquals(Theme.DARK, Theme.LIGHT.other());
        assertEquals(Theme.LIGHT, Theme.DARK.other());
    }
}
