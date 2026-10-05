package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.store.Providers;

/** The discovered {@link ClosureSource}s in the order the closure pass asks them, held for the JVM's life: the module
 *  graph fixes the installed set, and the pass asks it once per release. */
final class InstalledClosures {

    static final List<ClosureSource> SOURCES = Providers.all("closure-source",
                    ServiceLoader.load(ClosureSource.class), ClosureSource::name, _ -> true, Optional::of)
            .stream().sorted(Comparator.comparing(ClosureSource::kind).thenComparing(ClosureSource::name))
            .toList();

    private InstalledClosures() {
    }
}
