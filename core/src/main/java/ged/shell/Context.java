package ged.shell;

import ged.edit.Editor;
import ged.model.Genealogy;
import ged.store.Store;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;

import java.io.PrintStream;

/// What a command can see: the navigator (and through it the data), the
/// output stream, the active styling, and how to launch a text editor.
///
/// A command runs holding the [Store]'s write lock, so it may read and change
/// [#g()] freely. Other front ends (the web and desktop apps) wait meanwhile;
/// a command that waits on a person should do so in [Store#outside].
public record Context(Navigator nav, PrintStream out, Style style, Editor editor, Store store) {
    public FileSystem fs() { return nav.fs(); }
    public Genealogy g() { return nav.fs().genealogy(); }
}
