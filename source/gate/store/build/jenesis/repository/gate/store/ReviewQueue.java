package build.jenesis.repository.gate.store;

import module java.base;

import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.gate.HoldRecords;
import build.jenesis.repository.gate.QuarantineLog;

/**
 * The quarantine review queue as both operator surfaces render it: one bounded page of the artifacts a repository
 * currently holds, each with when and why it was held and the retroactive hold kinds standing on its coordinate. The
 * API ({@code GET /api/quarantine}) and the console's compliance review used to compose this page separately from the
 * same three reads, and had drifted: one placed a hold whose audit row was lost under a {@code QUARANTINE} verdict
 * with a one-line reason, the other under a verdict that read "audit row missing" with no reasons; one reported an
 * uninstalled kind in a separate list, the other as a marked entry. The page is composed here once, and a surface
 * only decides how to draw it.
 *
 * <p>The queue is keyed off the live {@code /quarantine} hold pointers ({@link QuarantineLog#reviewQueue}) and only
 * enriched from the log, so a hold whose log row was lost or never landed still surfaces and stays releasable. The
 * kinds come from the durable {@code holds/} records ({@link HoldRecords#heldKinds}) rather than the installed
 * providers - an uninstalled kind's hold still holds - and each says whether a provider answers to it, which is what
 * an operator needs to know before releasing one. One kind enumeration per page, and a read per path and kind.
 */
public final class ReviewQueue {

    private ReviewQueue() {
    }

    /** One held artifact: when and at what path it was held, the coordinate and verdict the gate recorded, its
     *  reasons, and the hold kinds standing on the coordinate. A hold whose audit row is missing is reported as a
     *  {@link Verdict#QUARANTINE} whose one reason says so - the pointer is the truth, the row was the explanation. */
    public record Row(String when, String path, String coordinate, String verdict, List<String> reasons,
                      List<HeldKind> holds) {
    }

    /** A retroactive hold kind standing on a row's coordinate, and whether an installed provider answers to it.
     *  Orphaned never means invalid: the hold still holds, and the flag is the surface saying out loud that releasing
     *  it releases a hold no installed module can re-evaluate. */
    public record HeldKind(String kind, boolean installed) {
    }

    /** A page of rows and the pointer key the next page starts after ({@code null} on the last). */
    public record Page(List<Row> rows, String next) {
    }

    /** At most {@code limit} held artifacts in path order after the pointer key {@code after} ({@code null} from the
     *  top). */
    public static Page page(ArtifactStore store, String after, int limit) throws IOException {
        QuarantineLog.QueuePage page = new QuarantineLog(store).reviewQueue(after, limit);
        Map<String, SortedSet<String>> kinds =
                HoldRecords.heldKinds(store, page.holds().stream().map(QuarantineLog.Held::path).toList());
        Set<String> installed = HoldRecords.installedKinds();
        List<Row> rows = new ArrayList<>();
        for (QuarantineLog.Held held : page.holds()) {
            List<HeldKind> holds = kinds.getOrDefault(held.path(), Collections.emptySortedSet()).stream()
                    .map(kind -> new HeldKind(kind, installed.contains(kind)))
                    .toList();
            if (held.event().isPresent()) {
                QuarantineLog.Event event = held.event().get();
                rows.add(new Row(event.when().toString(), held.path(), event.coordinate(), event.verdict().name(),
                        event.reasons(), holds));
            } else {
                rows.add(new Row("", held.path(), held.path(), Verdict.QUARANTINE.name(),
                        List.of("audit row missing"), holds));
            }
        }
        return new Page(List.copyOf(rows), page.next());
    }
}
