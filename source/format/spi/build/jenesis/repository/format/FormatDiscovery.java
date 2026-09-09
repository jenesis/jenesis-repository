package build.jenesis.repository.format;

import module java.base;

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

    private FormatDiscovery() {
    }
}
