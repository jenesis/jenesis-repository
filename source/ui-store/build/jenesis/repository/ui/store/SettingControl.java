package build.jenesis.repository.ui.store;

import build.jenesis.repository.settings.Setting;

import module java.base;

/**
 * The control that edits a setting's value, decided once from the setting's kind and its declared form, and what
 * drawing it takes: the element, the input type, the room it needs and the behaviour the console's script adds to it.
 * Every page that edits a setting draws it from this, so a setting reads and edits the same wherever it is asked.
 */
public enum SettingControl {

    /** A true/false value: a switch. */
    SWITCH,
    /** One of the setting's catalogued choices: a drop-down. */
    SELECT,
    /** One line of text. */
    LINE,
    /** A whole number, with the browser's numeric check. */
    NUMBER,
    /** A URI, with the browser's URL check. */
    URL,
    /** A secret, masked as it is typed and never prefilled. */
    SECRET,
    /** A duration, edited as an amount and a unit. */
    DURATION,
    /** A duration that may be switched off, offered as "never". */
    DURATION_OR_NONE,
    /** Free text over several lines. */
    TEXT,
    /** A list, one entry per line. */
    LINES,
    /** A JSON document, in a fixed-width face. */
    JSON,
    /** Several values on one line, edited as one removable entry each from the values the setting knows. */
    VALUES,
    /** A repository's routing, edited as its clauses. */
    ROUTING;

    /** The control for a setting of {@code kind} declared with {@code form}: the kind decides a switch and a
     *  drop-down, the form how text is edited, and the kind again what one line checks. */
    public static SettingControl of(Setting.Kind kind, Setting.Form form) {
        return switch (kind) {
            case BOOLEAN -> SWITCH;
            case CHOICE -> SELECT;
            default -> switch (form == null ? Setting.Form.LINE : form) {
                case TEXT -> TEXT;
                case LINES -> LINES;
                case JSON -> JSON;
                case VALUES -> VALUES;
                case ROUTING -> ROUTING;
                default -> switch (kind) {
                    case INTEGER, LONG -> NUMBER;
                    case URI -> URL;
                    case SECRET -> SECRET;
                    case DURATION -> DURATION;
                    case DURATION_OR_NONE -> DURATION_OR_NONE;
                    default -> LINE;
                };
            };
        };
    }

    /** Whether the value is one of the setting's catalogued choices, each of which may describe what it does. */
    public boolean choice() {
        return this == SELECT;
    }

    /** Whether the value is true or false, which a page draws as a switch. */
    public boolean isSwitch() {
        return this == SWITCH;
    }

    /** Whether the value is a repository's routing, which the script edits as its clauses. */
    public boolean editsRouting() {
        return this == ROUTING;
    }

    /** Whether the value is several values on one line, which the script edits as removable entries. */
    public boolean editsValues() {
        return this == VALUES;
    }

    /** Whether the value is picked from a list: a drop-down, and a switch where a page draws one as its choice. */
    public boolean select() {
        return this == SELECT || this == SWITCH;
    }

    /** Whether the value is edited over several lines. */
    public boolean textArea() {
        return this == TEXT || this == LINES || this == JSON;
    }

    /** Whether the value is edited in one input. */
    public boolean input() {
        return !select() && !textArea();
    }

    /** The input type that gives one line the browser's own check for its kind. */
    public String inputType() {
        return switch (this) {
            case NUMBER -> "number";
            case URL -> "url";
            case SECRET -> "password";
            default -> "text";
        };
    }

    /** How many lines a text area offers: more for a document than for a list. */
    public int rows() {
        return this == JSON ? 8 : 4;
    }

    /** Whether the value is shown in a fixed-width face. */
    public boolean code() {
        return this == JSON;
    }

    /** Whether the control takes the row's whole width rather than sharing a line with its Save. */
    public boolean wide() {
        return textArea() || this == ROUTING;
    }

    /** Whether the value is a duration, which the script edits as an amount and a unit. */
    public boolean duration() {
        return this == DURATION || this == DURATION_OR_NONE;
    }

    /** What the script's duration editor is told: whether "never" is offered, or nothing for any other control. */
    public String durationMode() {
        return switch (this) {
            case DURATION -> "duration";
            case DURATION_OR_NONE -> "or-none";
            default -> null;
        };
    }
}
