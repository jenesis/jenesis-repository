package build.jenesis.repository.closure.spi;

import module java.base;
import build.jenesis.repository.store.Providers;

/** The discovered {@link ClosureSource}s in the order the closure pass asks them, held for the JVM's life: the module
 *  graph fixes the installed set, and the pass asks it once per release. */
final class InstalledClosures {

    static final List<ClosureSource> SOURCES = ordered(Providers.all("closure-source",
            ServiceLoader.load(ClosureSource.class), ClosureSource::name, _ -> true, Optional::of));

    private InstalledClosures() {
    }

    /** {@code sources} in the order the pass asks them, by kind then name.
     *
     *  @throws IllegalStateException for a source whose kind its role cannot have - a carried kind on a resolving
     *                               source, or the reverse */
    static List<ClosureSource> ordered(List<ClosureSource> sources) {
        for (ClosureSource source : sources) {
            if (source.kind().carried() != source instanceof ClosureSource.Carried) {
                throw new IllegalStateException("The closure source '" + source.name() + "' is of the kind "
                        + source.kind() + ", which a " + (source instanceof ClosureSource.Carried ? "carried"
                        : "resolving") + " source cannot have");
            }
        }
        return sources.stream().sorted(Comparator.comparing(ClosureSource::kind).thenComparing(ClosureSource::name))
                .toList();
    }
}
