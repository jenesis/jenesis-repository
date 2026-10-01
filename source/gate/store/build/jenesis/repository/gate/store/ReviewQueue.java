package build.jenesis.repository.gate.store;

import module java.base;

import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.gate.HoldRecords;
import build.jenesis.repository.gate.QuarantineLog;

/**
 * The quarantine review queue as both operator surfaces render it: one bounded page of the artifacts a repository
 * currently holds, each with when and why it was held and the retroactive hold kinds standing on its coordinate. The
 * API ({@code GET /api/quarantine}) and the console's compliance review compose this page from the same three reads,
 * and two compositions would drift - a hold whose audit row was lost placed under different verdicts, an uninstalled
 * kind reported in different shapes - so the page is composed here once, and a surface only decides how to draw
 * it.
 *
 * <p>The queue is keyed off the live {@code /quarantine} hold pointers ({@link QuarantineLog#reviewQueue}) and only
 * enriched from the log, so a hold whose log row was lost or never landed still surfaces and stays releasable. The
 * kinds come from the durable {@code holds/} records ({@link HoldRecords#heldKinds}) rather than the installed
 * providers - an uninstalled kind's hold still holds - and each says whether a provider answers to it, which is what
 * an operator needs to know before releasing one. One kind enumeration per page, and a read per path and kind.
 *
 * <p>A version is reviewed whole - a jar is no use released without its POM - so a page does not cut between the files
 * of one: past its limit it reads on, a hold at a time, while the next held file is of the coordinate the page's last
 * one was recorded under, up to {@value #VERSION_TAIL} more. The files of a version sit beside each other in path
 * order, which is the order the page is cut in.
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

    /** How many held files past its limit a page reads to keep the last version on it whole. */
    static final int VERSION_TAIL = 64;

    /** At most {@code limit} held artifacts in path order after the pointer key {@code after} ({@code null} from the
     *  top), and the rest of the last one's version. */
    public static Page page(ArtifactStore store, String after, int limit) throws IOException {
        QuarantineLog log = new QuarantineLog(store);
        QuarantineLog.QueuePage page = log.reviewQueue(after, limit);
        List<QuarantineLog.Held> queue = new ArrayList<>(page.holds());
        String cut = page.next();
        String next = cut;
        Optional<String> version = cut == null ? Optional.empty() : queue.stream()
                .filter(held -> cut.equals(Publication.QUARANTINE_ROOT + held.path()))
                .findFirst().flatMap(QuarantineLog.Held::event).map(QuarantineLog.Event::coordinate);
        for (int read = 0; version.isPresent() && next != null && read < VERSION_TAIL; read++) {
            QuarantineLog.QueuePage more = log.reviewQueue(next, 1);
            if (more.holds().isEmpty()) {
                next = more.next();
                break;
            }
            QuarantineLog.Held following = more.holds().getFirst();
            if (!following.event().map(QuarantineLog.Event::coordinate).equals(version)) {
                break;
            }
            queue.add(following);
            next = more.next();
        }
        Map<String, SortedSet<String>> kinds =
                HoldRecords.heldKinds(store, queue.stream().map(QuarantineLog.Held::path).toList());
        Set<String> installed = HoldRecords.installedKinds();
        List<Row> rows = new ArrayList<>();
        for (QuarantineLog.Held held : queue) {
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
        return new Page(List.copyOf(rows), next);
    }
}
