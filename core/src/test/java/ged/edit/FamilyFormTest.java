package ged.edit;

import ged.Fixtures;
import ged.model.Genealogy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The family form's `hidden` field: `1 _HIDE Y` on the FAM record.
class FamilyFormTest {
    @TempDir Path dir;

    private static final String HIDE_DECL = "2 TAG _HIDE https://github.com/ghosthack/gedcom-studio#hidden-families";

    private static List<FamilyForm.Change> hide(Genealogy g, String id, String value) {
        var f = g.family(id).orElseThrow();
        var changes = FamilyForm.diff(f, Map.of(FamilyForm.Field.HIDDEN, value), g.isGedcom7());
        if (!changes.isEmpty()) FamilyForm.apply(g, f, changes);
        return changes;
    }

    private static String record(Genealogy g, String id) {
        return g.family(id).orElseThrow().node().toString();
    }

    private static long count(String text, String line) {
        return text.lines().filter(line::equals).count();
    }

    @Test
    void valuesReadTheHideLine() {
        var g = Genealogy.of("""
                0 HEAD
                1 GEDC
                2 VERS 5.5.1
                0 @F1@ FAM
                0 @F2@ FAM
                1 _HIDE Y
                0 @F3@ FAM
                1 _HIDE y
                0 @F4@ FAM
                1 _HIDE N
                0 TRLR
                """);
        assertEquals("no", FamilyForm.values(g.family("F1").orElseThrow()).get(FamilyForm.Field.HIDDEN));
        assertEquals("yes", FamilyForm.values(g.family("F2").orElseThrow()).get(FamilyForm.Field.HIDDEN));
        assertEquals("yes", FamilyForm.values(g.family("F3").orElseThrow()).get(FamilyForm.Field.HIDDEN), "any case");
        assertEquals("no", FamilyForm.values(g.family("F4").orElseThrow()).get(FamilyForm.Field.HIDDEN));
        assertEquals(List.of("marriage.date", "marriage.place", "divorce.date", "divorce.place", "divorced", "hidden"),
                FamilyForm.values(g.family("F1").orElseThrow()).keySet().stream().map(f -> f.key).toList());
    }

    @Test
    void parseAcceptsAliases() {
        for (var name : List.of("hidden", "HIDDEN", "hide", "_hide")) {
            assertEquals(FamilyForm.Field.HIDDEN, FamilyForm.Field.parse(name).orElseThrow(), name);
        }
    }

    @Test
    void diffValidatesYesOrNo() {
        var g = Fixtures.cousins();
        var f = g.family("F2").orElseThrow();
        var e = assertThrows(IllegalArgumentException.class,
                () -> FamilyForm.diff(f, Map.of(FamilyForm.Field.HIDDEN, "maybe"), false));
        assertEquals("hidden must be yes or no, not 'maybe'", e.getMessage());
        assertEquals(List.of(new FamilyForm.Change(FamilyForm.Field.HIDDEN, "no", "yes")),
                FamilyForm.diff(f, Map.of(FamilyForm.Field.HIDDEN, "Y"), false));
        assertTrue(FamilyForm.diff(f, Map.of(FamilyForm.Field.HIDDEN, "no"), false).isEmpty());
    }

    @Test
    void applyWritesTheLineLastAndRemovesIt() {
        var g = Fixtures.cousins();
        hide(g, "F2", "yes");
        var rec = record(g, "F2");
        assertTrue(rec.endsWith("1 _HIDE Y\n"), rec);
        assertTrue(g.family("F2").orElseThrow().hidden());
        assertTrue(hide(g, "F2", "yes").isEmpty(), "already hidden");
        assertEquals(1, count(record(g, "F2"), "1 _HIDE Y"));
        hide(g, "F2", "no");
        assertFalse(record(g, "F2").contains("_HIDE"));
        assertFalse(g.render().contains("SCHMA"), "a 5.5.1 header is left alone");
    }

    @Test
    void theLineGoesBeforeChanAndEveryOneIsRemoved() {
        var g = Genealogy.of("""
                0 HEAD
                1 GEDC
                2 VERS 5.5.1
                0 @F1@ FAM
                1 NOTE x
                1 CHAN
                2 DATE 1 JAN 2000
                0 @F2@ FAM
                1 _HIDE N
                1 NOTE y
                1 _HIDE Y
                0 @F3@ FAM
                1 _HIDE n
                1 NOTE z
                0 TRLR
                """);
        hide(g, "F1", "yes");
        assertTrue(record(g, "F1").startsWith("0 @F1@ FAM\n1 NOTE x\n1 _HIDE Y\n1 CHAN\n2 DATE "), record(g, "F1"));
        hide(g, "F2", "no");
        assertEquals("0 @F2@ FAM\n1 NOTE y\n", record(g, "F2"));
        hide(g, "F3", "yes");
        assertEquals("0 @F3@ FAM\n1 _HIDE Y\n1 NOTE z\n", record(g, "F3"), "the line there is set, not another added");
    }

    @Test
    void aLowercaseYIsKeptUntilTheValueChanges() {
        var g = Genealogy.of("""
                0 HEAD
                1 GEDC
                2 VERS 5.5.1
                0 @F1@ FAM
                1 _HIDE y
                0 TRLR
                """);
        assertTrue(hide(g, "F1", "yes").isEmpty());
        assertFalse(g.isDirty());
        assertTrue(g.render().contains("1 _HIDE y\n"));
    }

    @Test
    void renderShowsTheField() {
        var g = Fixtures.cousins();
        var form = FamilyForm.render(g, g.family("F2").orElseThrow(), null);
        assertTrue(form.contains("""
                #   divorced  yes or no; a divorce date or place implies yes
                #   hidden    yes or no; yes hides the family on person pages (saved as _HIDE Y)
                """), form);
        assertTrue(form.endsWith("divorced:        no\nhidden:          no\n"), form);
        hide(g, "F2", "yes");
        assertTrue(FamilyForm.render(g, g.family("F2").orElseThrow(), null).endsWith("hidden:          yes\n"));
    }

    @Test
    void theEditedFormHidesTheFamily() {
        var g = Fixtures.cousins();
        var f = g.family("F2").orElseThrow();
        var form = FamilyForm.render(g, f, null).replace("hidden:          no", "hidden: yes");
        var changes = FamilyForm.diffForm(f, form, false);
        assertEquals(List.of(new FamilyForm.Change(FamilyForm.Field.HIDDEN, "no", "yes")), changes);
        FamilyForm.apply(g, f, changes);
        assertTrue(g.family("F2").orElseThrow().hidden());
    }

    @Test
    void aSaveRoundTrips() throws Exception {
        var file = dir.resolve("t.ged");
        Files.writeString(file, Fixtures.COUSINS);
        var g = Genealogy.load(file);
        hide(g, "F2", "yes");
        g.save(file);
        var before = Fixtures.COUSINS.lines().toList();
        var after = Files.readString(file).lines().toList();
        var added = new ArrayList<>(after);
        before.forEach(added::remove);
        assertEquals(List.of("1 _HIDE Y"), added);
        assertEquals(before.size() + 1, after.size());

        var again = Genealogy.load(file);
        assertTrue(again.family("F2").orElseThrow().hidden());
        assertFalse(again.family("F1").orElseThrow().hidden());
        hide(again, "F2", "no");
        again.save(file);
        assertEquals(Fixtures.COUSINS, Files.readString(file));
    }

    @Test
    void gedcom7DeclaresTheTagOnce() throws Exception {
        var file = dir.resolve("g7.ged");
        Files.copy(Path.of(getClass().getResource("/gedcom7.ged").toURI()), file);
        var g = Genealogy.load(file);
        hide(g, "F1", "yes");
        hide(g, "F2", "yes");
        hide(g, "F1", "no");
        hide(g, "F1", "yes");
        g.save(file);
        var text = Files.readString(file);
        assertTrue(text.startsWith("0 HEAD\n1 GEDC\n2 VERS 7.0\n1 SCHMA\n" + HIDE_DECL + "\n1 SOUR"), text);
        assertEquals(1, count(text, HIDE_DECL));
        assertEquals(2, count(text, "1 _HIDE Y"));
        var again = Genealogy.load(file);
        hide(again, "F2", "no");
        hide(again, "F2", "yes");
        again.save(file);
        assertEquals(1, count(Files.readString(file), HIDE_DECL), "not declared again");
    }

    @Test
    void gedcom7AddsToAnExistingSchema() {
        var g = Genealogy.of("""
                0 HEAD
                1 GEDC
                2 VERS 7.0
                1 SCHMA
                2 TAG _FOO https://example.com/foo
                1 SOUR x
                0 @F1@ FAM
                0 @F2@ FAM
                0 TRLR
                """);
        hide(g, "F1", "yes");
        assertTrue(g.render().startsWith("""
                0 HEAD
                1 GEDC
                2 VERS 7.0
                1 SCHMA
                2 TAG _FOO https://example.com/foo
                %s
                1 SOUR x
                """.formatted(HIDE_DECL)), g.render());

        var declared = Genealogy.of("""
                0 HEAD
                1 GEDC
                2 VERS 7.0
                1 SCHMA
                2 TAG _HIDE https://example.com/elsewhere
                0 @F1@ FAM
                0 TRLR
                """);
        hide(declared, "F1", "yes");
        assertEquals(1, declared.render().lines().filter(l -> l.startsWith("2 TAG _HIDE")).count(), "never declared twice");
        assertTrue(declared.render().contains("1 _HIDE Y\n"));
    }
}
