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
 * <p>How a setting is declared beyond its value is its {@link Traits}, each set by one method: {@link #gate()},
 * {@link #essential()}, {@link #standard()} or {@link #advanced()}, {@link #local()}, {@link #operator()} and
 * {@link #form(Form)}. A {@link Kind#CHOICE} setting declares its {@link Choice choices} once, with what each reads as.
 */
public record Setting(String key, String group, String label, String description,
                      Kind kind, List<Choice> options, String defaultValue, boolean live, Scope scope, Traits traits) {

    /** The word a {@link Kind#DURATION_OR_NONE} setting takes to switch its rule off, rather than inherit a wider
     *  level's value - which is what leaving it unset means. */
    public static final String NONE = Durations.NONE;

    public Setting {
        options = List.copyOf(options);
        scope = scope == null ? Scope.GLOBAL : scope;
        traits = traits == null ? Traits.UNDECIDED : traits;
        if (traits.localOnly() && scope != Scope.REPOSITORY && scope != Scope.PROJECT) {
            throw new IllegalArgumentException("Setting '" + key + "' is " + scope + "-scoped, so it cannot be local: "
                    + "only a repository or project setting has a wider default to go without");
        }
    }

    /** A setting at an explicit {@link Scope} whose choices, if any, read as their values. */
    public Setting(String key, String group, String label, String description,
                   Kind kind, List<String> choices, String defaultValue, boolean live, Scope scope) {
        this(key, group, label, description, kind, choices.stream().map(Choice::of).toList(), defaultValue, live,
                scope, Traits.UNDECIDED);
    }

    /** A choice-carrying setting, deployment-wide by default. */
    public Setting(String key, String group, String label, String description,
                   Kind kind, List<String> choices, String defaultValue, boolean live) {
        this(key, group, label, description, kind, choices, defaultValue, live, Scope.GLOBAL);
    }

    /** A {@link Kind#CHOICE} setting at an explicit {@link Scope}, its choices declared once with what each reads as. */
    public Setting(String key, String group, String label, String description,
                   List<Choice> choices, String defaultValue, boolean live, Scope scope) {
        this(key, group, label, description, Kind.CHOICE, choices, defaultValue, live, scope, Traits.UNDECIDED);
    }

    /** A {@link Kind#CHOICE} setting, deployment-wide, its choices declared once with what each reads as. */
    public Setting(String key, String group, String label, String description,
                   List<Choice> choices, String defaultValue, boolean live) {
        this(key, group, label, description, choices, defaultValue, live, Scope.GLOBAL);
    }

    /**
     * How a setting is declared beyond its value: whether it is its module's enablement gate (the one setting a modules
     * console pairs with the module's toggle), its {@link Tier} ({@code null} until declared, which the catalogue
     * census refuses, since a default would decide for every contributor that forgot), whether it is a repository or
     * project setting with no wider default ({@code localOnly}, such as a repository's routing: a deployment-wide
     * default routing would point every repository at one upstream), whether only the deployment's operator may set
     * it, and the {@link Form} a form edits it as.
     */
    public record Traits(boolean enablement, Tier tier, boolean localOnly, boolean operatorOnly, Form form) {

        /** Nothing declared: no gate, no tier yet, inherited, anyone may set it, one line. */
        public static final Traits UNDECIDED = new Traits(false, null, false, false, Form.LINE);

        public Traits {
            form = form == null ? Form.LINE : form;
        }
    }

    /** Whether this is its module's enablement gate - see {@link Traits}. */
    public boolean enablement() {
        return traits.enablement();
    }

    /** The tier the setting is declared at, {@code null} while undeclared - see {@link Traits}. */
    public Tier tier() {
        return traits.tier();
    }

    /** Whether the setting has no wider default - see {@link Traits}. */
    public boolean localOnly() {
        return traits.localOnly();
    }

    /** Whether only the deployment's operator may set the setting - see {@link Traits}. */
    public boolean operatorOnly() {
        return traits.operatorOnly();
    }

    /** The values the setting may take, or suggests where its kind is not {@link Kind#CHOICE}, as the API, the
     *  command line and the store spell them. */
    public List<String> choices() {
        return options.stream().map(Choice::value).toList();
    }

    /** A copy of this setting marked as its module's enablement gate. */
    public Setting gate() {
        return with(new Traits(true, tier(), localOnly(), operatorOnly(), editedAs()));
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

    /** This repository or project setting, declared one with no wider default - see {@link Traits}. */
    public Setting local() {
        return with(new Traits(enablement(), tier(), true, operatorOnly(), editedAs()));
    }

    /** This setting, declared one only the deployment's operator may set - see {@link Traits}. */
    public Setting operator() {
        return with(new Traits(enablement(), tier(), localOnly(), true, editedAs()));
    }

    private Setting tiered(Tier decided) {
        return with(new Traits(enablement(), decided, localOnly(), operatorOnly(), editedAs()));
    }

    /** This setting, declared one a form edits as {@code shaped} rather than as one line of text - see {@link Form}. */
    public Setting form(Form shaped) {
        return with(new Traits(enablement(), tier(), localOnly(), operatorOnly(), shaped));
    }

    /** This setting, the values it suggests given the names and short descriptions a person reads, for a kind whose
     *  choices suggest rather than limit - see {@link Form#VALUES}. A {@link Kind#CHOICE} setting declares its named
     *  choices where it is made. */
    public Setting named(List<Choice> given) {
        Map<String, Choice> byValue = new HashMap<>();
        for (Choice choice : given) {
            if (!choices().contains(choice.value())) {
                throw new IllegalArgumentException("Setting '" + key + "' names the choice '" + choice.value()
                        + "', which is not one of its values " + choices());
            }
            byValue.put(choice.value(), choice);
        }
        return new Setting(key, group, label, description, kind,
                options.stream().map(option -> byValue.getOrDefault(option.value(), option)).toList(), defaultValue,
                live, scope, traits);
    }

    private Setting with(Traits declared) {
        return new Setting(key, group, label, description, kind, options, defaultValue, live, scope, declared);
    }

    /**
     * What one of a {@link Kind#CHOICE} setting's values reads as to a person: a name ("Hold for review") and a short
     * description of what choosing it does, which a console shows as "Name: description" where there is room and as
     * the name alone where there is not. The value stays what the API, the command line and the store use. A choice
     * a setting does not name is named from its value ({@code QUARANTINE} reads "Quarantine").
     */
    public record Choice(String value, String name, String description) {

        /** The gate's three verdicts, as every setting choosing one names them. */
        public static final List<Choice> VERDICTS = List.of(
                new Choice("ALLOW", "Allow", "the artifact is served, and what was found is recorded as a finding"),
                new Choice("QUARANTINE", "Hold for review",
                        "the artifact is stored but withheld until someone releases it"),
                new Choice("REJECT", "Reject", "the artifact is refused and nothing is stored"));

        public Choice {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
        }

        /** A choice that reads as its value, named from it ({@link #nameOf}). */
        public static Choice of(String value) {
            return new Choice(value, nameOf(value), "");
        }

        /** A value as words: a constant in capitals or words joined by hyphens or underscores reads as a sentence-cased
         *  phrase ({@code QUARANTINE} as "Quarantine", {@code not-found} as "Not found"); any other value - a name,
         *  a URL - reads as it is. */
        public static String nameOf(String value) {
            boolean constant = value.chars().noneMatch(Character::isLowerCase)
                    && value.chars().anyMatch(Character::isLetter);
            boolean joined = value.matches("[a-z0-9]+([-_][a-z0-9]+)+");
            if (!constant && !joined) {
                return value;
            }
            String words = value.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT);
            return Character.toUpperCase(words.charAt(0)) + words.substring(1);
        }
    }

    /** The name and description {@code value} reads as: as named, else named from the value itself. */
    public Choice choice(String value) {
        return options.stream().filter(choice -> choice.value().equals(value)).findFirst()
                .orElseGet(() -> Choice.of(value));
    }

    /** How a form edits this setting's value: as declared, else a single line. */
    public Form editedAs() {
        return traits.form();
    }

    /**
     * How a graphical form edits a value whose {@link Kind} alone does not say: the shape of the text, never its
     * meaning. The value stays the one text the API and the command line read and write; a console renders the shape
     * and turns what was entered back into that text. A kind that implies its own control - a switch for a
     * {@link Kind#BOOLEAN}, a choice for a {@link Kind#CHOICE}, an amount and a unit for a duration - needs none.
     */
    public enum Form {

        /** One line of text, the default. */
        LINE,

        /** Free text over several lines, such as a PEM block. */
        TEXT,

        /** A list, one entry per line. */
        LINES,

        /** Several values on one line, separated by commas, which a form shows as one removable entry each and adds to
         *  from the setting's {@link #choices() choices} - the values it knows, named as {@link Setting#named(List) named}. For a
         *  kind other than {@link Kind#CHOICE} the choices suggest rather than limit: a value they do not list is
         *  entered as it is. */
        VALUES,

        /** A JSON document. */
        JSON,

        /** A repository's routing: whether it accepts uploads, and its ordered fallbacks, each an upstream address with
         *  its caching and screening or another repository of the same deployment. */
        ROUTING
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
        return settable(scope, localOnly(), level);
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

        /** Asked on creation: a decision a new deployment, repository or project must make before it is used, easy
         *  to forget and costly to discover later - where a repository fetches from, how long it keeps what it holds,
         *  which advisory sources the gate asks and what it does with what they say, how a person signs in. A ceiling
         *  that defaults to none, a feature off until wanted or the shape of a multi-tenant routing is not one. */
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
        return kind.parses(value, choices());
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
