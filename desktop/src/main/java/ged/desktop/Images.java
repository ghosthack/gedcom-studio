package ged.desktop;

import javax.imageio.ImageIO;
import javax.swing.CellRendererPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/// Pictures from the daemon, loaded in the background and cached: originals,
/// and the cropped, scaled copies the UI paints (made at twice the size, so
/// they stay sharp on Retina screens).
///
/// Asking for a picture that isn't loaded yet returns null and starts loading
/// it; the component that asked is repainted when it arrives.
///
/// Crops: a person summary carries its photo's crop as `photoCrop`. Daemons
/// from before that field send only the address; for those, crops learnt from
/// loaded people are remembered by address, and for a summary photo whose crop
/// is not known yet the person is fetched once to learn it.
final class Images {

    static final int SCALE = 2;
    private static final Pattern PERSON_PHOTO = Pattern.compile("/api/v1/files/([^/]+)/people/([^/]+)/photos/\\d+");

    private final Api api;
    private final Semaphore slots = new Semaphore(4);
    private final Map<String, BufferedImage> originals = lru(40);
    private final Map<String, BufferedImage> scaled = lru(400);
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final Map<String, Optional<Model.Crop>> crops = new ConcurrentHashMap<>();
    private final Set<String> cropLookups = ConcurrentHashMap.newKeySet();
    private final Map<String, List<WeakReference<Component>>> waiting = new ConcurrentHashMap<>();

    Images(Api api) {
        this.api = api;
    }

    private static <K, V> Map<K, V> lru(int max) {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<K, V>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> e) {
                return size() > max;
            }
        });
    }

    /// Remembers the crops of a person's photos.
    void learn(Model.Person p) {
        for (var ph : p.photos()) {
            if (ph.url() != null) crops.put(ph.url(), Optional.ofNullable(ph.crop()));
        }
    }

    /// Forgets everything (the file changed on disk, or the daemon restarted:
    /// addresses may now name other pictures).
    void clear() {
        originals.clear();
        scaled.clear();
        failed.clear();
        crops.clear();
        cropLookups.clear();
    }

    boolean failed(String url) {
        return failed.contains(url);
    }

    /// The whole picture, or null while it loads (or if it can't be read).
    BufferedImage original(String url, Component repaint) {
        if (url == null) return null;
        var img = originals.get(url);
        if (img != null) return img;
        if (failed.contains(url)) return null;
        wait(url, repaint);
        if (loading.add(url)) {
            Bg.execute(() -> load(url));
        }
        return null;
    }

    /// The picture cropped as GEDCOM says (when `applyCrop`) and scaled to
    /// cover a `width`×`height` frame, at [#SCALE] times that size.
    /// Null while anything it needs is still loading.
    BufferedImage cover(String url, Model.Crop knownCrop, boolean useKnownCrop, int width, int height, Component repaint) {
        if (url == null) return null;
        Optional<Model.Crop> crop;
        if (useKnownCrop) {
            crop = Optional.ofNullable(knownCrop);
        } else {
            crop = crops.get(url);
            if (crop == null) {
                lookUpCrop(url, repaint);
                return null;
            }
        }
        var c = crop.orElse(null);
        var key = url + "|" + width + "x" + height + "|" + c;
        var img = scaled.get(key);
        if (img != null) return img;
        var original = original(url, repaint);
        if (original == null) return null;
        var region = PhotoMath.cover(PhotoMath.cropRect(original.getWidth(), original.getHeight(), c), width, height);
        img = scale(original, region, width * SCALE, height * SCALE);
        scaled.put(key, img);
        return img;
    }

    /// The crop for a summary's photo address is not known: fetch the person.
    private void lookUpCrop(String url, Component repaint) {
        wait(url, repaint);
        if (!cropLookups.add(url)) return;
        var m = PERSON_PHOTO.matcher(url);
        if (!m.find()) {
            crops.put(url, Optional.empty());
            notifyWaiting(url);
            return;
        }
        var fileId = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
        var key = java.net.URLDecoder.decode(m.group(2), java.nio.charset.StandardCharsets.UTF_8);
        Bg.execute(() -> {
            try {
                slots.acquire();
                try {
                    learn(api.person(fileId, key));
                } finally {
                    slots.release();
                }
            } catch (Exception e) {
                // no crop then
            }
            crops.putIfAbsent(url, Optional.empty());
            notifyWaiting(url);
        });
    }

    private void load(String url) {
        if (Boolean.getBoolean("gedcom.desktop.debug")) System.err.println("load " + url + " at " + System.nanoTime() / 1_000_000);
        try {
            slots.acquire();
            try {
                var bytes = api.bytes(url);
                var img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img == null) {
                    failed.add(url);
                } else {
                    originals.put(url, toRgb(img));
                    if (Boolean.getBoolean("gedcom.desktop.debug")) System.err.println("loaded " + url + " at " + System.nanoTime() / 1_000_000);
                }
            } finally {
                slots.release();
            }
        } catch (Exception e) {
            if (Boolean.getBoolean("gedcom.desktop.debug")) e.printStackTrace();
            failed.add(url);
        } finally {
            loading.remove(url);
            notifyWaiting(url);
        }
    }

    private void wait(String url, Component c) {
        if (c == null) return;
        var target = repaintTarget(c);
        waiting.computeIfAbsent(url, u -> java.util.Collections.synchronizedList(new ArrayList<>())).add(new WeakReference<>(target));
    }

    /// A list cell's renderer is not on screen itself: repaint its list.
    private static Component repaintTarget(Component c) {
        var p = c.getParent();
        if (p instanceof CellRendererPane && p.getParent() != null) return p.getParent();
        return c;
    }

    private void notifyWaiting(String url) {
        var list = waiting.remove(url);
        if (list == null) return;
        List<Component> targets = new ArrayList<>();
        synchronized (list) {
            for (var ref : list) {
                var c = ref.get();
                if (c != null && !targets.contains(c)) targets.add(c);
            }
        }
        SwingUtilities.invokeLater(() -> targets.forEach(Component::repaint));
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB || src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        var out = new BufferedImage(src.getWidth(), src.getHeight(),
                src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /// High-quality downscale of `region` of `src` to `w`×`h`: halve step by
    /// step, then one bicubic pass (a single pass from a large picture aliases).
    static BufferedImage scale(BufferedImage src, Rectangle region, int w, int h) {
        var r = region.intersection(new Rectangle(0, 0, src.getWidth(), src.getHeight()));
        if (r.isEmpty()) r = new Rectangle(0, 0, src.getWidth(), src.getHeight());
        BufferedImage cur = src.getSubimage(r.x, r.y, r.width, r.height);
        int cw = r.width, ch = r.height;
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2;
            ch /= 2;
            cur = draw(cur, cw, ch, type);
        }
        return draw(cur, Math.max(1, w), Math.max(1, h), type);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h, int type) {
        var out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
