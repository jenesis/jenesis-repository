package build.jenesis.repository.format;

import module java.base;

import build.jenesis.repository.store.Providers;

/** The one discovery of the formats on the module path, validated through {@link Providers#all} - a blank or
 *  duplicated name is a packaging error that throws here, once, rather than a discovery-order winner - and cached for
 *  the process: a format keeps state (a bounded digest set, a listing coalescer), so every consumer shares the one
 *  instance set rather than loading its own. A holder rather than a field on the interface, so the load happens on
 *  first use of the list and not on the interface's own initialisation. */
final class FormatDiscovery {

    static final List<RepositoryFormat> DECLARED = Providers.all("format",
            ServiceLoader.load(RepositoryFormat.class), RepositoryFormat::name, _ -> true, Optional::of);

    private FormatDiscovery() {
    }
}
