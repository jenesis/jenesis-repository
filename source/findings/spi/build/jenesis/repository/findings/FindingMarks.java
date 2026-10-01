package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.icon.IconContributor;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;

/**
 * Resolves the mark the console draws for the {@code source} a stored {@link Finding} was recorded with. The rendering
 * rule and the three states are {@link Marks}'; this family's part is that a finding names its contributor by a string
 * written once and kept after the contributor leaves, so resolution joins a recorded name with what is installed now:
 * <ul>
 *   <li>an installed contributor declaring a mark - {@link Mark.Kind#DECLARED};</li>
 *   <li>an installed writer declaring none - {@link Mark.Kind#GENERATED}, the figure derived from its name;</li>
 *   <li>a name nothing installed answers to - {@link Mark.Kind#ORPHANED}, the same figure in a dashed tile.</li>
 * </ul>
 * {@link #of} always returns a mark, and an absent contributor never touches the row.
 *
 * <p>Writers with a mark-bearing seam ({@code SignalSourceProvider}, whose {@code name()} is the recorded source) come
 * in as {@link IconContributor}s; writers named by no such seam (a maintenance task, the publish screen's stage) come
 * in as bare {@code names}, so they resolve as installed rather than gone.
 *
 * <p>Pure presentation with no I/O. Each source's answer is memoized, since discovery is fixed for the JVM's life.
 */
public final class FindingMarks {

    /** The contributors that can declare a mark, keyed by the name their findings are recorded under. */
    private final Map<String, IconContributor> contributors;

    /** Installed writers that attribute by name but have no mark-bearing seam. */
    private final Set<String> names;

    /** The resolved marks per recorded source, orphans included. */
    private final Map<String, Mark> resolved = new ConcurrentHashMap<>();

    /**
     * Over the deployment's contributors and bare installed names, both copied; a name in both resolves as a
     * contributor.
     */
    public FindingMarks(Collection<? extends IconContributor> contributors, Collection<String> names) {
        Map<String, IconContributor> byName = new LinkedHashMap<>();
        for (IconContributor contributor : contributors) {
            byName.put(contributor.name(), contributor);
        }
        this.contributors = Map.copyOf(byName);
        this.names = Set.copyOf(names);
    }

    /**
     * The mark for a recorded {@link Finding#source()}, never {@code null}.
     *
     * @throws IllegalArgumentException if the source is blank, which a stored finding's never is
     */
    public Mark of(String source) {
        Objects.requireNonNull(source, "source");
        return resolved.computeIfAbsent(source, this::resolve);
    }

    private Mark resolve(String source) {
        IconContributor contributor = contributors.get(source);
        if (contributor != null) {
            return Marks.of(contributor);
        }
        return names.contains(source) ? Marks.generated(source) : Marks.orphaned(source);
    }
}
