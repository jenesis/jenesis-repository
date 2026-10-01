package build.jenesis.repository.format.lifecycle;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;

/**
 * A hosted version's lifecycle flag - a mark that a coordinate/version is <b>deprecated</b> or <b>yanked</b> -
 * persisted as a small object in the repository's scoped store at {@code lifecycle/<coordinate>/<version>}, so it is
 * confined to the tenant and repository as the artifact is, and read back by a format for its native response (npm's
 * {@code deprecated} message, Cargo's {@code yanked} flag). The coordinate is whatever identifies the artifact in that
 * store - an npm name (a scoped {@code @scope/name} keeps its slash), a Cargo {@code <registry>/<crate>} - and the
 * format and the operator endpoint agree on it here.
 *
 * <p>The value is the state's lower-case name, then an optional message on the following lines, written with the
 * compare-and-set retry a versioned pointer uses, so a concurrent re-mark resolves last-writer-wins. Stateless; every
 * operation takes the caller's scoped store.
 */
public final class Lifecycle {

    /** The store-key namespace flags live under, beside a format's own {@code <format>/...} data. */
    private static final String ROOT = "lifecycle";

    private Lifecycle() {
    }

    /** Whether a version is affected by a lifecycle mark and, if so, what kind. */
    public enum State {

        /** The version is discouraged but still resolvable - npm renders a {@code deprecated} warning. */
        DEPRECATED,

        /** The version is withdrawn - Cargo renders it {@code yanked}, so a resolver skips it unless already pinned. */
        YANKED;

        /** Parse a case-insensitive state name ({@code deprecated} / {@code yanked}), or empty when unrecognised. */
        public static Optional<State> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            String trimmed = value.trim();
            for (State state : values()) {
                if (state.name().equalsIgnoreCase(trimmed)) {
                    return Optional.of(state);
                }
            }
            return Optional.empty();
        }
    }

    /** A lifecycle mark: its {@link State} and an optional operator message (never {@code null}; empty when none). */
    public record Flag(State state, String message) {

        public Flag {
            Objects.requireNonNull(state, "state");
            message = message == null ? "" : message;
        }
    }

    /** One flagged version: its {@code coordinate}, {@code version} and the {@link Flag}. */
    public record Entry(String coordinate, String version, Flag flag) {
    }

    /** The disclosure decision the {@linkplain #page(ArtifactStore, Disclosure, String, int) flat listing} routes each
     *  mark through - typically {@code inventory.disclosableDisplay(coordinate + ":" + version, HIDE_WITHHELD)},
     *  supplied by the web adapter - so a withheld version's mark is not disclosed on the served view while this helper
     *  stays free of the inventory. A mark the seam holds is dropped; every other, including a deprecated servable
     *  version or a ghost with no blob, is kept. */
    @FunctionalInterface
    public interface Disclosure {

        /** Whether a marked {@code coordinate}/{@code version} may be disclosed on a served listing. */
        boolean disclosable(String coordinate, String version) throws IOException;
    }

    /** The flag on a coordinate/version, or empty when none is marked or the names are not traversal-safe, so a crafted
     *  lookup never reads outside {@code lifecycle/}. */
    public static Optional<Flag> read(ArtifactStore store, String coordinate, String version) throws IOException {
        if (!safeCoordinate(coordinate) || !ArtifactStore.safeSegment(version)) {
            return Optional.empty();
        }
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key(coordinate, version));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(decode(stored.get().content()));
    }

    /** Every flagged version of one coordinate, keyed by version - what a format merges into a coordinate's response. */
    public static SortedMap<String, Flag> versions(ArtifactStore store, String coordinate) throws IOException {
        TreeMap<String, Flag> flags = new TreeMap<>();
        if (!safeCoordinate(coordinate)) {
            return flags;
        }
        for (String version : store.list(ROOT + "/" + coordinate)) {
            read(store, coordinate, version).ifPresent(flag -> flags.put(version, flag));
        }
        return flags;
    }

    /** One page of the repository's flagged versions that {@code disclosure} discloses - the flat listing an operator
     *  surface renders, each mark routed through the caller's {@link Disclosure} (the {@code lifecycle-web} adapter
     *  passes the servable-name seam), so a withheld version's mark is not disclosed. The marks are one per deprecated
     *  or yanked version, sized by the repository, so the answer is paged like the walk beneath it. */
    public static Page page(ArtifactStore store, Disclosure disclosure, String after, int limit) throws IOException {
        List<Entry> disclosed = new ArrayList<>();
        String cursor = after == null || after.isEmpty() ? null : after;
        long examined = 0;
        while (disclosed.size() < limit && examined < EXAMINED) {
            // Capped at the room left in the page, so a call never delivers more than asked: everything delivered is
            // kept, and the walk's cursor resumes strictly after the page's last entry.
            Traversal.Result result = MARKS.entries(limit - disclosed.size())
                    .walk(store, ROOT, cursor, key -> mark(store, key, disclosure, disclosed));
            examined += result.delivered();
            if (result.exhausted()) {
                return new Page(List.copyOf(disclosed), null);
            }
            cursor = result.cursor().orElseThrow();
        }
        return new Page(List.copyOf(disclosed), cursor);
    }

    /** One page of disclosed marks and the cursor continuing it. {@code next} is {@code null} only when the walk
     *  provably reached the end; a short page still carrying a cursor is the {@link #EXAMINED} case, and a caller must
     *  continue. */
    public record Page(List<Entry> entries, String next) {
    }

    /** How many stored marks one call may open before answering short. Disclosure filters after the walk, so a
     *  repository whose marks are nearly all withheld would otherwise walk arbitrarily far for one page; at the bound
     *  the page comes back short with its cursor. */
    private static final int EXAMINED = 10_000;

    /** The bounds the mark listing descends {@code lifecycle/} under. {@link #page} sets the entry cap to the room left
     *  in the page; the binding bound is the step budget, raising a
     *  {@link build.jenesis.repository.walk.TraversalException} rather than answering short, and depth is the store's
     *  {@link ArtifactStore#MAX_SEGMENTS}. */
    private static final PagedTreeWalk MARKS = PagedTreeWalk.bounded().steps(1_000_000);

    /** Decode one stored mark and add it to {@code disclosed} when the seam discloses it. The key's last segment is the
     *  version, everything between the root and it the coordinate, as {@link #key} writes them. */
    private static void mark(ArtifactStore store, String key, Disclosure disclosure, List<Entry> disclosed)
            throws IOException {
        String relative = key.substring(ROOT.length() + 1);
        int slash = relative.lastIndexOf('/');
        if (slash < 0) {
            return;                                  // an object directly under the root carries no coordinate/version
        }
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        if (stored.isEmpty()) {
            return;
        }
        Flag flag = decode(stored.get().content());
        if (flag == null) {
            return;
        }
        Entry entry = new Entry(relative.substring(0, slash), relative.substring(slash + 1), flag);
        if (disclosure.disclosable(entry.coordinate(), entry.version())) {
            disclosed.add(entry);
        }
    }

    /** Mark a coordinate/version, overwriting any previous mark by a bounded compare-and-set retry, so a concurrent
     *  re-mark resolves last-writer-wins. A traversal-unsafe coordinate or version is refused. */
    public static void mark(ArtifactStore store, String coordinate, String version, Flag flag) throws IOException {
        Objects.requireNonNull(flag, "flag");
        if (!safeCoordinate(coordinate)) {
            throw new IllegalArgumentException("Not a traversal-safe coordinate: " + coordinate);
        }
        if (!ArtifactStore.safeSegment(version)) {
            throw new IllegalArgumentException("Not a traversal-safe version: " + version);
        }
        byte[] content = encode(flag);
        Retries.update(store, key(coordinate, version), _ -> content);
        Publication.notifyMarked(subject(coordinate, version), store);
    }

    /** Clear a coordinate/version's mark; {@code true} when one was present, {@code false} when none was (or the names
     *  are not traversal-safe). */
    public static boolean clear(ArtifactStore store, String coordinate, String version) throws IOException {
        if (!safeCoordinate(coordinate) || !ArtifactStore.safeSegment(version)) {
            return false;
        }
        String key = key(coordinate, version);
        if (store.readVersioned(key).isEmpty()) {
            return false;
        }
        store.delete(key);
        Publication.notifyMarked(subject(coordinate, version), store);
        return true;
    }

    /** Mark a version as an ecosystem client's own command asked - {@code gem yank}, {@code cargo yank},
     *  {@code npm deprecate} - and audit it as that caller. It writes the mark
     *  {@link #mark(ArtifactStore, String, String, Flag)} writes for the operator's surfaces, under the same action, so
     *  one state results whichever surface changed it. */
    public static void mark(FormatExchange exchange, ArtifactStore store, String coordinate, String version, Flag flag)
            throws IOException {
        mark(store, coordinate, version, flag);
        exchange.audit(action(flag.state()), coordinate + "@" + version);
    }

    /** Clear a version's mark as an ecosystem client's own command asked ({@code cargo yank --undo}, an empty
     *  {@code npm deprecate}), recording it on the audit trail when there was one to clear. */
    public static boolean clear(FormatExchange exchange, ArtifactStore store, String coordinate, String version)
            throws IOException {
        boolean cleared = clear(store, coordinate, version);
        if (cleared) {
            exchange.audit(AuditActions.LIFECYCLE_CLEAR, coordinate + "@" + version);
        }
        return cleared;
    }

    /** The audit action a mark of {@code state} is recorded under, on every surface that sets one. */
    public static String action(State state) {
        return switch (state) {
            case DEPRECATED -> AuditActions.LIFECYCLE_DEPRECATED;
            case YANKED -> AuditActions.LIFECYCLE_YANKED;
        };
    }

    /** The lifecycle-mark event's subject: the coordinate and version, no ecosystem (a mark is keyed without one). */
    private static ArtifactDescriptor subject(String coordinate, String version) {
        return new ArtifactDescriptor(null, coordinate, version, null, null, false, null, -1L);
    }

    private static String key(String coordinate, String version) {
        return ROOT + "/" + coordinate + "/" + version;
    }

    private static byte[] encode(Flag flag) {
        String message = flag.message();
        String body = message.isEmpty()
                ? flag.state().name().toLowerCase(Locale.ROOT)
                : flag.state().name().toLowerCase(Locale.ROOT) + "\n" + message;
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static Flag decode(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        int newline = text.indexOf('\n');
        String stateName = newline < 0 ? text : text.substring(0, newline);
        String message = newline < 0 ? "" : text.substring(newline + 1);
        return State.parse(stateName.strip()).map(state -> new Flag(state, message)).orElse(null);
    }


    /** A coordinate may carry {@code /} (an npm scope, a Cargo {@code <registry>/<crate>}), so each {@code /}-delimited
     *  part must be a safe, non-empty segment. */
    private static boolean safeCoordinate(String coordinate) {
        if (coordinate == null || coordinate.isEmpty()) {
            return false;
        }
        for (String segment : coordinate.split("/", -1)) {
            if (!ArtifactStore.safeSegment(segment)) {
                return false;
            }
        }
        return true;
    }
}
