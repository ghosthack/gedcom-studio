package ged.store;

import ged.Fixtures;
import ged.edit.PersonForm;
import ged.model.Genealogy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {
    private static void rename(Genealogy g, String id, String given) {
        var p = g.person(id).orElseThrow();
        PersonForm.apply(g, p, PersonForm.diff(p, Map.of(PersonForm.Field.GIVEN, given)));
    }

    @Test
    void everyChangeMovesTheRevision() {
        var store = new Store(Fixtures.cousins());
        long before = store.revision();
        store.write(g -> { rename(g, "I9", "Elio"); return null; });
        assertTrue(store.revision() > before);
        long after = store.revision();
        store.read(g -> g.people().size());
        store.write(g -> g.people().size());
        assertEquals(after, store.revision(), "reading changes nothing");
    }

    @Test
    void waitersWakeOnAChange() throws Exception {
        var store = new Store(Fixtures.cousins());
        long rev = store.revision();
        var waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return store.awaitChange(rev, Duration.ofSeconds(20));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(100);
        store.write(g -> { rename(g, "I9", "Elio"); return null; });
        assertTrue(waiting.get(5, TimeUnit.SECONDS) > rev);
        assertEquals(rev, new Store(Fixtures.cousins()).awaitChange(rev, Duration.ofMillis(50)), "times out unchanged");
    }

    @Test
    void outsideLetsOthersWriteMeanwhile() throws Exception {
        var store = new Store(Fixtures.cousins());
        var editing = new CountDownLatch(1);
        var other = new CountDownLatch(1);
        var shell = CompletableFuture.runAsync(() -> store.write(g -> {
            try {
                store.outside(() -> {
                    editing.countDown();
                    return other.await(5, TimeUnit.SECONDS);
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return g.person("I9").orElseThrow().name().given();
        }));
        assertTrue(editing.await(5, TimeUnit.SECONDS));
        // the web app gets in while the shell's form is out
        store.write(g -> { rename(g, "I9", "Elio"); return null; });
        other.countDown();
        shell.get(5, TimeUnit.SECONDS);
        assertEquals("Elio", store.read(g -> g.person("I9").orElseThrow().name().given()));
    }
}
