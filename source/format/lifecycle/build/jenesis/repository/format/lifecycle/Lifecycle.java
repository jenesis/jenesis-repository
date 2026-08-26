package build.jenesis.repository.format.lifecycle;

import module java.base;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;

/**
 * A hosted version's lifecycle flag - an operator's mark that a specific coordinate/version is <b>deprecated</b> or
 * <b>yanked</b> - persisted as a small per-tenant metadata object through the {@link ArtifactStore} abstraction and
 * read back by a format so it can be surfaced in that format's native response (npm's {@code deprecated} message,
 * Cargo's {@code yanked} flag, ...). The flag lives at {@code lifecycle/<coordinate>/<version>} <em>within the
 * repository's already-scoped store</em>, so it is confined to the tenant and repository exactly like the artifact it
 * annotates; the coordinate string is whatever uniquely identifies the artifact within that store - an npm package
 * name (a scoped {@code @scope/name} keeps its slash), a Cargo {@code <registry>/<crate>} - and both the reading
 * format and the writing operator endpoint agree on it here rather than each hard-coding a layout.
 *
 * <p>The value is a tiny text object (the state's lower-case name, then an optional message on the following lines),
 * written with the same compare-and-set retry a versioned pointer uses so a concurrent re-mark resolves
 * last-writer-wins rather than lost. Reads and writes are the only place a flag's bytes touch storage, and they are a
 * bounded metadata object, never an artifact blob, so the streaming principle is untouched. The helper is stateless;
 * every operation takes the caller's already-scoped store.
 */
public final class Lifecycle {

    /** The store-key namespace flags live under, a sibling of a format's own {@code <format>/...} data in the scope. */
    private static final String ROOT = "lifecycle";

    private Lifecycle() {
    }

    /** Whether a version is affected by a lifecycle mark and, if so, what kind. */
    public enum State {

        /** The version is discouraged but still resolvable - npm renders it as a {@code deprecated} warning. */
        DEPRECATED,

        /** The version is withdrawn - Cargo renders it {@code yanked} so a resolver skips it unless already pinned. */
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

    /**
     * A per-coordinate/version disclosure decision the {@linkplain #all(ArtifactStore, Disclosure) flat listing} routes
     * each mark through - the servable-name enumeration seam face (typically
     * {@code inventory.disclosableDisplay(coordinate + ":" + version, HIDE_WITHHELD)}) the operator surface supplies, so
     * a withheld version's mark is not disclosed on the served view (plan &sect;8 Q3, served-view parity). It is injected
     * rather than reached for here so this dependency-minimal, pure-JDK helper stays free of the inventory: the decision
     * that needs the ecosystem/layout lives in the {@code web} adapter that already carries it. A mark whose coordinate/
     * version the seam classifies not-disclosable (held) is dropped from the listing; every other mark - a
     * deprecated-but-servable version, a ghost with no blob - is kept.
     */
    @FunctionalInterface
    public interface Disclosure {

        /** Whether a marked {@code coordinate}/{@code version} may be disclosed on a served listing. */
        boolean disclosable(String coordinate, String version) throws IOException;
    }

    /**
     * The lifecycle flag on a coordinate/version, or empty when none is marked (or the names are not traversal-safe,
     * so a crafted lookup can never read outside the {@code lifecycle/} subtree).
     */
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

    /**
     * Every flagged version across the repository's store that {@code disclosure} discloses - the flat listing an
     * operator surface renders, with each mark routed through the servable-name enumeration seam so a withheld
     * version's mark is not disclosed on the served view (plan &sect;8 Q3). The {@link Disclosure} is supplied by the
     * caller (the {@code lifecycle-web} adapter passes
     * {@code (coordinate, version) -> inventory.disclosableDisplay(coordinate + ":" + version, HIDE_WITHHELD)}) so this
     * helper stays inventory-free; a mark the seam classifies held is dropped, every other mark is kept.
     */
    public static List<Entry> all(ArtifactStore store, Disclosure disclosure) throws IOException {
        List<Entry> disclosed = new ArrayList<>();
        String cursor = null;
        while (true) {
            Traversal.Result result = MARKS.walk(store, ROOT, cursor, key -> mark(store, key, disclosure, disclosed));
            if (result.exhausted()) {
                return disclosed;
            }
            cursor = result.cursor().orElseThrow();
        }
    }

    /** The bounds the mark listing descends {@code lifecycle/} under. An operator surface that silently dropped marks
     *  past a cap would show a yanked version as live, so the entry cap is only the per-call continuation
     *  {@link #all} follows to exhaustion; the binding bound is the step budget - one {@link ArtifactStore#exists}
     *  probe per opened node - which raises a named {@link build.jenesis.repository.walk.TraversalException} rather
     *  than answering short, and the depth ceiling stays the store's own {@link ArtifactStore#MAX_SEGMENTS} write
     *  cap, so a key deeper than the store would accept fails by name where the previous recursion silently stopped
     *  at 64 levels. */
    private static final PagedTreeWalk MARKS = PagedTreeWalk.bounded().steps(1_000_000);

    /** Decode one stored mark and add it to {@code disclosed} when the seam discloses it. The key's last segment is the
     *  version and everything between the root and it is the (possibly multi-segment) coordinate, exactly the pairing
     *  {@link #key} writes. */
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

    /**
     * Mark a coordinate/version with the flag, overwriting any previous mark with a bounded compare-and-set retry so
     * a concurrent re-mark of the same version resolves last-writer-wins rather than one writer silently dropping its
     * update. A traversal-unsafe coordinate or version is refused (it must never key a write outside {@code lifecycle/}).
     */
    public static void mark(ArtifactStore store, String coordinate, String version, Flag flag) throws IOException {
        Objects.requireNonNull(flag, "flag");
        if (!safeCoordinate(coordinate)) {
            throw new IllegalArgumentException("Not a traversal-safe coordinate: " + coordinate);
        }
        if (!ArtifactStore.safeSegment(version)) {
            throw new IllegalArgumentException("Not a traversal-safe version: " + version);
        }
        byte[] content = encode(flag);
        String key = key(coordinate, version);
        for (int attempt = 0; attempt < Retries.COMPARE_AND_SET; attempt++) {
            Object token = store.readVersioned(key).map(ArtifactStore.Versioned::token).orElse(null);
            if (store.writeVersioned(key, content, token)) {
                Publication.notifyMarked(subject(coordinate, version), store);
                return;
            }
            Retries.backoff(attempt);
        }
        throw new IOException("Could not write lifecycle flag for " + coordinate + " " + version
                + " after repeated version conflicts");
    }

    /** Clear a coordinate/version's mark; {@code true} when one was present, {@code false} when there was nothing to
     *  clear (or the names are not traversal-safe). */
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


    /** A coordinate may carry {@code /} (an npm scope, a Cargo {@code <registry>/<crate>}), so it is validated
     *  segment-by-segment: every {@code /}-delimited part is a safe segment and none is empty ({@code //}). */
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
