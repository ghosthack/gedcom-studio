package ged.desktop;

import java.awt.Rectangle;

/// The geometry of showing a person's photo: the GEDCOM crop (the part of a
/// picture that shows this person) and fitting the result into a frame.
final class PhotoMath {

    /// The part of an `imageWidth`×`imageHeight` picture a crop names, kept
    /// inside the picture. A width or height of 0 runs to the picture's edge
    /// (GEDCOM 7 leaves them out); no crop, or one that misses the picture,
    /// is the whole picture.
    static Rectangle cropRect(int imageWidth, int imageHeight, Model.Crop crop) {
        var whole = new Rectangle(0, 0, imageWidth, imageHeight);
        if (crop == null || imageWidth <= 0 || imageHeight <= 0) return whole;
        int left = Math.max(0, crop.left());
        int top = Math.max(0, crop.top());
        if (left >= imageWidth || top >= imageHeight) return whole;
        int width = crop.width() <= 0 ? imageWidth - left : Math.min(crop.width(), imageWidth - left);
        int height = crop.height() <= 0 ? imageHeight - top : Math.min(crop.height(), imageHeight - top);
        if (width <= 0 || height <= 0) return whole;
        return new Rectangle(left, top, width, height);
    }

    /// The largest part of `source` with the aspect ratio of a `width`×`height`
    /// frame, so the frame is filled without distortion. Centred across;
    /// on a portrait picture it sits higher, where faces are.
    static Rectangle cover(Rectangle source, int width, int height) {
        if (width <= 0 || height <= 0 || source.width <= 0 || source.height <= 0) return new Rectangle(source);
        double want = width / (double) height;
        double have = source.width / (double) source.height;
        if (Math.abs(want - have) < 1e-9) return new Rectangle(source);
        if (have > want) {
            int w = Math.max(1, (int) Math.round(source.height * want));
            return new Rectangle(source.x + (source.width - w) / 2, source.y, w, source.height);
        }
        int h = Math.max(1, (int) Math.round(source.width / want));
        int spare = source.height - h;
        return new Rectangle(source.x, source.y + (int) Math.round(spare * 0.25), source.width, h);
    }

    /// The size of a `width`×`height` picture shrunk to fit in `maxWidth`×`maxHeight`
    /// (never enlarged).
    static java.awt.Dimension fit(int width, int height, int maxWidth, int maxHeight) {
        if (width <= 0 || height <= 0) return new java.awt.Dimension(0, 0);
        double scale = Math.min(1.0, Math.min(maxWidth / (double) width, maxHeight / (double) height));
        return new java.awt.Dimension(Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)));
    }

    /// Initials for a person without a photo: given name and surname, or the
    /// first two words of the name.
    static String initials(String given, String surname, String name) {
        var a = firstLetter(given);
        var b = firstLetter(surname);
        if (!a.isEmpty() || !b.isEmpty()) return (a + b).toUpperCase(java.util.Locale.ROOT);
        var words = name == null ? new String[0] : name.strip().split("\\s+");
        var sb = new StringBuilder();
        for (int i = 0; i < words.length && sb.length() < 2; i++) sb.append(firstLetter(words[i]));
        return sb.isEmpty() ? "?" : sb.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private static String firstLetter(String s) {
        if (s == null) return "";
        var t = s.strip();
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            if (Character.isLetter(cp)) return new String(Character.toChars(cp));
            i += Character.charCount(cp);
        }
        return "";
    }

    private PhotoMath() {
    }
}
