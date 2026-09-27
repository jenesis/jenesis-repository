package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.store.Durations;

/**
 * Describes one runtime-editable setting: its key in the shared {@code config/settings} store, the form group it
 * renders under, a human-readable label and description, the value kind that validates an entered value before it
 * is stored, the product default a cleared override reverts to, whether a change applies live (on the nodes' next
 * scheduled re-read) or only on the next restart, and its {@link Scope} - the narrowest level a value may be set at.
 * A {@link Scope#GLOBAL global} knob is the deployment's alone; a {@link Scope#TENANT tenant} one may also be set by
 * a tenant for its own artifact space; a {@link Scope#REPOSITORY repository} or {@link Scope#PROJECT project} one
 * may also be set for one repository or one build-cache project, stored in that repository's or project's own
 * settings document. Every wider level holds the default the narrower one inherits, so a value resolves repository
 * (or project) over tenant over deployment over the declared default - unless the setting is
 * {@link #localOnly() local}, which has no wider default at all. The neutral core catalogues its own settings; a
 * plugin module describes its settings through a {@link SettingsContributor}, so installing or removing the module
 * adds or removes its settings everywhere they surface (console, API, CLI, the creation wizards) with no change to
 * neutral code.
 *
 * <p>{@code enablement} marks the one setting a module contributes that gates whether the module's provider does
 * anything - its enable/disable flag (the retention sweep's {@code scheduled-cleanup}, the audit trail's {@code audit},
 * the rate limiter's ceiling). The modules console reads it to pair a module with its toggle without a maintained
 * table; {@code live} on that setting says whether flipping it applies on the next scheduled re-read or only on the
 * next restart. A contributor sets it with {@link #gate()}.
 *
 * <p>{@code tier} says whether the setting is asked when the thing it configures is created, shown, or folded away -
 * see {@link Tier}. Every constructor leaves it undecided ({@code null}) and a contributor declares it with
 * {@link #essential()}, {@link #standard()} or {@link #advanced()} on each setting it contributes; the catalogue
 * census refuses a setting that arrives undecided, because a default here would decide for every contributor that
 * forgot.
 *
 * <p>{@code localOnly} marks a {@link Scope#REPOSITORY repository} or {@link Scope#PROJECT project} setting that
 * has no wider default: its value is the repository's or project's own or nothing, and the tenant and deployment
 * documents refuse it. A repository's routing is one - a deployment-wide default routing would point every
 * repository, of every format, at one upstream. {@code operatorOnly} marks a setting only the deployment's operator
 * may set, at whatever level: the same routing names the upstreams a repository fetches from, which is the
 * operator's decision on every surface.
 */
public record Setting(String key, String group, String label, String description,
                      Kind kind, List<String> choices, String defaultValue, boolean live, Scope scope,
                      boolean enablement, Tier tier, boolean localOnly, boolean operatorOnly) {

    /** The word a {@link Kind#DURATION_OR_NONE} setting takes to switch its rule off, rather than inherit a wider
     *  level's value - which is what leaving it unset means. */
    public static final String NONE = Durations.NONE;

    public Setting {
        choices = List.copyOf(choices);
        scope = scope == null ? Scope.GLOBAL : scope;
        if (localOnly && scope != Scope.REPOSITORY && scope != Scope.PROJECT) {
            throw new IllegalArgumentException("Setting '" + key + "' is " + scope + "-scoped, so it cannot be local: "
                    + "only a repository or project setting has a wider default to go without");
        }
    }

    /** A setting at an explicit {@link Scope} that is not its module's enablement gate (the common case). */
    public Setting(String key, String group, String label, String description,
                   Kind kind, List<String> choices, String defaultValue, boolean live, Scope scope) {
        this(key, group, label, description, kind, choices, defaultValue, live, scope, false, null, false, false);
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
        return new Setting(key, group, label, description, kind, choices, defaultValue, live, scope, true, tier,
                localOnly, operatorOnly);
    }

    /** This setting, declared one the wizard of its scope asks - see {@link Tier#ESSENTIAL}. */
    public Setting essential() {
        return tiered(Tier.ESSENTIAL);
    }

    /** This setting, declared one the settings screens show but no wizard asks - see {@link Tier#STANDARD}. */
    public Setting standard() {
        return tiered(Tier.STANDARD);
    }

    /** This setting, declared one that tunes what was already decided - see {@link Tier#ADVANCED}. */
    public Setting advanced() {
        return tiered(Tier.ADVANCED);
    }

    /** This repository or project setting, declared one with no wider default - see {@link #localOnly}. */
    public Setting local() {
        return new Setting(key, group, label, description, kind, choices, defaultValue, live, scope, enablement, tier,
                true, operatorOnly);
    }

    /** This setting, declared one only the deployment's operator may set - see {@link #operatorOnly}. */
    public Setting operator() {
        return new Setting(key, group, label, description, kind, choices, defaultValue, live, scope, enablement, tier,
                localOnly, true);
    }

    private Setting tiered(Tier decided) {
        return new Setting(key, group, label, description, kind, choices, defaultValue, live, scope, enablement,
                decided, localOnly, operatorOnly);
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

    /** Whether a tenant may override this setting for its own artifact space - {@link #settableAt settable at}
     *  {@link Scope#TENANT}. */
    public boolean tenantOverridable() {
        return settableAt(Scope.TENANT);
    }

    /**
     * Whether a value of this setting may be stored at {@code level}: at its own scope, and at every wider level as
     * the default the narrower one inherits - except for a {@link #localOnly() local} setting, which only its own
     * scope holds. A repository document holds only repository settings and a project document only project
     * settings, since neither is a default for anything.
     */
    public boolean settableAt(Scope level) {
        return settable(scope, localOnly, level);
    }

    /** {@link #settableAt} for a setting of {@code scope} that is {@code local} or not - the one rule, so the
     *  classifier of a key the catalogue does not declare applies it too ({@link SettingsScopes#settableAt}). */
    public static boolean settable(Scope scope, boolean local, Scope level) {
        return switch (level) {
            case GLOBAL -> !local;
            case TENANT -> scope == Scope.TENANT || (!local && (scope == Scope.REPOSITORY || scope == Scope.PROJECT));
            case REPOSITORY -> scope == Scope.REPOSITORY;
            case PROJECT -> scope == Scope.PROJECT;
        };
    }

    /**
     * Whether a setting is asked when what it configures is created, shown on the settings screens, or folded away
     * there. The wizard of a setting's scope asks its {@link #ESSENTIAL} settings - the deployment's first-boot wizard
     * the global and tenant ones, the repository wizard the repository ones, the project wizard the project ones - so
     * a module that declares an essential setting adds it to that wizard with nothing else to write. The settings
     * screens show the essential and standard settings and fold the advanced ones behind a disclosure their filter
     * opens on a match; the generated reference names each tier. Nothing else differs: every tier is as editable, as
     * validated and as live as the others.
     */
    public enum Tier {

        /** Asked on creation: a decision that is easy to forget and costly to discover later - where a repository
         *  fetches from, how long it keeps what it holds, which advisory sources the gate asks and what it does with
         *  what they say, a tenant's ceilings, how a person signs in. */
        ESSENTIAL,

        /** Shown, not asked: a decision an operator makes when the need arises - a policy knob, an integration, a
         *  feature switched on or off, where something is sent and the credential that goes with it. */
        STANDARD,

        /** Tuning: a cadence, a cap, a ttl, a retry count, a timeout, a repair riding a walk, or an endpoint that
         *  already points at the one public service. The default is what nearly every deployment wants. */
        ADVANCED
    }

    /** The narrowest level a setting's value may be set at: the deployment's alone ({@link #GLOBAL}, the default), a
     *  tenant's for its own artifact space ({@link #TENANT}) - a gate policy, deny list or forward target differs per
     *  tenant while a deployment-wide knob (the listen port, the default tenant, the maintenance lease) stays global -
     *  or one repository's ({@link #REPOSITORY}) or one build-cache project's ({@link #PROJECT}), such as how long a
     *  repository keeps what it holds or how large a project may grow. */
    public enum Scope {
        GLOBAL, TENANT, REPOSITORY, PROJECT
    }

    /** Whether a value parses for this setting's kind - so a setting, including a restart-only one no live rebuild
     *  checks, cannot be stored with a value that would later fail to bind at boot. */
    public boolean parses(String value) {
        return kind.parses(value, choices);
    }

    /** The value kinds a setting can carry; each knows how to validate an entered value. {@link #SECRET} and
     *  {@link #PATH} are free-form like {@link #STRING} but tell a form to mask the input or expect a file path.
     *  {@link #DURATION_OR_NONE} is a duration, or {@link #NONE} to switch the rule off at a level whose wider level
     *  sets one - where leaving it unset would inherit it instead. */
    public enum Kind {
        BOOLEAN, STRING, SECRET, PATH, URI, INTEGER, LONG, DURATION, DURATION_OR_NONE, CHOICE;

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
                    case DURATION_OR_NONE -> Durations.parseOrNone(value);
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
