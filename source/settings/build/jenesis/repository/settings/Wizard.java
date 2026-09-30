package build.jenesis.repository.settings;

import module java.base;

/**
 * The wizards that ask for settings, and what each asks - derived from the catalogue, never listed.
 *
 * <p>A wizard belongs to a level ({@link #level()}), the level whose documents its answers are stored in, and asks
 * every {@link Setting.Tier#ESSENTIAL essential} setting of the scopes it is for ({@link #asks}): one step per settings
 * group, the groups in catalogue order, and nothing else. A {@link Setting.Tier#STANDARD standard} or
 * {@link Setting.Tier#ADVANCED advanced} setting keeps what it inherits and is changed later on its level's settings
 * screen. So a module that declares an essential setting of a scope has put a question into that scope's wizard on
 * every surface that runs it, by declaring it - there is no second list to add it to.
 * <ul>
 * <li>{@link #SETUP} is the first boot's: the deployment's own essential settings, and a tenant's and a repository's,
 *     which it asks as the deployment-wide value every tenant and repository inherits - a repository's routing, which
 *     has no wider value, excepted;</li>
 * <li>{@link #REPOSITORY} is a new repository's: its essential repository settings, stored in its own documents;</li>
 * <li>{@link #PROJECT} is a new build-cache project's, likewise.</li>
 * </ul>
 *
 * <p>No wizard asks a secret: a wizard carries what it has been told from step to step in its page, and an essential
 * setting of the secret kind is refused by the census that holds the tiers.
 */
public enum Wizard {

    SETUP(Setting.Scope.GLOBAL, Setting.Scope.GLOBAL, Setting.Scope.TENANT, Setting.Scope.REPOSITORY),
    REPOSITORY(Setting.Scope.REPOSITORY, Setting.Scope.REPOSITORY),
    PROJECT(Setting.Scope.PROJECT, Setting.Scope.PROJECT);

    /**
     * The one step of the first boot's wizard that is about no setting: the starter credential, which is an
     * environment secret a deployment is provisioned with rather than a value in the store. Every surface that runs
     * the first boot's wizard shows it first, and says what to do about it; the console adds the state of this
     * deployment's two starter keys and links to where a real administrator and credential are made.
     */
    public static final Information STARTER_CREDENTIAL = new Information("starter-credential",
            "Stop using the starter credential",
            "A new deployment is first signed in to with the one-time key its start printed, which stops working after "
                    + "an hour or as soon as an administrator exists. The console's starter key (jenrepo.ui.admin-key) "
                    + "and the API's bootstrap key (jenrepo.bootstrap-key) are secrets a deployment is provisioned with, "
                    + "re-provisioned on every boot for as long as they are set. Grant a real administrator and issue a "
                    + "real credential, then unset both; removing an id from jenrepo.ui.admins does not revoke the grant "
                    + "it seeded.");

    /** A step that informs rather than asks: a stable id, a title and what it says. */
    public record Information(String id, String title, String text) {

        public Information {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(text, "text");
        }
    }

    /** One step: a settings group, and the settings of it this wizard asks, in catalogue order. */
    public record Step(String group, List<Setting> settings) {

        public Step {
            Objects.requireNonNull(group, "group");
            settings = List.copyOf(settings);
        }
    }

    private final Setting.Scope level;
    private final Set<Setting.Scope> scopes;

    Wizard(Setting.Scope level, Setting.Scope... scopes) {
        this.level = level;
        this.scopes = Set.of(scopes);
    }

    /** The level whose settings documents this wizard's answers are stored in. */
    public Setting.Scope level() {
        return level;
    }

    /** Whether this wizard asks {@code setting}: an essential setting of one of its scopes that its level holds. */
    public boolean asks(Setting setting) {
        return setting.tier() == Setting.Tier.ESSENTIAL && scopes.contains(setting.scope())
                && setting.settableAt(level);
    }

    /** The steps before the settings ones that inform rather than ask: the starter credential's, for the first boot. */
    public List<Information> information() {
        return this == SETUP ? List.of(STARTER_CREDENTIAL) : List.of();
    }

    /** The settings steps over {@code catalogue}: one per group holding a setting this wizard {@link #asks}, in the
     *  catalogue's order - which {@link SettingsContributor#all()} gives by group - with a group it asks nothing of
     *  left out. */
    public List<Step> steps(List<Setting> catalogue) {
        Map<String, List<Setting>> byGroup = new LinkedHashMap<>();
        for (Setting setting : catalogue) {
            if (asks(setting)) {
                byGroup.computeIfAbsent(setting.group(), _ -> new ArrayList<>()).add(setting);
            }
        }
        List<Step> steps = new ArrayList<>();
        byGroup.forEach((group, settings) -> steps.add(new Step(group, settings)));
        return List.copyOf(steps);
    }

    /** {@link #steps(List)} over the installed catalogue. */
    public List<Step> steps() {
        return steps(SettingsContributor.all());
    }
}
