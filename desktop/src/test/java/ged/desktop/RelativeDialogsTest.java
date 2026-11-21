package ged.desktop;

import ged.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RelativeDialogsTest {

    @Test
    void namesPutTheSurnameBetweenSlashes() {
        assertEquals("Maria Luisa /Rossi/", RelativeDialogs.gedcomName(" Maria Luisa ", "Rossi"));
        assertEquals("/Rossi/", RelativeDialogs.gedcomName("", "Rossi"));
        assertEquals("Maria //", RelativeDialogs.gedcomName("Maria", " "));
    }

    @Test
    void likelySex() {
        assertEquals("M", RelativeDialogs.likelySex("father", "F"));
        assertEquals("F", RelativeDialogs.likelySex("mother", "M"));
        assertEquals("M", RelativeDialogs.likelySex("spouse", "F"));
        assertEquals("U", RelativeDialogs.likelySex("spouse", "U"));
        assertEquals("U", RelativeDialogs.likelySex("child", "F"));
    }

    @Test
    void aMothersChildTakesThePartnersSurname() {
        var p = Model.Person.from(Json.parseObject(ModelTest.PERSON));
        assertEquals("Bianchi", RelativeDialogs.likelySurname("child", p, "F2"));
        assertEquals("Rossi", RelativeDialogs.likelySurname("sibling", p, null));
        assertEquals("", RelativeDialogs.likelySurname("spouse", p, null));
    }

    @Test
    void theUnlinkQuestionSaysNobodyIsDeleted() {
        var p = Model.Person.from(Json.parseObject(ModelTest.PERSON));
        var father = p.relatives().getFirst();
        var q = RelativeDialogs.unlinkQuestion(p.summary(), father);
        assertTrue(q.contains("Nobody is deleted"), q);
        assertTrue(q.contains("Giuseppe Rossi will no longer be linked as Maria Rossi’s father"), q);
    }
}
