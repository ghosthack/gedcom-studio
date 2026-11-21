package ged.desktop;

import ged.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {

    static final String PERSON = """
            {"key":"I1","name":"Maria Rossi","given":"Maria","surname":"Rossi","sex":"F","born":"1850","died":"ABT 1920",
             "deceased":true,"lifespan":"1850–ABT 1920","photo":"/api/v1/files/a/people/I1/photos/0?sig=x","suffix":"",
             "otherNames":[{"name":"Mary Ross","type":"aka"}],"nicknames":["Mimi"],
             "events":[{"tag":"BIRT","label":"Born","type":"","date":"12 JUN 1850","year":1850,"place":"Genova","value":""},
                       {"tag":"OCCU","label":"Occupation","type":"","date":"","year":null,"place":"","value":"Sailor"}],
             "parents":[{"family":"F1","father":{"key":"I3","name":"Giuseppe Rossi"},"mother":null,
                         "siblings":[{"key":"I5","name":"Paolo Rossi"}]}],
             "halfSiblings":[],
             "families":[{"family":"F2","role":"wife","partner":{"key":"I2","name":"Marco Bianchi","surname":"Bianchi","sex":"M"},
                          "married":{"tag":"MARR","label":"Married","date":"1875","year":1875},"divorced":false,
                          "children":[{"key":"I6","name":"Luca Bianchi"}]}],
             "notes":["n"],"photos":[{"url":"/u?sig=1","file":"p.jpg","title":"","date":"","crop":{"left":1,"top":2,"width":3,"height":4}},
                                     {"url":null,"file":"q.jpg","title":"Q","date":"","crop":null}],
             "records":[{"key":"S1","kind":"sources","title":"Register"}],
             "fields":{"given":"Maria","birth.date":"12 JUN 1850"},
             "somethingNew":42}
            """;

    @Test
    void readsAPerson() {
        var p = Model.Person.from(Json.parseObject(PERSON));
        assertEquals("I1", p.key());
        assertEquals("Mimi", p.nicknames().getFirst());
        assertNull(p.events().get(1).year());
        assertEquals("Sailor", p.events().get(1).value());
        assertNull(p.parents().getFirst().mother());
        assertEquals(new Model.Crop(1, 2, 3, 4), p.photos().getFirst().crop());
        assertNull(p.photos().get(1).url());
        assertNull(p.photos().get(1).crop());
        assertEquals("12 JUN 1850", p.fields().get("birth.date"));
        assertEquals("1875", p.families().getFirst().married().date());
    }

    @Test
    void listsEveryoneWhoCanBeUnlinkedWithTheirPath() {
        var p = Model.Person.from(Json.parseObject(PERSON));
        assertEquals(List.of("father", "siblings/I5", "spouses/I2", "children/I6"),
                p.relatives().stream().map(Model.Relative::unlinkPath).toList());
    }

    @Test
    void readsPedigreeAndDescendants() {
        var ped = Model.Pedigree.from(Json.parseObject("""
                {"key":"I1","name":"A","father":{"key":"I2","name":"B","father":null,"mother":null},"mother":null}"""));
        assertEquals("I2", ped.father().person().key());
        assertNull(ped.mother());
        var d = Model.Descendants.from(Json.parseObject("""
                {"key":"I1","families":[{"family":"F1","partner":null,"children":[{"key":"I3","families":[]}]}]}"""));
        assertEquals("I3", d.families().getFirst().children().getFirst().person().key());
    }

    @Test
    void readsAFile() {
        var f = Model.FileInfo.from(Json.parseObject("""
                {"id":"a9","name":"f.ged","path":"/f.ged","gedcom":"5.5.1","encoding":"UTF-8","people":41,"families":13,
                 "records":{"notes":0,"sources":2,"media":5},"dirty":true,"dirtyCount":2,"changedOnDisk":false,
                 "revision":7,"home":"I1","warnings":["w"]}"""));
        assertEquals(2, f.sources());
        assertEquals(2, f.dirtyCount());
        assertEquals(List.of("w"), f.warnings());
    }

    @Test
    void summaryCropWhenTheDaemonSendsIt() {
        var with = Model.PersonSummary.from(Json.parseObject(
                "{\"key\":\"I1\",\"photo\":\"/p\",\"photoCrop\":{\"left\":10,\"top\":0,\"width\":200,\"height\":260}}"));
        assertTrue(with.cropKnown());
        assertEquals(new Model.Crop(10, 0, 200, 260), with.photoCrop());
        var none = Model.PersonSummary.from(Json.parseObject("{\"key\":\"I1\",\"photo\":\"/p\",\"photoCrop\":null}"));
        assertTrue(none.cropKnown());
        assertNull(none.photoCrop());
        var older = Model.PersonSummary.from(Json.parseObject("{\"key\":\"I1\",\"photo\":\"/p\"}"));
        assertFalse(older.cropKnown());
    }

    @Test
    void unnamedPeopleStillHaveALabel() {
        var s = Model.PersonSummary.from(Json.parseObject("{\"key\":\"I9\",\"name\":\"\"}"));
        assertEquals("Unnamed (I9)", s.displayName());
    }
}
