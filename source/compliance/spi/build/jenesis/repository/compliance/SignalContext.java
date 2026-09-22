package build.jenesis.repository.compliance;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Everything a {@link SignalSourceProvider} needs to build its {@link SignalSource} and must <em>not</em> discover for
 * itself: the effective configuration lookup, the durable space its snapshots live in, and the clock its staleness
 * stamps are taken from. One parameter, uniform across every provider - a feed that only reads a dial ignores the rest
 * exactly as it ignores a config key it does not own, and a feed that mirrors a whole catalogue reaches its durable
 * storage through the same seam instead of being wired specially somewhere a reader would never look.
 *
 * <p><strong>The snapshot space is deployment-global, and that is a property of this type rather than a comment.</strong>
 * A signal source is a deployment singleton - the CISA catalogue, the EPSS model and the OSV database are the same
 * public data for every tenant, refreshed once - which is the opposite of {@link VexProvider#over VexProvider.over},
 * whose first parameter is the tenant because a VEX statement genuinely belongs to the tenant that ingested it. So this
 * context carries <em>no tenant</em>, offers no way to supply one, and - decisively - <em>does not take an
 * {@link ArtifactStore} from its caller at all</em>. An {@code ArtifactStore} does not say at the type level whether it
 * is the deployment root or a tenant's (or a repository's) view of it, and the sites that resolve signal sources mostly
 * hold the latter: a maintenance pass sees only {@code RepositoryContext.store()}, documented as "scoped to this tenant
 * and repository". A caller-supplied store would therefore let a whole-catalogue mirror land under one repository, per
 * tenant, with nothing failing. Instead the deployment {@link #deployment binds} its root store once, where the root is
 * the only store in scope, and {@link #snapshots()} answers that binding narrowed to {@link #SNAPSHOT_ROOT} and then to
 * this one signal's own name. A provider therefore cannot reach a tenant's data, cannot reach another signal's
 * snapshots, and cannot mis-scope its own.
 *
 * <p><strong>Why the clock is here and the transport is not.</strong> The clock is the same kind of thing as the
 * snapshot space - ambient deployment state a provider must be handed rather than reach for - and every durable
 * staleness stamp is taken from it, so a test crosses a refresh interval without sleeping (the CISA catalogue already
 * hand-rolls its own {@code LongSupplier} seam for exactly this). The HTTP transport is deliberately absent: it lives
 * in the free {@code build.jenesis.repository.feed} support module, which carries {@code java.net.http}, and this is an
 * SPI contract module that stays {@code java.base}-light (plus the store contract the VEX seam already needs). A feed
 * builds its own transport in its own module, where the vendor's authentication, timeouts and decorators belong.
 *
 * @see SignalSourceProvider#create(SignalContext)
 */
public interface SignalContext {

    /**
     * The deployment-global store prefix every signal's snapshot space sits under, one sub-space per signal name:
     * {@code config/signals/<signal>}. It sits under {@code config/} deliberately - that root and {@code auth/} are the
     * only deployment-global spaces {@code StorageNamespace.SHARED_ROOTS} permits, and a signal mirror is deployment
     * data by definition, so this reuses the reserved root rather than minting a third one. A module that begins
     * persisting here declares {@code config/signals/<its name>} as its {@code StorageNamespace.sharedPrefixes()}, and
     * the orphan diagnostic and the operator purge then see it like any other key-space.
     */
    String SNAPSHOT_ROOT = Scopes.space(Scopes.CONFIG) + "/signals";

    /** The signal being created - the provider's own {@link SignalSourceProvider#name() name}, which is also the
     *  sub-space {@link #snapshots()} is confined to and the attribution key of everything the source reports. */
    String signal();

    /** The effective configuration value for {@code key} (stored settings over the deployment configuration),
     *  {@code null} when unset - the same lookup the provider used to be handed bare, now named. */
    String setting(String key);

    /**
     * The durable space this one signal owns, deployment-global and already confined to
     * {@code config/signals/}{@link #signal()}: an <em>already scoped</em> store, in the shape
     * {@code FeedSnapshots} asks for, which never scopes one itself. A whole-catalogue mirror commits its snapshot and
     * its staleness stamp here; a feed that queries per coordinate never touches it.
     *
     * @throws IllegalStateException when no deployment has {@link #deployment bound} its root store - a wiring error,
     *                               which fails loudly here rather than letting a mirror write nowhere and read as an
     *                               empty catalogue (&sect;9). A provider that never persists never sees it.
     */
    /**
     * Whether this source is switched on, read the one way the settings surface documents:
     * {@code jenreg.<name>=false} switches a source off and nothing else does, an unset value takes
     * {@code byDefault}, and the default a source passes is the one its own settings row declares.
     *
     * <p>It lives here because a source cannot reach {@code Features} for itself: half of them do not require the
     * store SPI at all, which is exactly why half of them had reimplemented the switch as
     * {@code Boolean.parseBoolean(setting(name))}. That disagrees with {@code Features} on an unset value - off
     * rather than on - and on every value that is neither "true" nor "false", so {@code jenreg.snyk=yes} enabled
     * Snyk while {@code jenreg.osv=yes} disabled OSV. Asking the context removes the choice.
     */
    default boolean enabled(String name, boolean byDefault) {
        return Features.enabled(this::setting, name, byDefault);
    }

    ArtifactStore snapshots();

    /** The deployment clock every staleness stamp and refresh deadline is taken from; {@link Clock#systemUTC()} until
     *  a deployment binds its own. A provider passes this down rather than calling {@code Instant.now()}, so a suite
     *  crosses a refresh interval without sleeping. */
    Clock clock();

    /** The context for one signal over an effective configuration lookup - what {@link SignalSourceProvider#named}
     *  hands each provider, and what a test builds directly. The snapshot space and the clock come from the
     *  deployment's {@link #deployment binding}, resolved when they are asked for rather than captured here, so a
     *  context outlives no wiring it was built beside. */
    static SignalContext of(String signal, UnaryOperator<String> config) {
        return Deployment.context(signal, config);
    }

    /**
     * Bind the deployment's <strong>root</strong> store and clock as the space every signal's snapshots live in - the
     * one wiring point, made by the composition that owns the root store, closing which retires the binding (only if
     * it is still the current one, so a second application context in the same JVM cannot unwire a live one). This is
     * the same registry-free "the deployment wires it once" seam {@code ComplianceScreen} uses for its health source
     * and meters, and it is sound here for the reason clause 6 gives: there is exactly one signal snapshot space per
     * deployment, so there is exactly one thing to bind. A per-tenant capability could never be wired this way, which
     * is precisely the distinction this SPI is making.
     */
    static Deployment deployment(ArtifactStore root, Clock clock) {
        return Deployment.bind(root, clock);
    }

    /** The deployment's binding of the signal snapshot space; closing it retires the binding. */
    final class Deployment implements AutoCloseable {

        private static final AtomicReference<Deployment> BOUND = new AtomicReference<>();

        private final ArtifactStore root;
        private final Clock clock;

        private Deployment(ArtifactStore root, Clock clock) {
            this.root = root;
            this.clock = clock;
        }

        private static Deployment bind(ArtifactStore root, Clock clock) {
            Deployment deployment = new Deployment(Objects.requireNonNull(root, "root"),
                    Objects.requireNonNull(clock, "clock"));
            BOUND.set(deployment);
            return deployment;
        }

        private static SignalContext context(String signal, UnaryOperator<String> config) {
            Objects.requireNonNull(config, "config");
            if (signal == null || signal.isBlank()) {
                throw new IllegalArgumentException("A signal context needs the signal's name");
            }
            return new Signal(signal.strip(), config);
        }

        @Override
        public void close() {
            BOUND.compareAndSet(this, null);
        }

        /** One signal's view: its own name, the caller's effective configuration lookup, and the deployment binding
         *  read at the moment it is asked for. */
        private record Signal(String signal, UnaryOperator<String> config) implements SignalContext {

            @Override
            public String setting(String key) {
                return config.apply(key);
            }

            @Override
            public ArtifactStore snapshots() {
                Deployment deployment = BOUND.get();
                if (deployment == null) {
                    throw new IllegalStateException("The " + signal + " signal asked for its durable snapshot space,"
                            + " but no deployment has bound its root store through SignalContext.deployment(...);"
                            + " refusing to mirror a catalogue into nowhere");
                }
                ArtifactStore store = deployment.root;
                for (String segment : SNAPSHOT_ROOT.split("/")) {
                    store = store.scope(segment);           // each backend validates the segment as traversal-free
                }
                return store.scope(signal);
            }

            @Override
            public Clock clock() {
                Deployment deployment = BOUND.get();
                return deployment == null ? Clock.systemUTC() : deployment.clock;
            }
        }
    }
}
