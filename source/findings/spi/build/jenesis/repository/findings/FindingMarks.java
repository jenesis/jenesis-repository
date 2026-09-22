package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.icon.IconContributor;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;

/**
 * The findings family's own mark lookup: given the {@code source} a stored {@link Finding} was recorded with, which
 * plug-in is that, is it still on this deployment, and what does the console draw for it. The <em>generic</em> half -
 * the rendering rule, the generated figure, the dashed orphan tile and the three states - is {@link Marks}, shared
 * with every other contributing family; what lives here is only the part that is genuinely this family's, namely that
 * a finding names its contributor by a free-form string it recorded at write time and that may outlive it.
 *
 * <p><strong>Why a durable string needs a lookup at all.</strong> {@code (source, id)} is a finding's identity, so
 * {@code source} is written once and then merged against forever; it is not a foreign key anything validates and
 * nothing rewrites it when a module leaves. A deployment that drops the Snyk module keeps every row Snyk ever wrote,
 * each still saying {@code snyk} - which is correct, and is the whole point of categorize-never-discard - and the
 * console's job is to say so rather than to render those rows as though a live plug-in stood behind them. Resolution
 * is therefore a <em>join</em> between a recorded name and the contributors installed right now, and the answer is
 * one of exactly three:
 * <ul>
 *   <li>an installed contributor that declares a mark - {@link Mark.Kind#DECLARED}, its own document;</li>
 *   <li>an installed contributor that declares none - {@link Mark.Kind#GENERATED}, the figure derived from its name,
 *       a real per-plug-in identity rather than a placeholder;</li>
 *   <li><b>a name nothing installed answers to</b> - {@link Mark.Kind#ORPHANED}, the same figure inside a dashed
 *       tile, so the row keeps the identity it was recorded with and stays recognisable as that plug-in's.</li>
 * </ul>
 * There is no fourth answer and no absent one: {@link #of} always returns a {@link Mark}, because a finding always
 * names <em>something</em>. Absence of an installed contributor is information, never an error and never a reason to
 * touch the row - removed-module data cleanup is an explicit, dry-run-guarded operator action, and a module's absence
 * must never trigger anything but a diagnostic.
 *
 * <p><strong>Two kinds of contributor, because two kinds exist.</strong> Some finding-writing families are named on a
 * seam that can also carry a mark - {@code SignalSourceProvider}, whose {@code name()} is exactly the string its
 * advisories are recorded under - and those are handed in as {@link IconContributor}s, so a feed that ships a mark
 * gets {@code DECLARED}. Others are named but have no such seam: a maintenance task provider names itself and writes
 * findings under that name, and the publish screen writes under a stage name that belongs to no plug-in at all. Those
 * are handed in as bare {@code names}: they are installed, they attribute correctly, and they resolve to their
 * generated figure. Collapsing the two into one input would mean either inventing contributor objects for names that
 * have none, or refusing a mark to writers that are demonstrably present - and the second is exactly the confusion
 * between "installed, declares no mark" and "gone" that the three states exist to prevent.
 *
 * <p>Presentation only, and pure: it holds no domain state, reads no store, and performs no I/O (&sect;10) - it is
 * called once per rendered finding row. Each source's answer is resolved once and memoized, because discovery is
 * static for the life of the JVM and a mark is a constant in its contributor's module, so a page of two hundred rows
 * from a handful of feeds decodes each feed's document once rather than per row.
 */
public final class FindingMarks {

    /** The contributors that can declare a mark, keyed by the name their findings are recorded under. */
    private final Map<String, IconContributor> contributors;

    /** Installed writers that attribute by name but have no mark-bearing seam - see the class note. */
    private final Set<String> names;

    /** The resolved marks, memoized per recorded source. A source no installed writer claims memoizes its orphan
     *  mark just as readily: it is a legitimate, stable answer, not a cache miss to be retried. */
    private final Map<String, Mark> resolved = new ConcurrentHashMap<>();

    /**
     * Over the contributors and the bare installed names a deployment has right now. Both are copied, so the lookup
     * cannot change under a reader mid-page; a name appearing in both wins as a contributor, since a contributor can
     * say strictly more about itself than its name can.
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
     * The mark for a recorded {@link Finding#source()}. Never {@code null} and never empty - see the class note for
     * why the three answers are three and not two.
     *
     * @throws IllegalArgumentException if the source is blank, which a stored finding's never is: a finding is
     *                                  identified by {@code (source, id)}, so an unnamed one could not have been
     *                                  written, and silently sharing one "unknown" figure between two different
     *                                  unnamed things is the mis-attribution this whole seam exists to prevent.
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
