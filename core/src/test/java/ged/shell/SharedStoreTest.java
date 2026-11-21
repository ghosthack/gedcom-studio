package ged.shell;

import ged.Fixtures;
import ged.edit.Editor;
import ged.edit.PersonForm;
import ged.model.Genealogy;
import ged.store.Store;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Shells sharing a [Store] with other front ends: what the web or desktop
/// app changes meanwhile is seen, and never silently undone.
class SharedStoreTest {
    private final Genealogy g = Fixtures.cousins();
    private final Store store = new Store(g);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Deque<UnaryOperator<String>> steps = new ArrayDeque<>();
    private final List<String> forms = new ArrayList<>();

    private Shell shell(String home) {
        Editor editor = file -> {
            var text = Files.readString(file);
            forms.add(text);
            var edited = steps.removeFirst().apply(text);
            if (edited == null) return false;
            Files.writeString(file, edited);
            return true;
        };
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        return new Shell(new Navigator(new FileSystem(g), g.person(home)), ps, ps, Style.PLAIN, editor, store);
    }

    private String run(Shell shell, String line) {
        out.reset();
        shell.execute(line);
        return out.toString(StandardCharsets.UTF_8);
    }

    /// What the web app does: an edit through the store.
    private void elsewhere(String id, PersonForm.Field field, String value) {
        store.write(x -> {
            var p = x.person(id).orElseThrow();
            return PersonForm.apply(x, p, PersonForm.diff(p, Map.of(field, value)));
        });
    }

    private static UnaryOperator<String> set(String field, String value) {
        return form -> form.replaceAll("(?m)^" + field.replace(".", "\\.") + ":.*$", field + ": " + value);
    }

    @Test
    void aFieldChangedElsewhereWhileTheFormIsOutReopensIt() {
        var shell = shell("I3");
        steps.add(form -> {
            elsewhere("I3", PersonForm.Field.OCCUPATION, "Captain"); // the web app, meanwhile
            return set("occupation", "Admiral").apply(form);
        });
        steps.add(form -> form); // saved again as reopened: the person has now seen the change
        var r = run(shell, "edit");
        assertTrue(forms.get(1).contains("changed elsewhere meanwhile: occupation is now 'Captain' (was 'Sailor')"), forms.get(1));
        assertTrue(forms.get(1).contains("occupation:      Admiral") || forms.get(1).contains("occupation: Admiral"), "their typing is kept");
        assertTrue(r.contains("occupation   Captain → Admiral"), r);
        assertEquals("Admiral", PersonForm.values(g.person("I3").orElseThrow()).get(PersonForm.Field.OCCUPATION));
    }

    @Test
    void otherFieldsChangedElsewhereAreKept() {
        var shell = shell("I3");
        steps.add(form -> {
            elsewhere("I3", PersonForm.Field.BIRTH_PLACE, "Livorno");
            return set("occupation", "Admiral").apply(form);
        });
        var r = run(shell, "edit");
        assertTrue(r.contains("1 change"), r);
        var now = PersonForm.values(g.person("I3").orElseThrow());
        assertEquals("Admiral", now.get(PersonForm.Field.OCCUPATION));
        assertEquals("Livorno", now.get(PersonForm.Field.BIRTH_PLACE), "the form's old birth place did not undo it");
    }

    @Test
    void aShellSeesEditsMadeElsewhere() {
        var a = shell("I9");
        var b = shell("I9");
        run(a, "edit given=Elio");
        assertTrue(b.prompt().startsWith("Elio Rossi"), b.prompt());
        assertTrue(run(b, "show").contains("Elio Rossi"));
        elsewhere("I9", PersonForm.Field.GIVEN, "Eleonora");
        assertTrue(run(a, "pwd; show").contains("Eleonora Rossi"));
    }

    @Test
    void aPersonDeletedElsewhereEndsTheEdit() {
        var shell = shell("I9");
        run(shell, "add child \"Nina Rossi\"");
        var nina = g.people().getLast();
        steps.add(form -> {
            store.write(x -> { x.removePerson(x.person(nina.id()).orElseThrow()); return null; });
            return set("given", "Nora").apply(form);
        });
        var r = run(shell, "edit " + nina.key());
        assertTrue(r.contains("was deleted meanwhile; nothing was changed"), r);
    }

    @Test
    void attachedShellsLeaveWithoutWarning() {
        var shell = shell("I9");
        shell.attached(true);
        run(shell, "edit given=Elio");
        var r = run(shell, "exit");
        assertTrue(shell.exited());
        assertEquals("", r);
    }
}
