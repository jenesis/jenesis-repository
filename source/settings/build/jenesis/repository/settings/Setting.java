package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.store.Durations;

/**
 * Describes one runtime-editable setting: its key in the shared {@code config/settings} store, the form group it
 * renders under, a human-readable label and description, the value kind that validates an entered value before it
 * is stored, the product default a cleared override reverts to, whether a change applies live (on the nodes' next
 * scheduled re-read) or only on the next restart, and its {@link Scope} - whether it is a deployment-wide
 * {@link Scope#GLOBAL global} knob or one a tenant may {@link Scope#TENANT override} for its own artifact space. The
 * neutral core catalogues its own settings; a plugin module describes its settings through a
 * {@link SettingsContributor}, so installing or removing the module adds or removes its settings everywhere they
 * surface (console, API, CLI) with no change to neutral code.
 *
 * <p>{@code enablement} marks the one setting a module contributes that gates whether the module's provider does
 * anything - its enable/disable flag (the retention sweep's {@code scheduled-cleanup}, the audit trail's {@code audit},
 * the rate limiter's ceiling). The modules console reads it to pair a module with its toggle without a maintained
 * table; {@code live} on that setting says whether flipping it applies on the next scheduled re-read or only on the
 * next restart. A contributor sets it with {@link #gate()}.
 */
public record Setting(String key, String group, String label, String description,
                      Kind kind, List<String> choices, String defaultValue, boolean live, Scope scope,
                      boolean enablement) {

    public Setting {
        choices = List.copyOf(choices);
        scope = scope == null ? Scope.GLOBAL : scope;
    }

    /** A setting at an explicit {@link Scope} that is not its module's enablement gate (the common case). */
    public Setting(String key, String group, String label, String description,
                   Kind kind, List<String> choices, String defaultValue, boolean live, Scope scope) {
        this(key, group, label, description, kind, choices, defaultValue, live, scope, false);
    }

    /** A choice-carrying setting, deployment-wide by default. */
    public Setting(String key, String group, String label, String description,
                   Kind kind, List<String> choices, String defaultValue, boolean live) {
        this(key, group, label, description, kind, choices, defaultValue, live, Scope.GLOBAL);
    }

    /** This setting, marked as its contributing module's enablement gate - the flag the modules console pairs with the
     *  module's enable/disable toggle. A copy, so a contributor writes {@code new Setting(...).gate()} without a wider
     *  constructor. */
    public Setting gate() {
        return new Setting(key, group, label, description, kind, choices, defaultValue, live, scope, true);
    }

    /** A setting whose kind carries no choice list (every kind but {@link Kind#CHOICE}), deployment-wide by default. */
    public Setting(String key, String group, String label, String description,
                   Kind kind, String defaultValue, boolean live) {
        this(key, group, label, description, kind, List.of(), defaultValue, live, Scope.GLOBAL);
    }

    /** A setting whose kind carries no choice list, at an explicit {@link Scope} - a plugin marks a gate policy or
     *  forward target {@link Scope#TENANT} so a tenant may override it. */
    public Setting(String key, String group, String label, String description,
                   Kind kind, String defaultValue, boolean live, Scope scope) {
        this(key, group, label, description, kind, List.of(), defaultValue, live, scope);
    }

    /** Whether a tenant may override this setting for its own artifact space. A {@link Scope#GLOBAL} setting is
     *  refused in a tenant document and resolves deployment-wide; a {@link Scope#TENANT} one layers a tenant value
     *  over the global default in the effective chain. */
    public boolean tenantOverridable() {
        return scope == Scope.TENANT;
    }

    /** Whether a setting is deployment-wide ({@link #GLOBAL}, the default) or a tenant may override it for its own
     *  artifact space ({@link #TENANT}) - a gate policy, deny list or forward target differs per tenant while a
     *  deployment-wide knob (the listen port, the default tenant, the maintenance lease) stays global. */
    public enum Scope {
        GLOBAL, TENANT
    }

    /** Whether a value parses for this setting's kind - so a setting, including a restart-only one no live rebuild
     *  checks, cannot be stored with a value that would later fail to bind at boot. */
    public boolean parses(String value) {
        return kind.parses(value, choices);
    }

    /** The value kinds a setting can carry; each knows how to validate an entered value. {@link #SECRET} and
     *  {@link #PATH} are free-form like {@link #STRING} but tell a form to mask the input or expect a file path. */
    public enum Kind {
        BOOLEAN, STRING, SECRET, PATH, URI, INTEGER, LONG, DURATION, CHOICE;

        public boolean parses(String value, List<String> choices) {
            try {
                switch (this) {
                    // A boolean dial honours exactly two words: everything the readers use - Boolean.parseBoolean in
                    // ModuleCapability's gate, "false".equalsIgnoreCase in the Features toggle - collapses every other
                    // spelling onto one of them. Accepting `yes`, `1` or `banana` would therefore store a value the
                    // operator chose and the code silently reads as the opposite, which is the (c)/shape
                    // read off the BOOLEAN kind: a dial must accept exactly the values its code honours distinctly.
                    case BOOLEAN -> {
                        String word = value.trim();
                        if (!word.equalsIgnoreCase("true") && !word.equalsIgnoreCase("false")) {
                            return false;
                        }
                    }
                    case INTEGER -> Integer.parseInt(value.trim());
                    case LONG -> Long.parseLong(value.trim());
                    // The whole grammar, not only ISO-8601: an operator who reads a cadence dial's own
                    // rejection message and types 6h into the settings screen must not be refused for it.
                    case DURATION -> Durations.parse(value.trim());
                    case URI -> java.net.URI.create(value);
                    case CHOICE -> {
                        if (!choices.contains(value.trim())) {
                            return false;
                        }
                    }
                    default -> { }
                }
                return true;
            } catch (RuntimeException _) {
                return false;
            }
        }
    }
}
