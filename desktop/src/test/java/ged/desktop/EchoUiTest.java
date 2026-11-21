package ged.desktop;

import ged.client.DaemonClient;
import ged.daemon.Home;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static ged.desktop.UiTest.button;
import static ged.desktop.UiTest.field;
import static ged.desktop.UiTest.onEdt;
import static ged.desktop.UiTest.walk;
import static org.junit.jupiter.api.Assertions.*;

/// Every value the edit forms change, echoed everywhere the window shows it.
///
/// The real [MainWindow] (never shown) against a real daemon: each edit is
/// made through the real form (fields filled, Save clicked) opened from the
/// window, then each page, the sidebar, the charts and the lists are read
/// component by component; the form, opened again, must show the new values.
/// Edits made directly through the API (as the web app or the shell would)
/// must reach the open page and an open form.
@Tag("ui")
class EchoUiTest {

    /// Three generations: Juan and María, their son Tomás (with Ana), and
    /// Tomás and Ana's daughter Sofía. A source and a note shared by
    /// Tomás's page and the family's, and a photo.
    static final String GED = """
            0 HEAD
            1 GEDC
            2 VERS 5.5.1
            1 CHAR UTF-8
            0 @I0@ INDI
            1 NAME Tomás Iker /Fábregas/
            2 NICK Adri
            1 NAME Iker /Fábregas/
            2 TYPE aka
            1 SEX M
            1 BIRT
            2 DATE 14 MAR 1979
            2 PLAC Segovia
            1 OCCU Técnico
            2 DATE 2005
            1 NOTE Likes trains.
            1 NOTE @N1@
            1 SOUR @S1@
            1 OBJE @M1@
            1 FAMC @F0@
            1 FAMS @F1@
            0 @I1@ INDI
            1 NAME Juan /Fábregas/
            1 SEX M
            1 BIRT
            2 DATE 1950
            1 FAMS @F0@
            0 @I2@ INDI
            1 NAME María /López/
            1 NAME María /López/ de Pérez
            2 TYPE married
            1 SEX F
            1 FAMS @F0@
            0 @I3@ INDI
            1 NAME Ana /Pérez/
            1 SEX F
            1 BIRT
            2 DATE 1981
            1 NOTE @N1@
            1 FAMS @F1@
            0 @I4@ INDI
            1 NAME Sofía /Fábregas/
            1 SEX F
            1 BIRT
            2 DATE 2012
            1 FAMC @F1@
            0 @F0@ FAM
            1 HUSB @I1@
            1 WIFE @I2@
            1 CHIL @I0@
            0 @F1@ FAM
            1 HUSB @I0@
            1 WIFE @I3@
            1 CHIL @I4@
            1 MARR
            2 DATE 2010
            2 PLAC Cuenca
            1 SOUR @S1@
            0 @N1@ NOTE Shared research note.
            0 @S1@ SOUR
            1 TITL Parish register
            0 @M1@ OBJE
            1 FILE wedding.png
            2 FORM png
            2 TITL Wedding day
            0 TRLR
            """;

    static Api api;
    static DaemonClient client;
    static Connection conn;
    String fileId;
    Path file;
    MainWindow win;
    final AtomicReference<JDialog> shown = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        // a daemon of its own, beside UiTest's
        var home = new Home(Path.of(System.getProperty("gedcom.shell.home") + "-echo"));
        home.create();
        Files.writeString(home.settingsFile(), "port=7437\n");
        client = DaemonClient.ensure(home, Duration.ofSeconds(30));
        conn = new Connection(home, client);
        api = new Api(conn);
        Prefs.enabled = false;
        SwingUtilities.invokeAndWait(Theme.LIGHT::install);
    }

    @AfterAll
    static void stop() {
        try {
            client.post("daemon/stop", Map.of("force", true));
        } catch (Exception ignored) {
            // already gone
        }
    }

    @BeforeEach
    void open(@TempDir Path dir) throws Exception {
        file = dir.resolve("fabregas.ged");
        Files.writeString(file, GED);
        var img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", dir.resolve("wedding.png").toFile());
        fileId = api.open(file.toString()).id();
        onEdt(() -> {
            win = new MainWindow(Theme.LIGHT, conn);
            win.presenter = shown::set;
            win.start(file.toString());
        });
        eventually(() -> assertEquals("Tomás Iker Fábregas", title()));
    }

    @AfterEach
    void close() throws Exception {
        onEdt(() -> {
            for (var w : java.awt.Window.getWindows()) if (w instanceof JDialog d) d.dispose();
            win.dispose();
        });
        try {
            api.close(fileId, true);
        } catch (Exception ignored) {
            // not open
        }
    }

    // ---- the name ------------------------------------------------------------------

    @Test
    void aNewNameShowsEverywhereThePersonIsShown() throws Exception {
        var form = editPerson("I0");
        onEdt(() -> {
            field(form, "Given names").setText("Tomás");
            field(form, "Surname").setText("Fábregas Gil");
            button(form, "Save").doClick();
        });
        closed(form);
        var name = "Tomás Fábregas Gil";
        var span = lifespan("I0");
        // their page: the heading and the toolbar
        eventually(() -> {
            assertEquals(name, title());
            assertEquals(name, win.crumb());
        });
        // the sidebar, and searching for the new surname
        eventually(() -> assertTrue(sidebarRows().contains(name + " | " + span + " | I0"), sidebarRows().toString()));
        onEdt(() -> win.sidebar().searchField().setText("gil"));
        eventually(() -> assertEquals(List.of(name + " | " + span + " | I0"), sidebarRows()));
        onEdt(() -> win.sidebar().searchField().setText(""));
        // their relatives' pages
        go(new Place.PersonPage("I1"));
        eventually(() -> assertTrue(chips().contains(name + " | " + span), chips().toString()));
        go(new Place.PersonPage("I3"));
        eventually(() -> {
            assertTrue(chips().contains(name + " | Husband  ·  " + span), chips().toString());
            assertTrue(texts().contains("FAMILY WITH TOMÁS"), texts().toString());
        });
        go(new Place.PersonPage("I4"));
        eventually(() -> assertTrue(chips().contains(name + " | Father  ·  " + span), chips().toString()));
        // the family: its title, its partner card, the list of families
        go(new Place.FamilyPage("F1"));
        eventually(() -> {
            assertEquals(name + " & Ana Pérez", title());
            assertTrue(chips().contains(name + " | Husband  ·  " + span), chips().toString());
        });
        go(new Place.RecordList("families"));
        eventually(() -> assertTrue(listRows().contains(name + " & Ana Pérez | Married 2010  ·  1 child | F1"), listRows().toString()));
        // the charts
        go(new Place.Ancestors("I4", 4));
        eventually(() -> assertTrue(chart().contains(List.of(name, span)), chart().toString()));
        go(new Place.Ancestors("I0", 4));
        eventually(() -> {
            assertEquals("Ancestors of " + name, title());
            assertEquals(List.of(name, span), chart().getLast(), "the root box");
        });
        go(new Place.Descendants("I0", 3));
        eventually(() -> assertEquals("Descendants of " + name, title()));
        go(new Place.Descendants("I1", 3));
        eventually(() -> assertTrue(chart().contains(List.of(name, span)), chart().toString()));
        go(new Place.Descendants("I3", 3));
        eventually(() -> assertTrue(chart().contains(List.of("m. 2010  ·  F1", name)), chart().toString()));
        // the records it uses list it
        go(new Place.RecordPage("S1"));
        eventually(() -> assertTrue(chips().contains(name + " | " + span), chips().toString()));
        go(new Place.RecordPage("N1"));
        eventually(() -> assertTrue(chips().contains(name + " | " + span), chips().toString()));
        // the family's form
        var family = editFamily("F1");
        onEdt(() -> {
            assertEquals("Edit " + name + " & Ana Pérez", family.getTitle());
            family.dispose();
        });
        // the form, opened again
        var again = editPerson("I0");
        onEdt(() -> {
            assertEquals("Edit " + name, again.getTitle());
            assertEquals("Tomás", field(again, "Given names").getText());
            assertEquals("Fábregas Gil", field(again, "Surname").getText());
            again.dispose();
        });
        assertTrue(saveFile().contains("0 @I0@ INDI\n1 NAME Tomás /Fábregas Gil/\n"), saveFile());
    }

    @Test
    void theSuffixShowsAfterTheNameInTheHeader() throws Exception {
        var form = editPerson("I0");
        onEdt(() -> {
            field(form, "Suffix").setText("Jr.");
            button(form, "Save").doClick();
        });
        closed(form);
        eventually(() -> assertEquals("Tomás Iker Fábregas Jr.", title()));
        var again = editPerson("I0");
        onEdt(() -> {
            assertEquals("Jr.", field(again, "Suffix").getText());
            again.dispose();
        });
        assertTrue(saveFile().contains("1 NAME Tomás Iker /Fábregas/ Jr.\n"), saveFile());
    }

    @Test
    void theSexShowsInTheHeaderAndTheFamilyRoles() throws Exception {
        var form = editPerson("I3");
        onEdt(() -> {
            ((javax.swing.JComboBox<?>) UiTest.find(form, javax.swing.JComboBox.class,
                    c -> "Sex".equals(c.getAccessibleContext().getAccessibleName()), "sex")).setSelectedIndex(2);
            button(form, "Save").doClick();
        });
        closed(form);
        var span = lifespan("I3");
        go(new Place.PersonPage("I3"));
        eventually(() -> assertTrue(texts().contains(span + "   ·   Sex unknown   ·   I3"), texts().toString()));
        go(new Place.PersonPage("I0"));
        eventually(() -> assertTrue(chips().contains("Ana Pérez | Wife  ·  " + span), chips().toString()));
        var again = editPerson("I3");
        onEdt(() -> {
            assertEquals("Unknown", ((javax.swing.JComboBox<?>) UiTest.find(again, javax.swing.JComboBox.class,
                    c -> "Sex".equals(c.getAccessibleContext().getAccessibleName()), "sex")).getSelectedItem());
            again.dispose();
        });
        assertTrue(saveFile().contains("0 @I3@ INDI\n1 NAME Ana /Pérez/\n1 SEX U\n"), saveFile());
    }

    // ---- birth and death -------------------------------------------------------------

    @Test
    void birthAndDeathShowAsTheLifespanEverywhere() throws Exception {
        var form = editPerson("I0");
        onEdt(() -> {
            field(form, "Born date").setText("1978");
            form.addNewEvent("DEAT");
            field(form, "Died date").setText("2030");
            field(form, "Died place").setText("La Plata");
            button(form, "Save").doClick();
        });
        closed(form);
        var span = lifespan("I0");
        assertTrue(span.contains("1978") && span.contains("2030"), span);
        eventually(() -> {
            assertTrue(texts().contains(span + "   ·   Male   ·   I0"), texts().toString());
            assertEquals(List.of("1978 | Born |  | 1978  ·  Segovia", "2005 | Occupation: Técnico | age 27 | 2005", "2030 | Died | aged 52 | 2030  ·  La Plata"),
                    timeline());
        });
        eventually(() -> assertTrue(sidebarRows().contains("Tomás Iker Fábregas | " + span + " | I0"), sidebarRows().toString()));
        go(new Place.PersonPage("I3"));
        eventually(() -> assertTrue(chips().contains("Tomás Iker Fábregas | Husband  ·  " + span), chips().toString()));
        go(new Place.PersonPage("I1"));
        eventually(() -> assertTrue(chips().contains("Tomás Iker Fábregas | " + span), chips().toString()));
        go(new Place.Ancestors("I4", 4));
        eventually(() -> assertTrue(chart().contains(List.of("Tomás Iker Fábregas", span)), chart().toString()));
        go(new Place.Descendants("I1", 3));
        eventually(() -> assertTrue(chart().contains(List.of("Tomás Iker Fábregas", span)), chart().toString()));
        var again = editPerson("I0");
        onEdt(() -> {
            assertEquals("1978", field(again, "Born date").getText());
            assertEquals("2030", field(again, "Died date").getText());
            assertEquals("La Plata", field(again, "Died place").getText());
            again.dispose();
        });
        var text = saveFile();
        assertTrue(text.contains("1 BIRT\n2 DATE 1978\n2 PLAC Segovia\n"), text);
        assertTrue(text.contains("1 DEAT\n2 DATE 2030\n2 PLAC La Plata\n"), text);
    }

    @Test
    void aDeathWithoutADateSaysDeceased() throws Exception {
        var form = editPerson("I1");
        onEdt(() -> {
            form.addNewEvent("DEAT");
            button(form, "Save").doClick();
        });
        closed(form);
        var span = lifespan("I1");
        assertTrue(api.person(fileId, "I1").summary().deceased());
        go(new Place.PersonPage("I1"));
        eventually(() -> {
            assertTrue(texts().contains(span + "   ·   Male   ·   Deceased   ·   I1"), texts().toString());
            assertTrue(timeline().contains(" | Died |  | "), timeline().toString());
        });
        var again = editPerson("I1");
        onEdt(() -> {
            assertEquals("", field(again, "Died date").getText());
            var values = new ArrayList<String>();
            walk(again, c -> { if (c instanceof JTextComponent t && "Died value".equals(t.getAccessibleContext().getAccessibleName())) values.add(t.getText()); });
            assertEquals(List.of(), values, "no Y to edit");
            again.dispose();
        });
        assertTrue(saveFile().contains("1 DEAT Y\n"), saveFile());
        go(new Place.PersonPage("I0"));
        eventually(() -> assertTrue(chips().contains("Juan Fábregas | Father  ·  " + span), chips().toString()));
    }

    @Test
    void anOtherNameWithWordsAfterTheSurnameKeepsThemWhenItsTypeChanges() throws Exception {
        go(new Place.PersonPage("I2"));
        eventually(() -> assertTrue(texts().contains("married  María López de Pérez"), texts().toString()));
        var form = editPerson("I2");
        onEdt(() -> {
            assertEquals("López", field(form, "Other name surname").getText());
            field(form, "Other name type").setText("marriage");
            button(form, "Save").doClick();
        });
        closed(form);
        eventually(() -> assertTrue(texts().contains("marriage  María López de Pérez"), texts().toString()));
        onEdt(() -> win.sidebar().searchField().setText("de perez"));
        eventually(() -> assertTrue(sidebarRows().stream().anyMatch(r -> r.startsWith("María López | ") && r.contains("matches “María López de Pérez”")),
                sidebarRows().toString()));
        var again = editPerson("I2");
        onEdt(() -> {
            assertEquals("marriage", field(again, "Other name type").getText());
            again.dispose();
        });
        assertTrue(saveFile().contains("1 NAME María /López/ de Pérez\n2 TYPE marriage\n"), saveFile());
    }

    // ---- an event's parts --------------------------------------------------------------

    @Test
    void eachPartOfAnEventShowsOnItsTimelineRowInDateOrder() throws Exception {
        var form = editPerson("I0");
        onEdt(() -> {
            field(form, "Occupation value").setText("Ingeniero");
            UiTest.button(form, b -> "Add type to Occupation".equals(b.getAccessibleContext().getAccessibleName())).doClick();
            field(form, "Occupation type").setText("Química");
            field(form, "Occupation date").setText("1995");
            field(form, "Occupation place").setText("Castilla");
            form.addNewEvent("EVEN");
            field(form, "Event type").setText("First communion");
            field(form, "Event date").setText("OCT 1988");
            button(form, "Save").doClick();
        });
        closed(form);
        eventually(() -> assertEquals(List.of(
                "1979 | Born |  | 14 MAR 1979  ·  Segovia",
                "1988 | Event (First communion) | age 9 | OCT 1988",
                "1995 | Occupation: Ingeniero (Química) | age 16 | 1995  ·  Castilla"), timeline()));
        var again = editPerson("I0");
        onEdt(() -> {
            assertEquals("Ingeniero", field(again, "Occupation value").getText());
            assertEquals("Química", field(again, "Occupation type").getText());
            assertEquals("1995", field(again, "Occupation date").getText());
            assertEquals("Castilla", field(again, "Occupation place").getText());
            assertEquals("First communion", field(again, "Event type").getText());
            assertEquals("OCT 1988", field(again, "Event date").getText());
            again.dispose();
        });
        var text = saveFile();
        assertTrue(text.contains("1 OCCU Ingeniero\n2 TYPE Química\n2 DATE 1995\n2 PLAC Castilla\n"), text);
        assertTrue(text.contains("1 EVEN\n2 TYPE First communion\n2 DATE OCT 1988\n"), text);
    }

    // ---- other names, nicknames ---------------------------------------------------------

    @Test
    void otherNamesAndNicknamesShowInTheHeaderAndFindThePerson() throws Exception {
        var form = editPerson("I0");
        onEdt(() -> {
            field(form, "Other name given").setText("Iker A.");
            field(form, "Other name type").setText("birth");
            field(form, "Nicknames").setText("Adri, Fercho");
            button(form, "Save").doClick();
        });
        closed(form);
        eventually(() -> {
            assertTrue(texts().contains("“Adri”"), texts().toString());
            assertTrue(texts().contains("“Fercho”"), texts().toString());
            assertTrue(texts().contains("birth  Iker A. Fábregas"), texts().toString());
        });
        onEdt(() -> win.sidebar().searchField().setText("fercho"));
        eventually(() -> assertEquals(List.of("Tomás Iker Fábregas | " + lifespan("I0") + "  ·  matches “Fercho” | I0"), sidebarRows()));
        var again = editPerson("I0");
        onEdt(() -> {
            assertEquals("Iker A.", field(again, "Other name given").getText());
            assertEquals("birth", field(again, "Other name type").getText());
            assertEquals("Adri, Fercho", field(again, "Nicknames").getText());
            again.dispose();
        });
        assertTrue(saveFile().contains("1 NAME Tomás Iker /Fábregas/\n2 NICK Adri\n2 NICK Fercho\n1 NAME Iker A. /Fábregas/\n2 TYPE birth\n"),
                saveFile());
    }

    // ---- notes ----------------------------------------------------------------------------

    @Test
    void aPersonsNotesShowInTheirNotesSection() throws Exception {
        var form = editPerson("I0");
        onEdt(() -> {
            field(form, "Note 1").setText("Likes trains\nand boats.");
            button(form, "Add note").doClick();
            field(form, "Note 3").setText("Moved in 2018.");
            button(form, "Save").doClick();
        });
        closed(form);
        eventually(() -> assertEquals(List.of("Likes trains\nand boats.", "Shared research note.", "Moved in 2018."), notes()));
        var again = editPerson("I0");
        onEdt(() -> {
            assertEquals("Likes trains\nand boats.", field(again, "Note 1").getText());
            assertEquals("Moved in 2018.", field(again, "Note 3").getText());
            again.dispose();
        });
        assertTrue(saveFile().contains("1 NOTE Likes trains\n2 CONT and boats.\n"), saveFile());
    }

    @Test
    void aSharedNotesTextShowsOnItsPageAndEveryPageUsingIt() throws Exception {
        go(new Place.RecordPage("N1"));
        var form = editRecord("N1");
        onEdt(() -> {
            field(form, "Note text").setText("Shared research note,\nchecked in 2024.");
            button(form, "Save").doClick();
        });
        closed(form);
        var text = "Shared research note,\nchecked in 2024.";
        eventually(() -> assertTrue(texts().contains(text), texts().toString()));
        go(new Place.PersonPage("I0"));
        eventually(() -> assertEquals(List.of("Likes trains.", text), notes()));
        go(new Place.PersonPage("I3"));
        eventually(() -> assertEquals(List.of(text), notes()));
        go(new Place.RecordList("notes"));
        eventually(() -> assertTrue(listRows().stream().anyMatch(r -> r.startsWith("Shared research note,")), listRows().toString()));
        // the person's form shows the shared note's new text
        var person = editPerson("I0");
        onEdt(() -> {
            assertTrue(UiTest.labels(person).stream().anyMatch(l -> String.valueOf(l.getText()).contains("Shared note N1")));
            var shownTexts = new ArrayList<String>();
            walk(person, c -> { if (c instanceof JTextComponent t && !t.isEditable()) shownTexts.add(t.getText()); });
            assertTrue(shownTexts.contains(text), shownTexts.toString());
            person.dispose();
        });
        var again = editRecord("N1");
        onEdt(() -> {
            assertEquals(text, field(again, "Note text").getText());
            again.dispose();
        });
        assertTrue(saveFile().contains("0 @N1@ NOTE Shared research note,\n1 CONT checked in 2024.\n"), saveFile());
    }

    @Test
    void aGedcom7SharedNoteShowsAndCanBeEditedOnItsPageAndUnlinked(@TempDir Path dir) throws Exception {
        var seven = dir.resolve("seven.ged");
        Files.writeString(seven, """
                0 HEAD
                1 GEDC
                2 VERS 7.0
                0 @I0@ INDI
                1 NAME Ana /Pérez/
                1 NOTE Her own note.
                1 SNOTE @N1@
                0 @N1@ SNOTE Shared in seven.
                0 TRLR
                """);
        var id = api.open(seven.toString()).id();
        try {
            onEdt(() -> win.openFile(seven.toString()));
            eventually(() -> assertEquals(List.of("Her own note.", "Shared in seven."), notes()));
            // the shared note's own page edits its text, and the person's page echoes it
            go(new Place.RecordPage("N1"));
            var record = editRecord("N1");
            onEdt(() -> {
                field(record, "Note text").setText("Shared in seven, edited.");
                button(record, "Save").doClick();
            });
            closed(record);
            go(new Place.PersonPage("I0"));
            eventually(() -> assertEquals(List.of("Her own note.", "Shared in seven, edited."), notes()));
            // the person's form shows it read-only, and unlinks it
            var form = editPerson("I0");
            onEdt(() -> {
                assertTrue(UiTest.labels(form).stream().anyMatch(l -> String.valueOf(l.getText()).contains("Shared note N1")));
                assertEquals("Her own note.", field(form, "Note 1").getText());
                UiTest.button(form, b -> "Remove Note 2".equals(b.getAccessibleContext().getAccessibleName())).doClick();
                button(form, "Save").doClick();
            });
            closed(form);
            eventually(() -> assertEquals(List.of("Her own note."), notes()));
            api.save(id, false, null);
            var text = Files.readString(seven);
            assertFalse(text.contains("1 SNOTE @N1@"), text);
            assertTrue(text.contains("0 @N1@ SNOTE Shared in seven, edited.\n"), text);
        } finally {
            api.close(id, true);
        }
    }

    // ---- the family ---------------------------------------------------------------------

    @Test
    void theMarriageShowsOnTheFamilyThePartnersAndTheCharts() throws Exception {
        go(new Place.FamilyPage("F1"));
        var form = editFamily("F1");
        onEdt(() -> {
            field(form, "Married date").setText("12 mar 2009");
            field(form, "Married place").setText("Segovia");
            button(form, "Add note").doClick();
            field(form, "Note").setText("Civil wedding.");
            button(form, "Save").doClick();
        });
        closed(form);
        eventually(() -> {
            assertTrue(texts().contains("Married 2009   ·   1 child"), texts().toString());
            assertEquals(List.of("2009 | Married |  | 12 MAR 2009  ·  Segovia"), timeline());
            assertEquals(List.of("Civil wedding."), notes());
        });
        go(new Place.PersonPage("I0"));
        eventually(() -> assertTrue(texts().contains("Married  ·  12 MAR 2009  ·  Segovia"), texts().toString()));
        go(new Place.PersonPage("I3"));
        eventually(() -> assertTrue(texts().contains("Married  ·  12 MAR 2009  ·  Segovia"), texts().toString()));
        go(new Place.Descendants("I0", 3));
        eventually(() -> assertTrue(chart().contains(List.of("m. 12 MAR 2009  ·  F1", "Ana Pérez")), chart().toString()));
        go(new Place.RecordList("families"));
        eventually(() -> assertTrue(listRows().contains("Tomás Iker Fábregas & Ana Pérez | Married 2009  ·  1 child | F1"),
                listRows().toString()));
        var again = editFamily("F1");
        onEdt(() -> {
            assertEquals("12 MAR 2009", field(again, "Married date").getText());
            assertEquals("Segovia", field(again, "Married place").getText());
            assertEquals("Civil wedding.", field(again, "Note").getText());
            again.dispose();
        });
        assertTrue(saveFile().contains("1 MARR\n2 DATE 12 MAR 2009\n2 PLAC Segovia\n"), saveFile());
    }

    @Test
    void aDivorceShowsOnTheFamilyThePartnersAndTheChart() throws Exception {
        go(new Place.FamilyPage("F1"));
        var form = editFamily("F1");
        onEdt(() -> {
            form.addNewEvent("DIV");
            field(form, "Divorced date").setText("2015");
            button(form, "Save").doClick();
        });
        closed(form);
        eventually(() -> {
            assertTrue(texts().contains("Married 2010   ·   1 child   ·   Divorced"), texts().toString());
            assertEquals(List.of("2010 | Married |  | 2010  ·  Cuenca", "2015 | Divorced |  | 2015"), timeline());
        });
        go(new Place.PersonPage("I3"));
        eventually(() -> assertTrue(texts().contains("Married  ·  2010  ·  Cuenca  ·  Divorced"), texts().toString()));
        go(new Place.Descendants("I0", 3));
        eventually(() -> assertTrue(chart().contains(List.of("m. 2010  ·  divorced  ·  F1", "Ana Pérez")), chart().toString()));
        var again = editFamily("F1");
        onEdt(() -> {
            assertEquals("2015", field(again, "Divorced date").getText());
            again.dispose();
        });
        assertTrue(saveFile().contains("1 DIV\n2 DATE 2015\n"), saveFile());
    }

    // ---- sources and media ---------------------------------------------------------------

    @Test
    void aSourcesTitleShowsOnItsPageTheListAndThePagesCitingIt() throws Exception {
        go(new Place.RecordPage("S1"));
        var form = editRecord("S1");
        onEdt(() -> {
            field(form, "Title").setText("Parish register of Segovia");
            button(form, "Save").doClick();
        });
        closed(form);
        var t = "Parish register of Segovia";
        eventually(() -> assertEquals(t, title()));
        go(new Place.RecordList("sources"));
        eventually(() -> assertTrue(listRows().contains(t + " | Used 2 times | S1"), listRows().toString()));
        go(new Place.PersonPage("I0"));
        eventually(() -> assertTrue(links().contains(t), links().toString()));
        go(new Place.FamilyPage("F1"));
        eventually(() -> assertTrue(links().contains(t), links().toString()));
        var again = editRecord("S1");
        onEdt(() -> {
            assertEquals(t, field(again, "Title").getText());
            again.dispose();
        });
        assertTrue(saveFile().contains("0 @S1@ SOUR\n1 TITL Parish register of Segovia\n"), saveFile());
    }

    @Test
    void aMediaTitleShowsUnderThePhotoAndInTheMediaList() throws Exception {
        go(new Place.PersonPage("I0"));
        eventually(() -> assertTrue(texts().contains("Wedding day"), texts().toString()));
        go(new Place.RecordPage("M1"));
        var form = editRecord("M1");
        onEdt(() -> {
            field(form, "Title").setText("Our wedding");
            button(form, "Save").doClick();
        });
        closed(form);
        eventually(() -> assertEquals("Our wedding", title()));
        go(new Place.RecordList("media"));
        eventually(() -> assertTrue(listRows().contains("Our wedding | Used once | M1"), listRows().toString()));
        go(new Place.PersonPage("I0"));
        eventually(() -> assertTrue(texts().contains("Our wedding"), texts().toString()));
        var photos = api.person(fileId, "I0").photos();
        onEdt(() -> {
            var viewer = new PhotoViewer(win, win.images(), photos, 0, "Tomás Iker Fábregas");
            assertTrue(UiTest.labels(viewer.getRootPane()).stream().anyMatch(l -> "Our wedding".equals(l.getText())),
                    UiTest.labels(viewer.getRootPane()).stream().map(JLabel::getText).toList().toString());
            viewer.dispose();
        });
        var again = editRecord("M1");
        onEdt(() -> {
            assertEquals("Our wedding", field(again, "Title").getText());
            again.dispose();
        });
        assertTrue(saveFile().contains("2 TITL Our wedding\n"), saveFile());
    }

    // ---- edits made elsewhere ---------------------------------------------------------------

    @Test
    void anEditMadeElsewhereReachesTheOpenPageAndTheSidebar() throws Exception {
        api.editPerson(fileId, "I0", Map.of(
                "set", Map.of("given", "Tomás José", "birth.date", "1980"),
                "seen", Map.of("given", "Tomás Iker", "birth.date", "14 MAR 1979")));
        eventually(() -> {
            assertEquals("Tomás José Fábregas", title());
            assertTrue(texts().contains(lifespan("I0") + "   ·   Male   ·   I0"), texts().toString());
            assertTrue(sidebarRows().contains("Tomás José Fábregas | " + lifespan("I0") + " | I0"), sidebarRows().toString());
        });
        go(new Place.Descendants("I1", 3));
        api.editPerson(fileId, "I0", Map.of("set", Map.of("surname", "Gil"), "seen", Map.of("surname", "Fábregas")));
        eventually(() -> assertTrue(chart().contains(List.of("Tomás José Gil", lifespan("I0"))), chart().toString()));
        go(new Place.FamilyPage("F1"));
        api.editFamily(fileId, "F1", Map.of("events", List.of(
                Map.of("ref", "MARR:0", "seen", Map.of("date", "2010"), "set", Map.of("date", "2011")))));
        eventually(() -> assertTrue(texts().contains("Married 2011   ·   1 child"), texts().toString()));
        go(new Place.RecordPage("S1"));
        api.editRecord(fileId, "S1", Map.of("lines", List.of(
                Map.of("ref", "0", "seen", Map.of("value", "Parish register"), "set", Map.of("value", "Register")))));
        eventually(() -> assertEquals("Register", title()));
    }

    @Test
    void anOpenFormHearsOfAnEditElsewhereAndSavingMergesWhatDoesntClash() throws Exception {
        var form = editPerson("I0");
        onEdt(() -> field(form, "Occupation value").setText("Ingeniero"));
        api.editPerson(fileId, "I0", Map.of("events", List.of(
                Map.of("ref", "OCCU:0", "seen", Map.of("date", "2005"), "set", Map.of("date", "2006")))));
        // the page behind it refreshes, and the form says so
        eventually(() -> assertTrue(timeline().contains("2006 | Occupation: Técnico | age 27 | 2006"), timeline().toString()));
        eventually(() -> assertTrue(banner(form).contains("changed elsewhere"), banner(form)));
        onEdt(() -> button(form, "Save").doClick());
        eventually(() -> assertTrue(timeline().contains("2006 | Occupation: Ingeniero | age 27 | 2006"), timeline().toString()));
        onEdt(() -> assertFalse(form.isDisplayable()));
    }

    @Test
    void anOpenFormThatClashesWithAnEditElsewhereKeepsTheUsersValueAndTakesTheirs() throws Exception {
        var form = editPerson("I0");
        onEdt(() -> {
            field(form, "Occupation value").setText("Ingeniero");
            field(form, "Occupation date").setText("2007");
        });
        api.editPerson(fileId, "I0", Map.of("events", List.of(
                Map.of("ref", "OCCU:0", "seen", Map.of("date", "2005", "place", ""), "set", Map.of("date", "2006", "place", "Cuenca")))));
        eventually(() -> assertTrue(banner(form).contains("changed elsewhere"), banner(form)));
        onEdt(() -> button(form, "Save").doClick());
        eventually(() -> assertTrue(banner(form).contains("while you were editing"), banner(form)));
        onEdt(() -> {
            assertEquals("Ingeniero", field(form, "Occupation value").getText(), "the user's change is kept");
            assertEquals("2007", field(form, "Occupation date").getText(), "the user's change is kept");
            assertEquals("error", field(form, "Occupation date").getClientProperty("JComponent.outline"));
            assertEquals("Cuenca", field(form, "Occupation place").getText(), "the other change is taken");
            button(form, "Save").doClick();
        });
        closed(form);
        eventually(() -> assertTrue(timeline().contains("2007 | Occupation: Ingeniero | age 28 | 2007  ·  Cuenca"), timeline().toString()));
    }

    @Test
    void anOpenFormIsNotToldOfEditsToOtherRecords() throws Exception {
        var form = editPerson("I0");
        var note = editRecord("N1");
        api.editRecord(fileId, "S1", Map.of("lines", List.of(
                Map.of("ref", "0", "seen", Map.of("value", "Parish register"), "set", Map.of("value", "Register")))));
        api.editPerson(fileId, "I3", Map.of("set", Map.of("given", "Ana María"), "seen", Map.of("given", "Ana")));
        // the page behind them has caught up with both
        eventually(() -> {
            assertTrue(links().contains("Register"), links().toString());
            assertTrue(chips().contains("Ana María Pérez | Wife  ·  " + lifespan("I3")), chips().toString());
        });
        Thread.sleep(300);
        onEdt(() -> {
            assertEquals("", banner(form), "nothing of I0's changed");
            assertEquals("", banner(note), "nothing of N1's changed");
        });
        // a change to what the form shows is told
        api.editPerson(fileId, "I0", Map.of("notes", List.of(
                Map.of("ref", "NOTE:0", "seen", Map.of("text", "Likes trains."), "set", Map.of("text", "Likes planes.")))));
        eventually(() -> assertTrue(banner(form).contains("changed elsewhere"), banner(form)));
        onEdt(() -> assertEquals("", banner(note)));
        api.editRecord(fileId, "N1", Map.of("seenText", "Shared research note.", "text", "Theirs."));
        eventually(() -> assertTrue(banner(note).contains("changed elsewhere"), banner(note)));
    }

    @Test
    void anOpenRecordFormHearsOfAnEditElsewhere() throws Exception {
        go(new Place.RecordPage("N1"));
        var form = editRecord("N1");
        onEdt(() -> field(form, "Note text").setText("Mine."));
        api.editRecord(fileId, "N1", Map.of("seenText", "Shared research note.", "text", "Theirs."));
        eventually(() -> assertTrue(texts().contains("Theirs."), texts().toString()));
        eventually(() -> assertTrue(banner(form).contains("changed elsewhere"), banner(form)));
        onEdt(() -> button(form, "Save").doClick());
        eventually(() -> assertTrue(banner(form).contains("while you were editing"), banner(form)));
        onEdt(() -> assertEquals("Mine.", field(form, "Note text").getText()));
    }

    // ---- driving the window --------------------------------------------------------------

    private void go(Place place) throws Exception {
        onEdt(() -> win.go(place));
        eventually(() -> {
            assertEquals(place, win.current());
            assertFalse(texts().isEmpty());
        });
    }

    private EditDialog editPerson(String key) throws Exception {
        shown.set(null);
        onEdt(() -> win.editPerson(key));
        return (EditDialog) waitForDialog();
    }

    private EditDialog editFamily(String key) throws Exception {
        shown.set(null);
        onEdt(() -> win.editFamily(key));
        return (EditDialog) waitForDialog();
    }

    private RecordEditDialog editRecord(String key) throws Exception {
        shown.set(null);
        onEdt(() -> win.editRecord(key));
        return (RecordEditDialog) waitForDialog();
    }

    private JDialog waitForDialog() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (shown.get() == null) {
            if (System.nanoTime() > end) throw new AssertionError("no form opened");
            Thread.sleep(30);
        }
        return shown.get();
    }

    /// Waits for a form to close: its save went through.
    private static void closed(java.awt.Window form) throws Exception {
        eventually(() -> assertFalse(form.isDisplayable(), "the form is still open: " + banner(form)));
    }

    /// The lifespan the daemon gives `key` now: what every place should show.
    private String lifespan(String key) throws Exception {
        return api.person(fileId, key).summary().lifespan();
    }

    private String saveFile() throws Exception {
        api.save(fileId, false, null);
        return Files.readString(file);
    }

    /// Retries `check` on the EDT until it passes, for up to 15 seconds.
    static void eventually(UiTest.Check check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            try {
                onEdt(check);
                return;
            } catch (AssertionError | RuntimeException e) {
                if (System.nanoTime() > end) throw e;
            }
            Thread.sleep(60);
        }
    }

    // ---- reading the window ----------------------------------------------------------------

    private JComponent page() {
        return win.page();
    }

    /// The page's heading: its largest label.
    private String title() {
        JLabel best = null;
        for (var l : UiTest.labels(page())) {
            if (best == null || l.getFont().getSize2D() > best.getFont().getSize2D()) best = l;
        }
        return best == null ? null : best.getText();
    }

    /// Every label's and paragraph's text on the page.
    private List<String> texts() {
        var out = new ArrayList<String>();
        walk(page(), c -> {
            if (!c.isVisible()) return;
            if (c instanceof JLabel l && l.getText() != null && !l.getText().isEmpty()) out.add(l.getText());
            if (c instanceof JTextComponent t) out.add(t.getText());
        });
        return out;
    }

    private List<String> links() {
        var out = new ArrayList<String>();
        walk(page(), c -> { if (c instanceof Ui.LinkLabel l) out.add(l.getText()); });
        return out;
    }

    /// Each person chip: "name | note  ·  lifespan".
    private List<String> chips() {
        var out = new ArrayList<String>();
        walk(page(), c -> {
            if (c instanceof PersonChip chip) out.add(String.join(" | ", UiTest.labels(chip).stream()
                    .map(JLabel::getText).filter(t -> t != null && !t.isEmpty()).toList()));
        });
        return out;
    }

    /// Each timeline row: "year | title | age | date  ·  place".
    private List<String> timeline() {
        var out = new ArrayList<String>();
        walk(page(), c -> {
            if (c instanceof PersonView.TimelineRow r) {
                var ls = UiTest.labels(r);
                var detail = new ArrayList<String>();
                walk(r, x -> { if (x instanceof JTextComponent t) detail.add(t.getText()); });
                out.add(ls.get(0).getText() + " | " + ls.get(1).getText() + " | " + (ls.size() > 2 ? ls.get(2).getText() : "")
                        + " | " + String.join("", detail));
            }
        });
        return out;
    }

    /// The Notes section's paragraphs.
    private List<String> notes() {
        var out = new ArrayList<String>();
        walk(page(), c -> {
            if (c instanceof Ui.Card card && card.getComponentCount() > 1 && card.getComponent(0) instanceof JLabel h
                    && h.getText().equals("NOTES")) {
                walk(card, x -> { if (x instanceof JTextComponent t) out.add(t.getText()); });
            }
        });
        return out;
    }

    /// The chart's boxes, as their lines.
    private List<List<String>> chart() {
        var out = new ArrayList<List<String>>();
        walk(page(), c -> { if (c instanceof TreeChart t) out.addAll(t.boxes()); });
        return out;
    }

    /// The sidebar's people as drawn: "name | detail | key".
    private List<String> sidebarRows() {
        return rendered(win.sidebar().peopleList());
    }

    /// A list page's rows as drawn: "title | detail | key".
    private List<String> listRows() {
        var out = new ArrayList<String>();
        walk(page(), c -> { if (c instanceof JList<?> l) out.addAll(rendered(l)); });
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> rendered(JList list) {
        var out = new ArrayList<String>();
        var model = list.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            var c = list.getCellRenderer().getListCellRendererComponent(list, model.getElementAt(i), i, false, false);
            out.add(String.join(" | ", UiTest.labels((Container) c).stream()
                    .map(JLabel::getText).filter(t -> t != null && !t.isBlank()).toList()));
        }
        return out;
    }

    private static String banner(Container root) {
        var out = new ArrayList<String>();
        walk(root, c -> {
            if (c instanceof Ui.Banner b && b.isVisible()) walk(b, t -> { if (t instanceof JTextComponent x) out.add(x.getText()); });
        });
        return String.join("\n", out);
    }
}
