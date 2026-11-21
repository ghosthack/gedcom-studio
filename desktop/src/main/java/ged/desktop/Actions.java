package ged.desktop;

import java.awt.Component;
import java.util.List;

/// What a page can ask the main window to do: go somewhere, open an editor
/// or a dialog. Pages hold no state of their own beyond what they show.
interface Actions {

    void go(Place place);

    default void openPerson(String key) {
        go(new Place.PersonPage(key));
    }

    void editPerson(String key);

    void editFamily(String key);

    /// The edit form of a note, source or media record.
    void editRecord(String key);

    /// The add-relative dialog for `key`, with `relation` chosen (or null).
    void addRelative(String key, String relation);

    void linkExisting(String key);

    void unlink(Model.Person person, Model.Relative relative);

    void showPhotos(List<Model.Photo> photos, int index, String title);

    /// Opens a media record's file in the system's own viewer.
    void openExternally(String url, String name);

    /// A key named in text (a warning): the person, family or record it is.
    void resolve(String key);

    /// The right-click menu for a person shown on a page.
    void personMenu(Component c, int x, int y, Model.PersonSummary person, Model.Person context, Model.Relative relative);

    /// Opens (or switches to) a GEDCOM file by path.
    void openFile(String path);

    /// The File ▸ Open… chooser.
    void chooseFile();

    /// Drops a file from the recent list.
    void forget(String id);

    Images images();
}
