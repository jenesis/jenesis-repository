package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.StoreBindings;

/**
 * The effective settings lookup the compliance machinery resolves its per-request dials through, bound by the
 * deployment to the store it builds and read by everything that has to agree with the screen.
 *
 * <p>It lives here rather than on the screen because its readers are not all screens: the proxy path resolves signer
 * trust through it before it has a verdict, and two observers re-derive outside the screening module what a late
 * signature completes. Each of those must read the <em>same</em> lookup the screen did, or an operator's runtime key
 * would admit an artifact on one path and not on another - so the lookup is compliance vocabulary, and this is the
 * compliance contract home.
 *
 * <p>Every one of those readers is a discovered service with no constructor a deployment reaches, and every one is
 * handed the store it works over; so the deployment {@linkplain #bindings binds} its lookup to that store, and a reader
 * asks the store it was handed ({@link #lookup(ArtifactStore)}). Two deployments in one process each read their own.
 *
 * <p><b>A store carrying no lookup answers the boot environment rather than nothing.</b> A composition that binds no
 * lookup still has the environment it started with, and a dial set there - a system property, an environment variable
 * - is what such a deployment means. Answering {@code null} for every key would make every dial read as its default
 * whatever the process was started with, which is the quieter and worse failure.
 */
public final class ComplianceSettings {

    /** The deployment's lookup, as its store carries it. */
    private record Bound(Supplier<UnaryOperator<String>> settings) {
    }

    private ComplianceSettings() {
    }

    /**
     * The lookup to resolve a dial through for work over {@code store}: what the deployment bound to it, else the boot
     * environment. Never {@code null}, so a caller never has to decide what an absent configuration means.
     */
    public static UnaryOperator<String> lookup(ArtifactStore store) {
        UnaryOperator<String> configured = store == null ? null
                : store.bindings().get(Bound.class).map(bound -> bound.settings().get()).orElse(null);
        return configured == null ? Features.settings() : configured;
    }

    /**
     * The deployment's lookup as the bindings of the store it builds, so an inspector that verifies signatures is
     * handed the keys an operator configured at runtime rather than the ones the process booted with - a key written
     * through the settings API never reaches {@link Features#settings()}. Resolved per call, so it follows the
     * settings as they change.
     */
    public static StoreBindings bindings(Supplier<UnaryOperator<String>> settings) {
        return StoreBindings.of(Bound.class, new Bound(Objects.requireNonNull(settings, "settings")));
    }

    /** {@code store} carrying {@code settings} as its deployment's lookup - {@link #bindings} over one store. */
    public static ArtifactStore bind(ArtifactStore store, Supplier<UnaryOperator<String>> settings) {
        return bindings(settings).over(store);
    }
}
