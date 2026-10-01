package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.store.Durations;

/**
 * One runtime-editable setting: its key in the shared {@code config/settings} store, the form group, label and
 * description, the value kind that validates an entered value, the default a cleared override reverts to, whether a
 * change applies live (on the nodes' next re-read) or on restart, and its {@link Scope}, the narrowest level a value
 * may be set at. A value resolves repository (or project) over tenant over deployment over the declared default,
 * unless the setting is {@link #localOnly() local}, which has no wider default. A module describes its settings
 * through a {@link SettingsContributor}, so they surface wherever settings do (console, API, CLI, wizards).
 *
 * <p>{@code enablement} marks the one setting of a module that gates whether its provider does anything; the modules
 * console pairs it with the module's toggle. A contributor sets it with {@link #gate()}.
 *
 * <p>{@code tier} ({@link Tier}) is {@code null} from every constructor and declared with {@link #essential()},
 * {@link #standard()} or {@link #advanced()}; the catalogue census refuses an undecided setting, since a default would
 * decide for every contributor that forgot.
 *
 * <p>{@code localOnly} marks a repository or project setting with no wider default, such as a repository's routing:
 * a deployment-wide default routing would point every repository at one upstream. {@code operatorOnly} marks a setting
 * only the deployment's operator may set, at any level.
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

    /** A copy of this setting marked as its module's enablement gate. */
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

    /** A setting whose kind carries no choice list, at an explicit {@link Scope}. */
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
     * Whether a value of this setting may be stored at {@code level}: its own scope, and every wider level as the
     * inherited default, except for a {@link #localOnly() local} setting. A repository or project document holds only
     * settings of its own scope.
     */
    public boolean settableAt(Scope level) {
        return settable(scope, localOnly, level);
    }

    /** {@link #settableAt} for a setting of {@code scope}, also applied to undeclared keys by
     *  {@link SettingsScopes#settableAt}. */
    public static boolean settable(Scope scope, boolean local, Scope level) {
        return switch (level) {
            case GLOBAL -> !local;
            case TENANT -> scope == Scope.TENANT || (!local && (scope == Scope.REPOSITORY || scope == Scope.PROJECT));
            case REPOSITORY -> scope == Scope.REPOSITORY;
            case PROJECT -> scope == Scope.PROJECT;
        };
    }

    /**
     * Whether a setting is asked when what it configures is created, shown on the settings screens, or folded away.
     * The wizard of a setting's scope asks its {@link #ESSENTIAL} settings (the first-boot wizard the global and tenant
     * ones), and the settings screens fold {@link #ADVANCED} ones behind a disclosure. Every tier is otherwise
     * equally editable, validated and live.
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

    /** The narrowest level a setting's value may be set at: the deployment ({@link #GLOBAL}, the default), a tenant
     *  ({@link #TENANT}), one repository ({@link #REPOSITORY}) or one build-cache project ({@link #PROJECT}). */
    public enum Scope {
        GLOBAL, TENANT, REPOSITORY, PROJECT
    }

    /** Whether a value parses for this setting's kind, so even a restart-only setting cannot be stored with a value
     *  that would fail to bind at boot. */
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
                    // Exactly two words: the readers collapse any other spelling onto one of them, so `yes` could be
                    // read as false.
                    case BOOLEAN -> {
                        String word = value.trim();
                        if (!word.equalsIgnoreCase("true") && !word.equalsIgnoreCase("false")) {
                            return false;
                        }
                    }
                    case INTEGER -> Integer.parseInt(value.trim());
                    case LONG -> Long.parseLong(value.trim());
                    // The whole grammar the dials accept, suffixed forms included.
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
