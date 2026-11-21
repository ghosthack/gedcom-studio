package ged.desktop;

import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import java.awt.image.BufferedImage;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class IconsTest {

    @Test
    void everyIconParsesAndPaints() throws Exception {
        int n = 0;
        for (var m : Icons.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != Icon.class
                    || m.getParameterCount() != 1 || m.getParameterTypes()[0] != int.class) continue;
            var icon = (Icon) m.invoke(null, 16);
            var img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            var g = img.createGraphics();
            icon.paintIcon(null, g, 0, 0);
            g.dispose();
            assertTrue(painted(img), m.getName() + " paints nothing");
            n++;
        }
        assertTrue(n > 25, "found " + n + " icons");
    }

    @Test
    void pathDataWithCurvesAndArcs() {
        var p = Icons.parse("M2 2C2 8 8 8 8 2S14 -4 14 2Q17 5 20 2T22 2A2 2 0 1 1 18 2z");
        var b = p.getBounds2D();
        assertEquals(2, b.getMinX(), 0.01);
        assertTrue(b.getMaxX() >= 21.9);
        assertThrows(IllegalArgumentException.class, () -> Icons.parse("1 2"));
    }

    @Test
    void appIconRendersAtEverySize() {
        for (var i : AppIcon.images()) {
            var img = (BufferedImage) i;
            assertTrue(painted(img));
            // the centre is opaque: the green square
            assertEquals(255, img.getRGB(img.getWidth() / 2, img.getHeight() / 2) >>> 24);
        }
    }

    private static boolean painted(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) >>> 24) != 0) return true;
        return false;
    }
}
