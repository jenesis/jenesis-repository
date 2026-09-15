package build.jenesis.repository.format;

import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * The one discovery of the formats on the module path, validated through {@link Providers#all} - a blank or
 * duplicated name is a packaging error that throws here, once, rather than a discovery-order winner - and cached for
 * the process, so every consumer reaches the formats through this list rather than loading its own.
 *
 * <p><b>The reason is validation and consistency, not shared state.</b> This used to say that a format "keeps state
 * (a bounded digest set, a listing coalescer)", and neither example was ever a field of a format: the digest memory
 * is a local of one enumeration, and the coalescer is a static of the listing store. Measured across every format in
 * this build, the only mutable per-instance state that exists at all is the throttle pacing OCI's upload-session
 * reap. It could not be otherwise - see {@link RepositoryFormat}'s lifecycle clause - so a second load does not
 * corrupt anything. What it actually costs is the validation above, an installed set free to disagree with the one
 * that serves, and a fresh allocation of every format on every call.
 *
 * <p>A holder rather than a field on the interface, so the load happens on first use of the list and not on the
 * interface's own initialisation.
 */
final class FormatDiscovery {

    static final List<RepositoryFormat> DECLARED = Providers.all("format",
            ServiceLoader.load(RepositoryFormat.class), RepositoryFormat::name, _ -> true, Optional::of);

    /** The active set and the lookup it was computed against - see {@link #installed()}. */
    private static volatile Active active;

    private record Active(UnaryOperator<String> lookup, List<RepositoryFormat> formats) {
    }

    /**
     * The formats the deployment's own configuration leaves switched on, held against the lookup that decided it.
     *
     * <h2>Why it is held at all</h2>
     *
     * {@link #DECLARED} is discovered once; the ACTIVE subset was re-derived on every call, and it is asked on
     * per-request and per-artifact paths - an inventory describing a path, the compliance screen deciding whose
     * prefix a publish is under, a browse rendering a row. Each call walked every declared format and asked the
     * configuration whether it was enabled and fully configured, which for a deployment carrying twenty-odd formats
     * is twenty-odd property lookups and two list allocations, per artifact.
     *
     * <p>Measured 2026-09-15 by a flight recording of a five-minute soak: {@code RepositoryFormat.installed} was the
     * hottest product frame in it, 262 of 20,707 execution samples - about 1.3% of sampled CPU in one method that
     * answers the same thing every time.
     *
     * <h2>Why holding it is safe, and what would make it unsafe</h2>
     *
     * The answer is a function of {@link Features#lookup() the installed lookup} and of nothing else, so the lookup
     * IS the cache key - by identity, because {@link Features#configure} installs a new one and {@link
     * Features#reset} installs a fresh default each time it is called. A deployment configures once at boot and the
     * answer is then fixed for the process; a test that reconfigures gets a fresh answer because it reconfigured.
     *
     * <p>The one shape this would get wrong is a caller that changes a format's toggle <em>underneath</em> the
     * default lookup - setting the {@code jenreg.<name>} system property without calling {@link Features#configure}
     * or {@link Features#reset} - since the default lookup reads those live. Nothing in either tree does that
     * (measured: no test sets a format toggle as a property at all, while 108 test sites go through configure or
     * reset), and a test that wants to is one {@code Features.reset()} away from being right.
     */
    static List<RepositoryFormat> installed() {
        UnaryOperator<String> lookup = Features.lookup();
        Active held = active;
        if (held != null && held.lookup() == lookup) {
            return held.formats();
        }
        List<RepositoryFormat> formats = installed(Features.settings());
        active = new Active(lookup, formats);
        return formats;
    }

    /** The same filter against a lookup the caller supplies, and deliberately not held: such a lookup is a Spring
     *  {@code Environment} a configuration class holds while the global one may not be installed yet, asked once as
     *  that class is built rather than per request. */
    static List<RepositoryFormat> installed(UnaryOperator<String> config) {
        List<RepositoryFormat> formats = new ArrayList<>();
        for (RepositoryFormat format : DECLARED) {
            if (Features.active(config, format.name(), format.requiredConfig())) {
                formats.add(format);
            }
        }
        return List.copyOf(formats);
    }

    private FormatDiscovery() {
    }
}
