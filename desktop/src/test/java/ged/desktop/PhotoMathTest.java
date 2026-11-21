package ged.desktop;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.*;

class PhotoMathTest {

    @Test
    void cropIsTheNamedRectangle() {
        assertEquals(new Rectangle(620, 120, 360, 420), PhotoMath.cropRect(1200, 800, new Model.Crop(620, 120, 360, 420)));
    }

    @Test
    void noCropIsTheWholePicture() {
        assertEquals(new Rectangle(0, 0, 600, 800), PhotoMath.cropRect(600, 800, null));
    }

    @Test
    void zeroWidthOrHeightRunsToTheEdge() {
        assertEquals(new Rectangle(100, 50, 500, 750), PhotoMath.cropRect(600, 800, new Model.Crop(100, 50, 0, 0)));
    }

    @Test
    void cropIsKeptInsideThePicture() {
        assertEquals(new Rectangle(500, 700, 100, 100), PhotoMath.cropRect(600, 800, new Model.Crop(500, 700, 400, 400)));
        assertEquals(new Rectangle(0, 0, 600, 800), PhotoMath.cropRect(600, 800, new Model.Crop(900, 10, 10, 10)));
    }

    @Test
    void coverCentresAcrossAndFavoursTheTopOfPortraits() {
        // landscape into a square: centred across
        assertEquals(new Rectangle(200, 0, 800, 800), PhotoMath.cover(new Rectangle(0, 0, 1200, 800), 32, 32));
        // portrait into a square: a quarter of the spare height above
        assertEquals(new Rectangle(0, 50, 600, 600), PhotoMath.cover(new Rectangle(0, 0, 600, 800), 32, 32));
        // offsets carry through
        assertEquals(new Rectangle(620, 135, 360, 360), PhotoMath.cover(new Rectangle(620, 120, 360, 420), 10, 10));
    }

    @Test
    void fitShrinksButNeverEnlarges() {
        assertEquals(new Dimension(300, 400), PhotoMath.fit(600, 800, 1000, 400));
        assertEquals(new Dimension(60, 80), PhotoMath.fit(60, 80, 1000, 1000));
    }

    @Test
    void initials() {
        assertEquals("MR", PhotoMath.initials("Maria", "Rossi", "Maria Rossi"));
        assertEquals("ÉP", PhotoMath.initials("élodie", "Pérez", ""));
        assertEquals("M", PhotoMath.initials("Maria", "", "Maria"));
        assertEquals("JD", PhotoMath.initials("", "", "John Doe"));
        assertEquals("?", PhotoMath.initials("", "", ""));
    }
}
