package ged.desktop;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// The small decisions pages make: ages on the timeline, list filtering,
/// the people list's second line, and the charts' trees and lines.
class ViewLogicTest {

    static Model.PersonSummary person(String key, String name) {
        return new Model.PersonSummary(key, name, "", "", "U", "", "", false, "", null, "", null, false);
    }

    @Test
    void agesOnTheTimeline() {
        var death = new Model.Event("DEAT", "Died", "", "1920", 1920, "", "");
        var birth = new Model.Event("BIRT", "Born", "", "1850", 1850, "", "");
        var baptism = new Model.Event("BAPM", "Baptised", "", "1850", 1850, "", "");
        assertEquals("aged 70", PersonView.TimelineRow.age(death, 1850));
        assertNull(PersonView.TimelineRow.age(birth, 1850));
        assertNull(PersonView.TimelineRow.age(baptism, 1850));
        assertNull(PersonView.TimelineRow.age(death, null));
    }

    @Test
    void filterIsAccentAndCaseBlindAndWordByWord() {
        var items = RecordViews.recordItems(List.of(
                new Model.RecordSummary("S1", "sources", "Registre paroissial de Génova", 2, null, null),
                new Model.RecordSummary("S2", "sources", "Census 1881", 0, null, null)));
        assertEquals(List.of("S1"), RecordViews.filter(items, "genova regis").stream().map(RecordViews.Item::key).toList());
        assertEquals(List.of("S2"), RecordViews.filter(items, "s2").stream().map(RecordViews.Item::key).toList());
        assertEquals(2, RecordViews.filter(items, " ").size());
        assertEquals("Used 2 times", items.get(0).detail());
        assertEquals("Not used", items.get(1).detail());
    }

    @Test
    void peopleListShowsWhatASearchMatched() {
        var p = new Model.PersonSummary("I1", "Maria Rossi", "Maria", "Rossi", "F", "1850", "", false, "b. 1850", null, "Mimi", null, false);
        assertEquals("b. 1850  ·  matches “Mimi”", PersonCellRenderer.detail(p));
    }

    @Test
    void ancestorsChartHasEmptySlotsForMissingParents() {
        var ped = new Model.Pedigree(person("I1", "A"), new Model.Pedigree(person("I2", "B"), null, null), null);
        var root = TreeChart.ancestors(ped, 2);
        assertEquals(2, root.next().size());
        assertEquals(TreeChart.Node.Kind.PERSON, root.next().get(0).kind());
        assertEquals(TreeChart.Node.Kind.EMPTY, root.next().get(1).kind());
        assertEquals("mother", root.next().get(1).relation());
        assertEquals("I1", root.next().get(1).owner());
        // the father's own parents are empty slots one generation further
        assertEquals(2, root.next().get(0).next().size());
        assertTrue(TreeChart.ancestors(ped, 0).next().isEmpty());
    }

    @Test
    void connectorsRunFromBoxToBox() {
        var a = new Rectangle(0, 0, 100, 40);
        var b = new Rectangle(150, 100, 100, 40);
        var h = TreeChart.connector(a, b).getBounds2D();
        assertEquals(100, h.getMinX(), 0.01);
        assertEquals(150, h.getMaxX(), 0.01);
        var v = TreeChart.connectorDown(a, new Rectangle(200, 100, 100, 40)).getBounds2D();
        assertEquals(40, v.getMinY(), 0.01);
        assertEquals(100, v.getMaxY(), 0.01);
    }

    @Test
    void ellipsize() {
        var g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        var fm = g.getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        assertEquals("Maria", TreeChart.ellipsize("Maria", fm, 500));
        var cut = TreeChart.ellipsize("Maria Luisa Giovanna Rossi di Genova", fm, 80);
        assertTrue(cut.endsWith("…") && fm.stringWidth(cut) <= 80, cut);
    }

    @Test
    void approximateYearsReadAsCirca() {
        assertEquals("1850–c. 1920", Model.approx("1850–~1920"));
        assertEquals("c. 1825–1880", Model.approx("~1825–1880"));
        assertEquals("b. 1852", Model.approx("b. 1852"));
        assertEquals("", Model.approx(""));
        var p = Model.PersonSummary.from(ged.json.Json.parseObject(
                "{\"key\":\"I1\",\"born\":\"1850\",\"died\":\"~1920\",\"lifespan\":\"1850–~1920\"}"));
        assertEquals("1850–c. 1920", p.lifespan());
        assertEquals("c. 1920", p.died());
    }

    @Test
    void familyBoxesShowTheMarriage() {
        var married = new Model.Event("MARR", "Married", "", "ABT 1848", 1848, "Genova", "");
        assertEquals("m. c. 1848  ·  F1", TreeChart.familyLabel(new Model.DescendantFamily("F1", person("I4", "T"), married, false, List.of())));
        assertEquals("F6  ·  partner unknown", TreeChart.familyLabel(new Model.DescendantFamily("F6", null, null, false, List.of())));
        assertEquals("m. 1875  ·  divorced  ·  F2", TreeChart.familyLabel(new Model.DescendantFamily("F2", person("I2", "M"),
                new Model.Event("MARR", "Married", "", "1875", 1875, "", ""), true, List.of())));
    }

    @Test
    void mediaThumbnailsOnlyForPictures() {
        assertEquals("/f", new Model.RecordSummary("M1", "media", "", 1, "/f", "image/jpeg").thumbnail());
        assertNull(new Model.RecordSummary("M2", "media", "", 1, "/f", "application/pdf").thumbnail());
        assertNull(new Model.RecordSummary("M3", "media", "", 1, null, null).thumbnail());
        assertEquals("/f", RecordViews.recordItems(List.of(new Model.RecordSummary("M1", "media", "", 1, "/f", "image/png")))
                .getFirst().thumbnail());
    }

    @Test
    void reconnectionWaitsLongerEachTimeUpToHalfAMinute() {
        assertEquals(1, ChangeWatcher.backoff(0));
        assertEquals(2, ChangeWatcher.backoff(1));
        assertEquals(30, ChangeWatcher.backoff(5));
        assertEquals(30, ChangeWatcher.backoff(50));
    }
}
