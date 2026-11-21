package ged.desktop;

/// Where the main area is: one page of the open file. Navigation history is a
/// list of these, so each is a small value.
sealed interface Place {

    /// The person a place is about, if any: the toolbar's edit and add-relative
    /// buttons and the person/ancestors/descendants switch act on them.
    default String personKey() {
        return null;
    }

    String title();

    /// No file chosen: the open and recent files.
    record Welcome() implements Place {
        public String title() { return "Welcome"; }
    }

    record PersonPage(String key) implements Place {
        public String personKey() { return key; }
        public String title() { return "Person " + key; }
    }

    record Ancestors(String key, int depth) implements Place {
        public String personKey() { return key; }
        public String title() { return "Ancestors of " + key; }
    }

    record Descendants(String key, int depth) implements Place {
        public String personKey() { return key; }
        public String title() { return "Descendants of " + key; }
    }

    record FamilyPage(String key) implements Place {
        public String title() { return "Family " + key; }
    }

    /// The families, notes, sources or media of the file.
    record RecordList(String kind) implements Place {
        public String title() { return Kinds.plural(kind); }
    }

    record RecordPage(String key) implements Place {
        public String title() { return "Record " + key; }
    }

    record Warnings() implements Place {
        public String title() { return "Warnings"; }
    }

    /// Display names for the record kinds the API uses.
    final class Kinds {
        static String plural(String kind) {
            return switch (kind) {
                case "families" -> "Families";
                case "notes" -> "Notes";
                case "sources" -> "Sources";
                case "media" -> "Media";
                default -> kind;
            };
        }

        static String singular(String kind) {
            return switch (kind) {
                case "families", "family" -> "Family";
                case "notes" -> "Note";
                case "sources" -> "Source";
                case "media" -> "Media";
                case "person" -> "Person";
                default -> kind;
            };
        }

        private Kinds() {
        }
    }
}
