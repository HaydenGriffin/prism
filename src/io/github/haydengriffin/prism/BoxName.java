package io.github.haydengriffin.prism;

/**
 * The box's own name in the app's words: the name set in Android's settings ("SHIELD"), else its model, else
 * "this box". No Android types: runs on the host JVM.
 */
final class BoxName {
    /** Null when neither a device name nor a model is known. */
    private final String name;

    private BoxName(String name) {
        this.name = name;
    }

    static BoxName of(String deviceName, String model) {
        String name = blank(deviceName) ? model : deviceName;
        return new BoxName(blank(name) ? null : name.trim());
    }

    /** On its own, at the start of a line: "SHIELD", "This box". */
    String label() {
        return name == null ? "This box" : name;
    }

    /** Inside a sentence: "the SHIELD", "this box". */
    String the() {
        return name == null ? "this box" : "the " + name;
    }

    /** The TV input the box is on, as a heading: "SHIELD input", "Box input". */
    String input() {
        return name == null ? "Box input" : name + " input";
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
