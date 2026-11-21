package ged.desktop;

import ged.client.DaemonClient;
import ged.daemon.Home;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/// The app's windows, built for real against a real daemon (started from the
/// test class path on a port of its own) and inspected component by component.
///
/// These need a display, so they run in their own surefire execution, without
/// `java.awt.headless` (see desktop/pom.xml); CI gives them one with xvfb-run.
/// Nothing is shown: windows are built and laid out, never made visible.
@Tag("ui")
class UiTest {

    /// Grouped by kind, as real files often are: the residences first, the
    /// graduation with a type and nothing else. With a note of their own and
    /// a shared one, a further name, a nickname, a family and a source.
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
            2 PLAC Segovia, Provincia de Castilla, Spain
            1 RESI
            2 DATE 1979
            1 RESI
            2 DATE 1981
            1 RESI
            2 DATE 2005
            1 GRAD
            2 TYPE Técnico Químico
            1 BAPM
            2 DATE APR 1980
            1 EVEN
            2 TYPE First communion
            2 DATE OCT 1988
            1 NOTE Likes trains.
            1 NOTE @N1@
            1 FAMS @F1@
            0 @I1@ INDI
            1 NAME Ana /Pérez/
            1 SEX F
            1 FAMS @F1@
            0 @F1@ FAM
            1 HUSB @I0@
            1 WIFE @I1@
            1 MARR
            2 DATE 2010
            0 @N1@ NOTE Shared research note.
            0 @S1@ SOUR
            1 TITL Parish register
            1 AUTH Unknown
            0 TRLR
            """;

    static Api api;
    static DaemonClient client;
    String fileId;
    Path file;

    @BeforeAll
    static void start() throws Exception {
        var home = Home.fromEnvironment();
        home.create();
        Files.writeString(home.settingsFile(), "port=7433\n");
        client = DaemonClient.ensure(home, Duration.ofSeconds(30));
        api = new Api(new Connection(home, client));
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
        file = dir.resolve("tomas.ged");
        Files.writeString(file, GED);
        fileId = api.open(file.toString()).id();
    }

    // ---- the person's edit form: what it shows ---------------------------------------

    @Test
    void everyEventIsARowInTimelineOrderNamedByItsKind() throws Exception {
        var dialog = editDialog("I0", r -> { });
        onEdt(() -> {
            assertEquals("Técnico Químico", field(dialog, "Graduated type").getText());
            assertEquals("", field(dialog, "Graduated date").getText());
            assertEquals("1981", field(dialog, "Residence 2 date").getText());
            assertEquals("First communion", field(dialog, "Event type").getText());
            assertEquals("OCT 1988", field(dialog, "Event date").getText());
            assertEquals(List.of("Born", "Residence", "Baptised", "Residence", "Event", "Residence", "Graduated"),
                    rowLabels(dialog, List.of("Born", "Residence", "Baptised", "Event", "Graduated", "Ordained")));
            assertEquals("Likes trains.", field(dialog, "Note 1").getText());
            assertEquals("Iker", field(dialog, "Other name given").getText());
            assertEquals("aka", field(dialog, "Other name type").getText());
            assertEquals("Adri", field(dialog, "Nicknames").getText());
            assertTrue(labels(dialog).stream().anyMatch(l -> l.getText().contains("Shared note N1")), "the shared note says where to edit it");
            dialog.dispose();
        });
    }

    @Test
    void anEventWithoutATypeOffersOneOnRequest() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        onEdt(() -> {
            var before = new ArrayList<Component>();
            walk(dialog, c -> { if (c instanceof JTextComponent t && "Born type".equals(t.getAccessibleContext().getAccessibleName())) before.add(c); });
            assertEquals(List.of(), before, "no empty type field on the birth row");
            var add = button(dialog, b -> "Add type to Born".equals(b.getAccessibleContext().getAccessibleName()));
            add.doClick();
            field(dialog, "Born type").setText("Home birth");
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        assertEquals("Home birth", event(api.person(fileId, "I0"), "BIRT").type());
    }

    @Test
    void longValuesShowFromTheirStart() throws Exception {
        var dialog = editDialog("I0", r -> { });
        onEdt(() -> {
            var place = field(dialog, "Born place");
            assertEquals("Segovia, Provincia de Castilla, Spain", place.getText());
            assertEquals(0, place.getCaretPosition());
            dialog.dispose();
        });
    }

    @Test
    void anUntouchedFormSendsNothing() throws Exception {
        var dialog = editDialog("I0", r -> { });
        onEdt(() -> {
            assertEquals(Map.of(), dialog.request());
            dialog.dispose();
        });
    }

    // ---- the person's edit form: saving ------------------------------------------------

    @Test
    void typingTheGraduationsTypeAndDateSavesThemAndTheTimelinePlacesIt() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        onEdt(() -> {
            field(dialog, "Graduated type").setText("Técnico en Química");
            field(dialog, "Graduated date").setText("dec 1997");
            var body = dialog.request();
            assertEquals(Set.of("events"), body.keySet(), "only what changed: " + body);
            button(dialog, "Save").doClick();
        });
        var person = Model.Person.from(Model.map(saved.get(15, TimeUnit.SECONDS), "person"));
        var grad = event(person, "GRAD");
        assertEquals("Técnico en Química", grad.fields().get("type"));
        assertEquals("DEC 1997", grad.fields().get("date"));
        assertEquals(List.of("Born", "Residence", "Baptised", "Residence", "Event", "Graduated", "Residence"),
                timelineTitles(person).stream().map(t -> t.split("[ :(]")[0]).toList());
    }

    @Test
    void editingTheSecondResidenceAndRemovingTheThird() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        onEdt(() -> {
            field(dialog, "Residence 2 date").setText("1982");
            field(dialog, "Residence 2 place").setText("Serrano 101, Segovia");
            button(dialog, b -> "Remove Residence 3".equals(b.getAccessibleContext().getAccessibleName())).doClick();
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        var residences = api.person(fileId, "I0").events().stream().filter(e -> e.tag().equals("RESI")).toList();
        assertEquals(List.of("1979", "1982"), residences.stream().map(Model.Event::date).toList());
        assertEquals("Serrano 101, Segovia", residences.get(1).place());
        var text = saveFile();
        assertTrue(text.contains("1 RESI\n2 DATE 1982\n2 PLAC Serrano 101, Segovia\n"), text);
        assertFalse(text.contains("2005"), text);
    }

    @Test
    void addingAnEventWithAType() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        onEdt(() -> {
            dialog.addNewEvent("EVEN");
            // the person had one "Event" already: the rows are now numbered
            assertEquals("First communion", field(dialog, "Event 1 type").getText());
            field(dialog, "Event 2 type").setText("Confirmation");
            field(dialog, "Event 2 date").setText("1993");
            field(dialog, "Event 2 place").setText("Cuenca");
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        var added = api.person(fileId, "I0").events().stream().filter(e -> e.type().equals("Confirmation")).findFirst().orElseThrow();
        assertEquals("EVEN", added.tag());
        assertEquals("1993", added.date());
        assertEquals("Cuenca", added.place());
    }

    @Test
    void anEventWithoutTheTypeItNeedsIsRefusedAtItsRow() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        onEdt(() -> {
            dialog.addNewEvent("EVEN");
            field(dialog, "Event 2 date").setText("1993");
            button(dialog, "Save").doClick();
        });
        var banner = waitFor(() -> visibleBanner(dialog));
        onEdt(() -> {
            assertTrue(banner.contains("needs a type"), banner);
            assertTrue(banner.startsWith("Event 2"), banner);
            assertEquals("error", field(dialog, "Event 2 type").getClientProperty("JComponent.outline"));
            assertTrue(dialog.isDisplayable(), "the form stays open");
            dialog.dispose();
        });
        assertFalse(saved.isDone());
        assertEquals(1, api.person(fileId, "I0").events().stream().filter(e -> e.tag().equals("EVEN")).count(), "nothing was applied");
    }

    @Test
    void addingDiedWithNothingInItMarksTheDeath() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        onEdt(() -> {
            dialog.addNewEvent("DEAT");
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        assertTrue(api.person(fileId, "I0").summary().deceased());
        assertTrue(saveFile().contains("1 DEAT Y\n"));
    }

    @Test
    void editingANoteAndAddingOne() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        onEdt(() -> {
            field(dialog, "Note 1").setText("Likes trains\nand boats.");
            button(dialog, "Add note").doClick();
            field(dialog, "Note 3").setText("Moved in 2018.");
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        var notes = api.person(fileId, "I0").noteItems();
        assertEquals(List.of("Likes trains\nand boats.", "Shared research note.", "Moved in 2018."),
                notes.stream().map(Model.NoteItem::text).toList());
        assertEquals("N1", notes.get(1).shared());
    }

    @Test
    void unlinkingTheSharedNoteKeepsTheNote() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        onEdt(() -> {
            button(dialog, b -> "Remove Note 2".equals(b.getAccessibleContext().getAccessibleName())).doClick();
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        assertEquals(List.of("Likes trains."), api.person(fileId, "I0").noteItems().stream().map(Model.NoteItem::text).toList());
        assertEquals("Shared research note.", api.record(fileId, "N1").text());
    }

    @Test
    void editingAnOtherNameAndTheNicknames() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        onEdt(() -> {
            field(dialog, "Other name given").setText("Iker A.");
            field(dialog, "Nicknames").setText("Adri,  Fer");
            button(dialog, "Add other name").doClick();
            field(dialog, "Other name 2 given").setText("Tomás");
            field(dialog, "Other name 2 surname").setText("Fábregas García");
            field(dialog, "Other name 2 type").setText("birth");
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        var p = api.person(fileId, "I0");
        assertEquals(List.of("Adri", "Fer"), p.nicknames());
        assertEquals(List.of("Iker A.", "Tomás"), p.otherNames().stream().map(n -> n.fields().get("given")).toList());
        var text = saveFile();
        assertTrue(text.contains("1 NAME Iker A. /Fábregas/\n2 TYPE aka\n"), text);
        assertTrue(text.contains("1 NAME Tomás /Fábregas García/\n2 TYPE birth\n"), text);
    }

    @Test
    void theNameFieldsAndTheListsGoInOneRequest() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        onEdt(() -> {
            field(dialog, "Given names").setText("Tomás");
            field(dialog, "Born date").setText("21 mar 1979");
            var body = dialog.request();
            assertEquals(Map.of("given", "Tomás"), body.get("set"));
            assertEquals(Map.of("given", "Tomás Iker"), body.get("seen"));
            assertEquals(List.of(Map.of("ref", "BIRT:0", "seen", Map.of("date", "14 MAR 1979"), "set", Map.of("date", "21 mar 1979"))),
                    body.get("events"));
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        var p = api.person(fileId, "I0");
        assertEquals("Tomás", p.summary().given());
        assertEquals("21 MAR 1979", event(p, "BIRT").date());
    }

    @Test
    void aConflictKeepsTheUsersEditsAndTakesTheirs() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = editDialog("I0", saved::complete);
        // someone else moves the second residence meanwhile
        api.editPerson(fileId, "I0", Map.of("events", List.of(
                Map.of("ref", "RESI:1", "seen", Map.of("date", "1981"), "set", Map.of("date", "1983")))));
        onEdt(() -> {
            field(dialog, "Residence 2 date").setText("1982");
            field(dialog, "Graduated date").setText("1997");
            button(dialog, "Save").doClick();
        });
        var banner = waitFor(() -> visibleBanner(dialog));
        onEdt(() -> {
            assertTrue(banner.contains("while you were editing"), banner);
            assertEquals("1982", field(dialog, "Residence 2 date").getText(), "the user's own change is kept");
            assertEquals("error", field(dialog, "Residence 2 date").getClientProperty("JComponent.outline"));
            assertEquals("1997", field(dialog, "Graduated date").getText());
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        var p = api.person(fileId, "I0");
        assertEquals(List.of("1979", "1982", "2005"), p.events().stream().filter(e -> e.tag().equals("RESI")).map(Model.Event::date).toList());
        assertEquals("1997", event(p, "GRAD").date());
    }

    @Test
    void aConflictThatMovedTheEventsReloadsThem() throws Exception {
        var dialog = editDialog("I0", r -> { });
        api.editPerson(fileId, "I0", Map.of("events", List.of(Map.of("ref", "RESI:0", "seen", Map.of("date", "1979"), "remove", true))));
        onEdt(() -> {
            field(dialog, "Residence 2 date").setText("1982");
            button(dialog, "Save").doClick();
        });
        var banner = waitFor(() -> visibleBanner(dialog));
        onEdt(() -> {
            assertTrue(banner.contains("reloaded"), banner);
            assertEquals("2005", field(dialog, "Residence 2 date").getText(), "the latest residences");
            dialog.dispose();
        });
    }

    // ---- the family's edit form --------------------------------------------------------

    @Test
    void editingTheMarriageAndAddingAnEngagement() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = familyDialog("F1", saved::complete);
        onEdt(() -> {
            assertEquals("2010", field(dialog, "Married date").getText());
            field(dialog, "Married date").setText("12 mar 2010");
            field(dialog, "Married place").setText("Segovia");
            dialog.addNewEvent("ENGA");
            field(dialog, "Engaged date").setText("2009");
            button(dialog, "Add note").doClick();
            field(dialog, "Note").setText("Civil wedding.");
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        var f = api.family(fileId, "F1");
        assertEquals(List.of("ENGA", "MARR"), f.events().stream().map(Model.Event::tag).toList());
        assertEquals("12 MAR 2010", f.events().get(1).date());
        assertEquals("Segovia", f.events().get(1).place());
        assertEquals(List.of("Civil wedding."), f.notes());
    }

    // ---- a record's edit form ----------------------------------------------------------

    @Test
    void editingRemovingAndAddingASourcesLines() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = recordDialog("S1", saved::complete);
        onEdt(() -> {
            assertEquals("Parish register", field(dialog, "Title").getText());
            field(dialog, "Title").setText("Parish register of Segovia");
            button(dialog, b -> "Remove Author".equals(b.getAccessibleContext().getAccessibleName())).doClick();
            button(dialog, "Add line").doClick();
            var tag = find(dialog, javax.swing.JComboBox.class,
                    c -> "New line tag".equals(c.getAccessibleContext().getAccessibleName()), "new line tag");
            tag.setSelectedItem("PUBL");
            field(dialog, "New line value").setText("Diocese of Segovia");
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        var text = saveFile();
        assertTrue(text.contains("0 @S1@ SOUR\n1 TITL Parish register of Segovia\n1 PUBL Diocese of Segovia\n"), text);
    }

    @Test
    void editingANotesText() throws Exception {
        var saved = new CompletableFuture<Map<String, Object>>();
        var dialog = recordDialog("N1", saved::complete);
        onEdt(() -> {
            field(dialog, "Note text").setText("Shared research note,\nupdated.");
            button(dialog, "Save").doClick();
        });
        saved.get(15, TimeUnit.SECONDS);
        assertEquals("Shared research note,\nupdated.", api.record(fileId, "N1").text());
    }

    // ---- the person page --------------------------------------------------------------

    @Test
    void theTimelineIsInDateOrder() throws Exception {
        var person = api.person(fileId, "I0");
        assertEquals(List.of("1979", "1979", "1980", "1981", "1988", "2005", ""), timelineYears(person));
    }

    // ---- helpers ----------------------------------------------------------------------

    private EditDialog editDialog(String key, java.util.function.Consumer<Map<String, Object>> onSaved) throws Exception {
        var defs = api.fields();
        var p = api.person(fileId, key);
        var id = fileId;
        var ref = new AtomicReference<EditDialog>();
        SwingUtilities.invokeAndWait(() -> ref.set(new EditDialog(null, "Edit", "", null, "person", "person",
                defs.person(), defs.dates(), EditDialog.Content.of(p), body -> api.editPerson(id, key, body), onSaved, null)));
        return ref.get();
    }

    private EditDialog familyDialog(String key, java.util.function.Consumer<Map<String, Object>> onSaved) throws Exception {
        var defs = api.fields();
        var f = api.family(fileId, key);
        var id = fileId;
        var ref = new AtomicReference<EditDialog>();
        SwingUtilities.invokeAndWait(() -> ref.set(new EditDialog(null, "Edit", "", null, "family", "family",
                defs.family(), defs.dates(), EditDialog.Content.of(f), body -> api.editFamily(id, key, body), onSaved, null)));
        return ref.get();
    }

    private RecordEditDialog recordDialog(String key, java.util.function.Consumer<Map<String, Object>> onSaved) throws Exception {
        var r = api.record(fileId, key);
        var id = fileId;
        var ref = new AtomicReference<RecordEditDialog>();
        SwingUtilities.invokeAndWait(() -> ref.set(new RecordEditDialog(null, r, body -> api.editRecord(id, key, body), onSaved)));
        return ref.get();
    }

    private String saveFile() throws Exception {
        api.save(fileId, false, null);
        return Files.readString(file);
    }

    private static Model.Event event(Model.Person p, String tag) {
        return p.events().stream().filter(e -> e.tag().equals(tag)).findFirst().orElseThrow();
    }

    /// The event rows' labels, top to bottom.
    private static List<String> rowLabels(Container root, List<String> kinds) {
        return labels(root).stream().filter(l -> l.isVisible() && kinds.contains(l.getText())).map(JLabel::getText).toList();
    }

    /// The banner's text when it shows, else null.
    private static String visibleBanner(Container root) {
        var out = new ArrayList<String>();
        walk(root, c -> {
            if (c instanceof Ui.Banner b && b.isVisible()) {
                walk(b, t -> { if (t instanceof JTextComponent x) out.add(x.getText()); });
            }
        });
        return out.isEmpty() ? null : out.getFirst();
    }

    /// Polls `check` on the EDT until it gives a value.
    private static <T> T waitFor(java.util.concurrent.Callable<T> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            var got = new AtomicReference<T>();
            onEdt(() -> got.set(check.call()));
            if (got.get() != null) return got.get();
            Thread.sleep(50);
        }
        throw new AssertionError("timed out");
    }

    private List<String> timelineYears(Model.Person p) throws Exception {
        return timelineRows(p).stream().map(r -> labels(r).getFirst().getText()).toList();
    }

    private List<String> timelineTitles(Model.Person p) throws Exception {
        return timelineRows(p).stream().map(r -> labels(r).get(1).getText()).toList();
    }

    private List<PersonView.TimelineRow> timelineRows(Model.Person p) throws Exception {
        var out = new ArrayList<PersonView.TimelineRow>();
        onEdt(() -> {
            var page = PersonView.build(p, new NoActions(new Images(api)));
            walk(page, c -> { if (c instanceof PersonView.TimelineRow r) out.add(r); });
        });
        return out;
    }

    static JTextComponent field(Container root, String accessibleName) {
        return find(root, JTextComponent.class, f -> accessibleName.equals(f.getAccessibleContext().getAccessibleName()), accessibleName);
    }

    static JLabel label(Container root, Predicate<JLabel> p) {
        return find(root, JLabel.class, p, "label");
    }

    static AbstractButton button(Container root, String text) {
        return find(root, AbstractButton.class, b -> text.equals(b.getText()), "button " + text);
    }

    static AbstractButton button(Container root, Predicate<AbstractButton> p) {
        return find(root, AbstractButton.class, p, "button");
    }

    static List<JLabel> labels(Container root) {
        var out = new ArrayList<JLabel>();
        walk(root, c -> { if (c instanceof JLabel l) out.add(l); });
        return out;
    }

    static <T extends Component> T find(Container root, Class<T> type, Predicate<T> p, String what) {
        var found = new ArrayList<T>();
        walk(root, c -> { if (type.isInstance(c) && p.test(type.cast(c))) found.add(type.cast(c)); });
        assertEquals(1, found.size(), "exactly one " + what);
        return found.getFirst();
    }

    static void walk(Component c, java.util.function.Consumer<Component> each) {
        each.accept(c);
        if (c instanceof Container k) for (var child : k.getComponents()) walk(child, each);
    }

    interface Check {
        void run() throws Exception;
    }

    static void onEdt(Check check) throws Exception {
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                check.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        if (failure.get() instanceof Error e) throw e;
        if (failure.get() instanceof Exception e) throw e;
    }

    /// The page's links go nowhere in a test.
    record NoActions(Images images) implements Actions {
        public void go(Place place) { }
        public void editPerson(String key) { }
        public void editFamily(String key) { }
        public void editRecord(String key) { }
        public void addRelative(String key, String relation) { }
        public void linkExisting(String key) { }
        public void unlink(Model.Person person, Model.Relative relative) { }
        public void showPhotos(List<Model.Photo> photos, int index, String title) { }
        public void openExternally(String url, String name) { }
        public void resolve(String key) { }
        public void personMenu(Component c, int x, int y, Model.PersonSummary person, Model.Person context, Model.Relative relative) { }
        public void openFile(String path) { }
        public void chooseFile() { }
        public void forget(String id) { }
    }
}
