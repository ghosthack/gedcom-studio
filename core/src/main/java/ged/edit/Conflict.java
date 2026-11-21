package ged.edit;

/// An edit made against values that someone else has changed since they
/// were shown (in another window, the web app, the desktop app). Nothing of
/// the edit is applied; the message says what changed.
public final class Conflict extends IllegalArgumentException {
    public Conflict(String message) { super(message); }
}
