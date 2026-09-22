package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.Features;

/**
 * The effective settings lookup the compliance machinery resolves its per-request dials through, wired once by the
 * composition root and read by everything that has to agree with the screen.
 *
 * <p>It lives here rather than on the screen because its readers are not all screens: the proxy path resolves signer
 * trust through it before it has a verdict, and two observers re-derive outside the screening module what a late
 * signature completes. Each of those must read the <em>same</em> lookup the screen did, or an operator's runtime key
 * would admit an artifact on one path and not on another - so the lookup is compliance vocabulary, and this is the
 * compliance contract home.
 *
 * <p><b>Unwired, it answers the boot environment rather than nothing.</b> A composition that wires no lookup still has
 * the environment it started with, and a dial set there - a system property, an environment variable - is what such a
 * deployment means. Answering {@code null} for every key made every dial read as its default whatever the process was
 * started with, which is the quieter and worse failure.
 */
public final class ComplianceSettings {

    /** JVM-wide, because the readers are discovered services with no constructor the root can reach. */
    private static final AtomicReference<Supplier<UnaryOperator<String>>> WIRED = new AtomicReference<>();

    private ComplianceSettings() {
    }

    /**
     * The lookup to resolve a dial through: what the composition root wired, else the boot environment. Never
     * {@code null}, so a caller never has to decide what an absent configuration means.
     */
    public static UnaryOperator<String> lookup() {
        Supplier<UnaryOperator<String>> wired = WIRED.get();
        UnaryOperator<String> configured = wired == null ? null : wired.get();
        return configured == null ? Features.settings() : configured;
    }

    /**
     * Wire the deployment's lookup, so an inspector that verifies signatures is handed the keys an operator
     * configured at runtime rather than the ones the process booted with - a key written through the settings API
     * never reaches {@link Features#settings()}. Closing the handle retires the wiring, and only if it is still the
     * current one, so a nested or overlapping wiring cannot retire its successor.
     */
    public static AutoCloseable wire(Supplier<UnaryOperator<String>> settings) {
        WIRED.set(settings);
        return () -> WIRED.compareAndSet(settings, null);
    }
}
