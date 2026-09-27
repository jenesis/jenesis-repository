package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The one place a setting is changed, at every level - the deployment's, a tenant's, a repository's and a build-cache
 * project's - and the one place a repository's or a project's settings are read for editing. The API, the console and
 * so the command line all change settings here: the API's handlers call it, the console calls it in process, and the
 * CLI is a client of the API.
 *
 * <p>A change is decided the same way whoever asks for it:
 * <ol>
 * <li>every value is refused or accepted before anything is written ({@link #refusals}): the catalogue's refusal for
 *     the level ({@link SettingsContributor#refusal(String, String, Setting.Scope, UnaryOperator)}), a key an operator
 *     pinned above the store, whose stored value would be inert ({@link Pinned}), and an
 *     {@link Setting#operatorOnly() operator-only} key asked for by someone who is not the operator;</li>
 * <li>a deployment or tenant change is resolved dry against what the node makes of its settings ({@link Resolution})
 *     - a value the kind accepts but a gate policy refuses is refused here rather than stored to fail every publish;
 *     </li>
 * <li>the values are written through {@link Settings}, which seals a SECRET value before it reaches the store, drops
 *     the writing node's cached snapshot so it applies the change at once, and moves the settings epoch so every other
 *     node re-reads;</li>
 * <li>and each value the change names is recorded on the audit trail under one name - {@link AuditActions#SETTING_SET} or
 *     {@link AuditActions#SETTING_CLEAR}, its target the key prefixed by the level it was set at - so the trail says
 *     the same thing of a change whichever surface made it.</li>
 * </ol>
 * A refused change writes nothing and throws {@link IllegalArgumentException} naming every refused value.
 */
public final class SettingsEditor {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * What the running node makes of its settings: a candidate is checked before it is written, and a deployment
     * change is applied at once. The repository server's is its {@link LiveConfig}.
     */
    public interface Resolution {

        /** Throw when the deployment's stored values, {@code deployment}, would not resolve. */
        void check(Map<String, String> deployment);

        /** Throw when {@code tenant}'s values with {@code values} in place would not resolve. */
        void checkTenant(String tenant, Map<String, String> values);

        /** Apply the deployment's values this node now holds. */
        void applied();
    }

    /** Who a change is recorded against: the tenant whose trail it lands on, and the acting identity. */
    public record Actor(String tenant, String name) {
    }

    /** A change refused because what it sets is pinned above the store, where a stored value would be inert. */
    public static final class Pinned extends IllegalArgumentException {

        Pinned(String message) {
            super(message);
        }
    }

    /**
     * One setting of a level, as a screen or an answer shows it: the value the object set itself ({@code null} for
     * none), what it would inherit without one - its tenant's, the deployment's, else the setting's default - and the
     * pin that fixes it, if one does.
     */
    public record Row(Setting setting, String own, String inherited, Optional<PinnedSettings.Pin> pin) {

        /** The value in force: a pin, else the object's own, else what it inherits. */
        public String effective() {
            return pin.map(PinnedSettings.Pin::value).orElse(own != null ? own : inherited);
        }

        /** Whether the object set a value of its own. */
        public boolean overridden() {
            return own != null;
        }
    }

    private final Settings settings;
    private final Function<String, Optional<PinnedSettings.Pin>> pins;
    private final Resolution resolution;
    private final AuditTrail audit;

    public SettingsEditor(Settings settings, Function<String, Optional<PinnedSettings.Pin>> pins,
                          Resolution resolution, AuditTrail audit) {
        this.settings = settings;
        this.pins = pins;
        this.resolution = resolution;
        this.audit = audit;
    }

    /**
     * An editor over its own {@link Settings} of {@code store}, resolving through a {@link LiveConfig} of the shipped
     * defaults - for a composition that carries no repository server but still edits its settings (the console on its
     * own), and for a test. {@code config} answers the deployment's own configuration: its {@code secrets-key}, and
     * what a gate policy reads beneath the stored settings.
     */
    public static SettingsEditor over(ArtifactStore store, Function<String, Optional<PinnedSettings.Pin>> pins,
                                      AuditTrail audit, UnaryOperator<String> config) throws IOException {
        Settings settings = new Settings(store, SecretCipher.of(config.apply("secrets-key")));
        LiveConfig live = new LiveConfig(settings, new RepositoryProperties(), AdvisorySource.none(), config,
                key -> pins.apply(key).map(PinnedSettings.Pin::value));
        return new SettingsEditor(settings, pins, live, audit);
    }

    /** The settings this editor writes through. */
    public Settings settings() {
        return settings;
    }

    /** The pin fixing {@code key} above the store, or empty. */
    public Optional<PinnedSettings.Pin> pinned(String key) {
        return pins.apply(key);
    }

    /** The deployment's value of {@code key} as a refusal consults it: a pin, else the stored value. */
    public String deployment(String key) {
        return pins.apply(key).map(PinnedSettings.Pin::value).orElseGet(() -> settings.getOrDefault(key, null));
    }

    /**
     * Why each of {@code values} cannot be stored at {@code level}, keyed by setting, in key order - empty when every
     * one may. What a wizard asks when a step is left, and what every change asks first.
     */
    public SortedMap<String, String> refusals(Setting.Scope level, Map<String, String> values, boolean operator) {
        SortedMap<String, String> refused = new TreeMap<>();
        values.forEach((key, given) -> {
            String value = given == null ? "" : given;
            Optional<String> refusal = SettingsContributor.refusal(key, value, level, this::deployment);
            if (refusal.isPresent()) {
                refused.put(key, refusal.get());
                return;
            }
            Optional<PinnedSettings.Pin> pin = local(key) ? Optional.empty() : pins.apply(key);
            if (pin.isPresent()) {
                refused.put(key, pinned(key, pin.get()));
            } else if (!operator && SettingsScopes.operatorOnly(key)) {
                refused.put(key, "Setting '" + key + "' is the deployment operator's to set.");
            }
        });
        return refused;
    }

    private static boolean local(String key) {
        return SettingsScopes.declared(key).map(Setting::localOnly).orElse(false);
    }

    private static String pinned(String key, PinnedSettings.Pin pin) {
        return "Setting '" + key + "' is pinned by " + pin.source() + " and cannot be changed; the stored value would "
                + "be inert.";
    }

    /** Throw when any value is refused: {@link Pinned} when every refusal is a pin, else naming every refusal. */
    private void refuse(Setting.Scope level, Map<String, String> values, boolean operator) {
        SortedMap<String, String> refused = refusals(level, values, operator);
        if (refused.isEmpty()) {
            return;
        }
        String message = String.join(" ", refused.values());
        boolean allPinned = refused.keySet().stream()
                .allMatch(key -> !local(key) && pins.apply(key).isPresent());
        throw allPinned ? new Pinned(message) : new IllegalArgumentException(message);
    }

    /**
     * Set each non-blank value and clear each blank one deployment-wide, once every one passes {@link #refusals} and
     * the deployment resolves with them in place; the node applies them at once.
     */
    public void deployment(Map<String, String> values, Actor actor) throws IOException {
        refuse(Setting.Scope.GLOBAL, values, true);
        Map<String, String> candidate = new TreeMap<>();
        settings.overrides().keySet().forEach(key -> candidate.put(key, settings.getOrDefault(key, null)));
        values.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                candidate.remove(key);
            } else {
                candidate.put(key, value.trim());
            }
        });
        check(values, () -> resolution.check(candidate));
        settings.set(values);
        resolution.applied();
        recorded(actor, "", values);
    }

    /**
     * Set each non-blank value and clear each blank one in {@code tenant}'s own documents, once every one passes
     * {@link #refusals} at the tenant level and the tenant resolves with them in place.
     */
    public void tenant(String tenant, Map<String, String> values, boolean operator, Actor actor) throws IOException {
        if (!SettingsDocuments.validTenant(tenant)) {
            throw new IllegalArgumentException("Not a tenant name: " + tenant);
        }
        refuse(Setting.Scope.TENANT, values, operator);
        check(values, () -> resolution.checkTenant(tenant, values));
        settings.setTenant(tenant, values);
        recorded(actor, tenant + "/", values);
    }

    /** Set each non-blank value and clear each blank one in a repository's own documents, once every one passes
     *  {@link #refusals} at the repository level. */
    public void repository(String tenant, String repository, Map<String, String> values, boolean operator,
                           Actor actor) throws IOException {
        refuse(Setting.Scope.REPOSITORY, values, operator);
        settings.setRepository(tenant, repository, values);
        recorded(actor, tenant + "/" + repository + "/", values);
    }

    /** Set each non-blank value and clear each blank one in a build-cache project's own documents, once every one
     *  passes {@link #refusals} at the project level. */
    public void project(String tenant, String project, Map<String, String> values, Actor actor) throws IOException {
        refuse(Setting.Scope.PROJECT, values, true);
        settings.setProject(tenant, project, values);
        recorded(actor, tenant + "/" + project + "/", values);
    }

    /** Run a dry resolve, turning its failure into a refusal that names what was asked. */
    private static void check(Map<String, String> values, Runnable resolve) {
        try {
            resolve.run();
        } catch (RuntimeException refused) {
            throw new IllegalArgumentException("The settings " + new TreeMap<>(values).keySet() + " do not resolve "
                    + "with " + (values.size() == 1 ? "this value" : "these values") + ": " + refused.getMessage(),
                    refused);
        }
    }

    private void recorded(Actor actor, String level, Map<String, String> values) {
        new TreeMap<>(values).forEach((key, value) -> audit.record(actor.tenant(), actor.name(),
                value == null || value.isBlank() ? AuditActions.SETTING_CLEAR : AuditActions.SETTING_SET,
                level + key));
    }

    /**
     * Define the repository name {@code name} deployment-wide - {@code repositories.<name>}, what every tenant's
     * repository of that name routes by unless it sets its own routing - or, with a blank {@code specification},
     * remove the definition. A definition is parsed as the boot sweep parses it and its upstreams screened as every
     * outbound target is, so a definition that would not route, or would fetch from where this deployment must not,
     * is refused rather than stored.
     */
    public void definition(String name, String specification, Actor actor) throws IOException {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid repository name '" + name + "'.");
        }
        String key = SettingsScopes.repositoryKey(name);
        refusePinned(key);
        if (specification == null || specification.isBlank()) {
            settings.set(key, "");
            audit.record(actor.tenant(), actor.name(), AuditActions.REPOSITORY_REMOVE, name);
            return;
        }
        RepositoryDefinition definition;
        try {
            definition = RepositoryDefinition.parse(specification);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Repository '" + name + "' has an invalid definition '" + specification
                    + "': " + invalid.getMessage() + " Fix the definition (writable / fallback <source> "
                    + "[nocache|harden|unscreened]), or remove it - a repository definition that cannot be parsed is "
                    + "refused rather than stored.", invalid);
        }
        String refused = RepositoryDefinition.upstreamRefusal(definition, allowInternal());
        if (refused != null) {
            throw new IllegalArgumentException("Repository '" + name + "' has a refused definition '" + specification
                    + "': " + refused + "." + RepositoryDefinition.upstreamRemedy());
        }
        settings.set(key, specification.trim());
        audit.record(actor.tenant(), actor.name(), AuditActions.REPOSITORY_SET, name);
    }

    /**
     * Name the upstream a format's proxy fetches from - the deployment's, or with a {@code tenant} that tenant's own,
     * which its repositories pull through instead - or, with a blank {@code url}, remove it. Screened as every
     * outbound target is.
     */
    public void upstream(String tenant, String format, String url, Actor actor) throws IOException {
        if (!NAME.matcher(format).matches()) {
            throw new IllegalArgumentException("Invalid format name '" + format + "'.");
        }
        String key = SettingsScopes.upstreamKey(format);
        refusePinned(key);
        boolean removed = url == null || url.isBlank();
        if (!removed) {
            URI upstream;
            try {
                upstream = URI.create(url.trim());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("The '" + format + "' upstream '" + url + "' is not a URL.",
                        invalid);
            }
            String refused = RepositoryDefinition.upstreamRefusal(upstream, allowInternal());
            if (refused != null) {
                throw new IllegalArgumentException("The '" + format + "' upstream '" + url + "' is refused: "
                        + refused + "." + RepositoryDefinition.upstreamRemedy());
            }
        }
        String value = removed ? null : url.trim();
        if (tenant == null || tenant.isBlank()) {
            settings.set(key, value);
        } else {
            settings.set(tenant, key, value);
        }
        audit.record(actor.tenant(), actor.name(), removed ? AuditActions.UPSTREAM_REMOVE : AuditActions.UPSTREAM_SET,
                tenant == null || tenant.isBlank() ? format : tenant + "/" + format);
    }

    private void refusePinned(String key) {
        Optional<PinnedSettings.Pin> pin = pins.apply(key);
        if (pin.isPresent()) {
            throw new Pinned(pinned(key, pin.get()));
        }
    }

    /** Whether the deployment admits an internal or plaintext upstream: the one outbound dial, pin over store. */
    private boolean allowInternal() {
        return Boolean.parseBoolean(Objects.requireNonNullElse(deployment(RepositoryDefinition.ALLOW_INTERNAL_SETTING),
                "false"));
    }

    /**
     * Restore an exported bundle: the deployment's documents and every tenant slice it carries, a full restore that
     * clears what the bundle omits and keeps every stored secret, since a bundle never carries one. It is refused
     * before anything is written when it would not resolve - the deployment's values on their own and each tenant's
     * over them - when a value is one its setting or its outbound screen refuses, or when it sets a pinned key.
     */
    public void importBundle(Map<String, ? extends Map<String, String>> bundle, Actor actor) throws IOException {
        Map<String, String> global = new LinkedHashMap<>();
        Map<String, Map<String, String>> perTenant = new LinkedHashMap<>();
        bundle.forEach((key, document) -> {
            if (document == null) {
                return;
            }
            if (SettingsDocuments.isTenantKey(key)) {
                String[] parsed = SettingsDocuments.parseTenantKey(key);
                if (parsed == null) {
                    return;   // an unsafe tenant key is refused, with its own message, by the restore below
                }
                values(document, perTenant.computeIfAbsent(parsed[0], _ -> new LinkedHashMap<>()));
            } else {
                values(document, global);
            }
        });
        try {
            resolution.check(global);
            perTenant.values().forEach(slice -> {
                Map<String, String> combined = new LinkedHashMap<>(global);
                combined.putAll(slice);
                resolution.check(combined);
            });
        } catch (RuntimeException refused) {
            throw new IllegalArgumentException(UNRESOLVED, refused);
        }
        screen(bundle);
        settings.importBundle(bundle);
        resolution.applied();
        audit.record(actor.tenant(), actor.name(), AuditActions.SETTINGS_IMPORT, SettingsDocuments.ROOT);
    }

    /** Restore one tenant's slice - its documents only, the deployment's and every other tenant's untouched - refused
     *  on the same terms as {@link #importBundle}, the slice resolved over the deployment's values. */
    public void importTenant(String tenant, Map<String, ? extends Map<String, String>> bundle, Actor actor)
            throws IOException {
        Map<String, String> combined = new LinkedHashMap<>();
        settings.overrides().keySet().forEach(key -> combined.put(key, settings.getOrDefault(key, null)));
        bundle.values().forEach(document -> {
            if (document != null) {
                values(document, combined);
            }
        });
        try {
            resolution.check(combined);
        } catch (RuntimeException refused) {
            throw new IllegalArgumentException(UNRESOLVED, refused);
        }
        screen(bundle);
        settings.importTenant(tenant, bundle);
        audit.record(actor.tenant(), actor.name(), AuditActions.SETTINGS_IMPORT,
                tenant + "/" + SettingsDocuments.ROOT);
    }

    /** Why a bundle that does not resolve is refused. */
    public static final String UNRESOLVED = "the settings bundle does not resolve to a valid configuration";

    private static void values(Map<String, String> document, Map<String, String> into) {
        document.forEach((setting, value) -> {
            if (value != null) {
                into.put(setting, value);
            }
        });
    }

    /** Refuse a bundle value its setting does not parse or its outbound screen refuses, and every pinned key in it,
     *  named together - a restore is one operation, and refusing it one key at a time would have an operator edit and
     *  post it again and again. */
    private void screen(Map<String, ? extends Map<String, String>> bundle) {
        boolean allowInternal = allowInternal();
        SortedSet<String> pinned = new TreeSet<>();
        for (Map<String, String> document : bundle.values()) {
            if (document == null) {
                continue;
            }
            document.forEach((key, value) -> {
                if (pins.apply(key).isPresent()) {
                    pinned.add(key);
                }
                if (value == null || value.isBlank()) {
                    return;
                }
                Optional<Setting> setting = SettingsScopes.declared(key);
                if (setting.isPresent() && !setting.get().parses(value)) {
                    throw new IllegalArgumentException("'" + value + "' is not a valid value for setting '" + key
                            + "'.");
                }
                if (setting.isEmpty() && key.startsWith(SettingsScopes.REPOSITORY_PREFIX)) {
                    String refused = RepositoryDefinition.upstreamRefusal(RepositoryDefinition.parse(value),
                            allowInternal);
                    if (refused != null) {
                        throw new IllegalArgumentException("'" + key + "' is refused: " + refused + "."
                                + RepositoryDefinition.upstreamRemedy());
                    }
                } else if (setting.isEmpty() && key.startsWith(SettingsScopes.UPSTREAM_PREFIX)) {
                    String refused = RepositoryDefinition.upstreamRefusal(URI.create(value), allowInternal);
                    if (refused != null) {
                        throw new IllegalArgumentException("'" + key + "' is refused: " + refused + "."
                                + RepositoryDefinition.upstreamRemedy());
                    }
                }
            });
        }
        if (!pinned.isEmpty()) {
            throw new Pinned("the bundle sets " + pinned.size() + " setting(s) this deployment pins from above the "
                    + "store, whose stored values would be inert: " + String.join(", ", pinned) + ". Remove them "
                    + "from the bundle, or unpin them where they are pinned.");
        }
    }

    /**
     * The settings of one level as a screen or an answer shows them - a repository's or a build-cache project's -
     * each with what the object set itself, what it inherits and what pins it. {@code name} is the repository or the
     * project, or {@code null} for one not created yet, which has nothing of its own - what a creation wizard shows.
     * Its reads are the object's own documents by name and the cached tenant and deployment snapshots.
     */
    public List<Row> rows(Setting.Scope level, String tenant, String name) throws IOException {
        Map<String, String> own = own(level, tenant, name);
        List<Row> rows = new ArrayList<>();
        for (Setting setting : SettingsContributor.all()) {
            if (setting.scope() != level) {
                continue;
            }
            rows.add(new Row(setting, own.get(setting.key()), inherited(setting, tenant),
                    setting.localOnly() ? Optional.empty() : pins.apply(setting.key())));
        }
        return rows;
    }

    /** What an object of {@code level} in {@code tenant} would have for {@code setting} without a value of its own:
     *  the tenant's, else the deployment's, else the setting's default - a local setting's default only. */
    private String inherited(Setting setting, String tenant) {
        String fallback = setting.defaultValue().isBlank() ? "" : setting.defaultValue();
        if (setting.localOnly()) {
            return fallback;
        }
        return settings.getOrDefault(tenant, setting.key(), fallback);
    }

    /**
     * The configuration one repository or project runs under: for each key a pin, else its own value, else what it
     * inherits ({@link Row#inherited}) - a key of no setting resolving as the tenant resolves it. What a retention
     * preview, a project's eviction and a project listing judge by.
     */
    public UnaryOperator<String> config(Setting.Scope level, String tenant, String name) throws IOException {
        Map<String, String> own = own(level, tenant, name);
        return key -> {
            Optional<Setting> setting = SettingsScopes.declared(key);
            boolean local = setting.map(Setting::localOnly).orElse(false);
            Optional<PinnedSettings.Pin> pin = local ? Optional.empty() : pins.apply(key);
            if (pin.isPresent()) {
                return pin.get().value();
            }
            String value = own.get(key);
            if (value != null) {
                return value;
            }
            String fallback = setting.map(Setting::defaultValue).filter(text -> !text.isBlank()).orElse(null);
            return local ? fallback : settings.getOrDefault(tenant, key, fallback);
        };
    }

    private Map<String, String> own(Setting.Scope level, String tenant, String name) throws IOException {
        if (name == null) {
            return Map.of();
        }
        return switch (level) {
            case REPOSITORY -> settings.overrides(tenant, name);
            case PROJECT -> settings.project(tenant, name);
            default -> throw new IllegalArgumentException("Only a repository's or a project's settings are read as "
                    + "a level's rows.");
        };
    }
}
