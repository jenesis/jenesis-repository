package build.jenesis.repository.web.testkit;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.gateway.LiveDefinitions;
import build.jenesis.repository.server.FixedTenantRouting;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoriesRoutingContext;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * The wiring an admin controller needs, once.
 *
 * <p>Every {@code *-web} controller takes some subset of the same collaborators, and a suite per controller that
 * each stood its own kernel up would repeat one paragraph in every suite and let the copies drift - a controller
 * test arranged slightly differently from its neighbours is a test whose failure tells you less than it should.
 *
 * <p>This is deliberately <em>not</em> a Spring context. A controller here is constructed and called directly, so a
 * suite runs in milliseconds and stays in the fastest lane, and what it asserts is the handler's own behaviour
 * rather than the framework's dispatch. The route annotations are checked elsewhere, from the compiled artifacts.
 *
 * <p>The store is a real {@code FilesystemArtifactStore} over a temporary directory rather than a stub. That costs
 * nothing at this size and means a paging or disclosure assertion is made against the store the product actually
 * uses, not against a mock that agrees with whatever the test expected.
 */
public final class Web {

    private Web() {
    }

    /** A real filesystem-backed store rooted at {@code root} - the same provider a deployment resolves. */
    public static ArtifactStore store(Path root) {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** The kernel's repository view over {@code store}, with the proxy off and no advisory source - the arrangement
     *  an admin endpoint sees. Authorization is anonymous, so a suite asserts the handler's own checks rather than
     *  an authentication layer's. */
    public static Repositories repositories(ArtifactStore store) throws IOException {
        return repositories(store, Authorization.anonymous());
    }

    /** {@link #repositories(ArtifactStore)} deciding through {@code authorization} - an enforcing one over the same
     *  store when a suite drives credentials, quotas or roles, which an anonymous authorization holds none of. */
    public static Repositories repositories(ArtifactStore store, Authorization authorization) throws IOException {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setProxyEnabled(false);
        Settings settings = new Settings(store);
        LiveConfig live = new LiveConfig(settings, properties, AdvisorySource.none(), _ -> null);
        // The router's live definitions, as the boot wires them, so a definition a suite stores is what the resolver
        // answers for writability and hardening.
        return new Repositories(store, authorization, live,
                StagingProvider.resolve(_ -> null), RetentionProvider.resolve(_ -> null),
                new LiveDefinitions(live, settings, properties));
    }

    /** {@link #repositories(ArtifactStore)} over a fresh store at {@code root}. */
    public static Repositories repositories(Path root) throws IOException {
        return repositories(store(root));
    }

    /** The fixed routing over {@code store}, answering the {@code default} tenant as a deployment configuring none
     *  does - what a controller resolving {@code /repository/<tenant>/<repository>/...} routes through. */
    public static RepositoryRouting routing(ArtifactStore store, Repositories repositories) {
        return new FixedTenantRouting(new RepositoriesRoutingContext(store, repositories, "default", _ -> null),
                "default", "default");
    }

    /** The fixed routing over {@code repositories}' store serving {@code tenant}, administered by that tenant's keys
     *  alone - the tenant every {@code /api} call a suite makes through it answers for. */
    public static RepositoryRouting routing(Repositories repositories, String tenant) {
        return routing(repositories, tenant, tenant);
    }

    /** The fixed routing over {@code repositories}' store serving {@code tenant}, operated by {@code operator} - a
     *  deployment whose operator tenant is not the one it serves. */
    public static RepositoryRouting routing(Repositories repositories, String tenant, String operator) {
        return new FixedTenantRouting(new RepositoriesRoutingContext(repositories.root(), repositories, tenant,
                _ -> null), tenant, operator);
    }

    /** An environment holding exactly {@code pinned} and nothing ambient - no process environment, no system
     *  properties - so a handler reading the effective-value chain sees only what the suite puts there or in the
     *  store. Every key given is a pin, since it is set from above the store. */
    public static ConfigurableEnvironment environment(Map<String, Object> pinned) {
        ConfigurableEnvironment environment = new AbstractEnvironment() {
        };
        environment.getPropertySources().addFirst(new MapPropertySource("suite", new HashMap<>(pinned)));
        return environment;
    }

    /** Pins over {@code environment}: nothing pinned unless the suite pins it, so a stored setting is the effective
     *  one. A pin outranks the store, which is exactly the precedence a suite wants to be able to vary. */
    public static PinnedSettings pins(ConfigurableEnvironment environment) {
        return new PinnedSettings(environment);
    }

    /** The settings editor of {@code repositories}' node, over the settings its resolver reads - so a change a handler
     *  makes through it is what the resolver answers next - pinning nothing, and recording on {@code audit}. */
    public static SettingsEditor editor(Repositories repositories, AuditTrail audit) {
        return new SettingsEditor(repositories.live().settings(), _ -> Optional.empty(), repositories.live(), audit);
    }

    /** A scheduler with no passes registered - enough for a handler that only needs to take the exclusive lease,
     *  and nothing runs behind the suite's back. */
    public static MaintenanceScheduler scheduler(Repositories repositories, ArtifactStore store) {
        return new MaintenanceScheduler(repositories, store, List.of(), _ -> null, Duration.ofMinutes(5),
                new SimpleMeterRegistry());
    }

    /** An audit trail that keeps what it was told, so a suite can assert the action name a handler recorded. */
    public static Recording audit() {
        return new Recording();
    }

    /**
     * An {@link AuditTrail} that records into a list.
     *
     * <p>Worth having rather than a mock, because the two things a suite wants to say about an audited endpoint are
     * both about the whole row: that the action is the one name {@code AuditActions} owns for it - the trail is
     * queried by action, so a second spelling is a query that silently comes back short - and that the actor is a
     * hash rather than the credential itself.
     */
    public static final class Recording implements AuditTrail {

        private final List<Recorded> recorded = new ArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void record(String tenant, String actor, String action, String target) {
            recorded.add(new Recorded(tenant, actor, action, target));
        }

        @Override
        public List<Event> query(String tenant, Instant from, Instant to, String action) {
            List<Event> events = new ArrayList<>();
            for (Recorded row : recorded) {
                if (action == null || action.equals(row.action())) {
                    events.add(new Event(Instant.EPOCH, row.actor(), row.action(), row.target()));
                }
            }
            return events;
        }

        /** Everything recorded, in the order the handlers recorded it. */
        public List<Recorded> rows() {
            return List.copyOf(recorded);
        }

        /** Just the action names, which is what most assertions are about. */
        public List<String> actions() {
            return recorded.stream().map(Recorded::action).toList();
        }
    }

    /** One audited act, with the tenant {@link AuditTrail.Event} does not carry. */
    public record Recorded(String tenant, String actor, String action, String target) {
    }
}
