package build.jenesis.repository.format.testkit;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * How one {@link RepositoryFormat} registers with the shared {@link FormatContract}: a fixture supplies the realistic
 * corpus - one published artifact, one holdable version, one generated index, one upstream - and the kit then drives
 * <em>every</em> contract property over it. A format is covered by writing a fixture, never by copying assertions into
 * a fifth hand-written per-format suite, which is exactly how the four free layouts arrived at four different ideas of
 * what a {@code HEAD} or a traversal-shaped path means.
 *
 * <p>Four declarations carry the fixture's honesty, and each is machine-checked rather than trusted:
 * <ul>
 *   <li>{@link #providerClass()} keys the fixture to a statically declared {@code provides ... with ...} class, so the
 *       census can prove no declared or runtime-discovered format is unfixtured (and no fixture names a dead one);</li>
 *   <li>{@link #serving()} is discovered through the SPI by {@link RepositoryFormat#name() name}, never named as a
 *       concrete class, so a fixture exercises the format the way the dispatcher reaches it;</li>
 *   <li>{@link #namespaces()} states where this format is allowed to write. The traversal leg asserts that after
 *       every probe vector the store holds <em>nothing</em> outside them, which is what makes "the traversal was
 *       refused" a statement about the store rather than about a status code;</li>
 *   <li>{@link #unsupported()} names the properties this format genuinely does not have (a layout with no enumeration
 *       surface, a protocol that advertises no upstream digest), each with a mandatory reason. It is a statement about
 *       the format's protocol, never about its implementation quality: a property no fixture anywhere exercises is a
 *       hole the census fails on.</li>
 * </ul>
 *
 * <p>A fixture seeds into the store it is handed and holds no state of its own between checks: every check gets a
 * fresh, empty store, so absence assertions mean what they say.
 */
public interface FormatFixture {

    /** The {@link RepositoryFormat#name() format name} this fixture drives ({@code maven}, {@code oci}, ...) - the
     *  same string an operator writes as {@code jenreg.<name>}. */
    String format();

    /** The fully qualified {@code RepositoryFormat} implementation class this fixture covers, as the census parses it
     *  out of the format module's {@code provides ... with ...} clause. */
    String providerClass();

    /** The installed format, discovered through the SPI by {@link #format() name} exactly as the dispatcher discovers
     *  it - so a fixture works through the SPI alone and never against a locally constructed instance. */
    default RepositoryFormat serving() {
        return RepositoryFormat.installed(format()).orElseThrow(() -> new IllegalStateException(
                "the '" + format() + "' format is not on the module path, so its contract cannot run"));
    }

    /** The store key prefixes this format may write under, relative to the already-scoped store it is handed
     *  ({@code publish/maven}, {@code oci}, {@code blobs}, ...). Everything else is an escape. */
    List<String> namespaces();

    /** Publish one realistic artifact through the format's own write path and name the request path that serves those
     *  exact bytes back. */
    Published publish(ArtifactStore store, byte[] body) throws IOException;

    /** What {@link #publish} laid down: the request path a {@code GET} serves the body from, and the content hash of
     *  the {@code blobs/<hash>} object holding it (the key the {@code HEAD} leg seals). */
    record Published(String servedPath, String contentHash) {

        public Published {
            Objects.requireNonNull(servedPath, "servedPath");
            Objects.requireNonNull(contentHash, "contentHash");
        }
    }

    /** Splice a relative fragment into this format's namespace, yielding a single request path this format
     *  {@link RepositoryFormat#handles claims}. The kit passes it each shared {@link TraversalVectors} vector, and a
     *  benign name when it needs a path that is well-formed but unpublished; this one line per fixture is what keeps
     *  the vectors themselves shared rather than re-invented per format. */
    String probe(String vector);

    /** The body a probing {@code PUT} carries - a manifest for a format that only accepts one, plain bytes elsewhere. */
    default byte[] probeBody() {
        return "t202a-probe".getBytes(StandardCharsets.UTF_8);
    }

    /** A deployment setting this format reads off the exchange, or {@code null} - the seam the Maven metadata
     *  computation is switched on through. */
    default String setting(String key) {
        return null;
    }

    /**
     * Seed a holdable version and name every enumeration surface that lists it, or empty when this format publishes no
     * enumeration surface at all. The kit asserts the version is listed everywhere before the hold and nowhere after,
     * and that its served path answers {@code 404} once held.
     */
    default Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        return Optional.empty();
    }

    /** A holdable version: the request path it serves from, the enumeration surfaces that must list and then omit it,
     *  and how this format's namespace retracts it ({@link ContractHold} for a {@code publish/} pointer, a
     *  {@code withheld/<hash>} marker for a {@code blobs/} one). */
    record Enumerated(String heldPath, List<Probe> probes, Hold hold) {

        public Enumerated {
            Objects.requireNonNull(heldPath, "heldPath");
            probes = List.copyOf(probes);
            Objects.requireNonNull(hold, "hold");
        }
    }

    /** One enumeration surface and the token that names the holdable version in its rendered body. */
    record Probe(String path, String token) {

        public Probe {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(token, "token");
        }
    }

    /** Places this format's hold on the seeded version. Idempotent, as every hold writer's converge pass is. */
    @FunctionalInterface
    interface Hold {
        void apply(ArtifactStore store) throws IOException;
    }

    /**
     * Seed a generated (rendered-on-read) document and name a change to the stored state that must change its bytes,
     * or empty when this format generates nothing. The kit asserts the document arrives buffered, is byte-identical
     * across two serves of unchanged state, revalidates to {@code 304}, and stops revalidating once the state moves.
     */
    default Optional<Index> index(ArtifactStore store) throws IOException {
        return Optional.empty();
    }

    /** A generated document: the request path that renders it and a change to the stored state it must reflect. */
    record Index(String path, Change change) {

        public Index {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(change, "change");
        }
    }

    /** A change to the stored state a generated document renders. */
    @FunctionalInterface
    interface Change {
        void apply(ArtifactStore store) throws IOException;
    }

    /**
     * The pull-through leg over a body the upstream generates as it is read, or empty when this format is not a
     * {@link ProxyFormat}. The fixture builds the request path (which for a content-addressed protocol depends on the
     * body's digest) and a fetcher answering that body plus whatever integrity sidecar its protocol advertises.
     */
    default Optional<Upstream> upstream(GeneratedBody body) throws IOException {
        return Optional.empty();
    }

    /**
     * The same leg with the upstream's advertised digest deliberately disagreeing with the bytes it serves, or empty
     * when this format's protocol advertises no digest to verify against - a plain file mirror has nothing to check,
     * and the kit refuses to fabricate one.
     */
    default Optional<Upstream> tampered(GeneratedBody body) throws IOException {
        return Optional.empty();
    }

    /** One pull-through leg: the request path that misses locally, the upstream root, and the fetcher standing in for
     *  it. */
    record Upstream(String requestPath, URI root, ProxyFormat.Fetcher fetcher) {

        public Upstream {
            Objects.requireNonNull(requestPath, "requestPath");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(fetcher, "fetcher");
        }
    }

    /** The contract properties this format's protocol does not have, each mapped to the reason and to where the
     *  property <em>is</em> proven instead. Empty by default: an exclusion is a deliberate, reviewable statement. */
    default Map<FormatContract.Property, String> unsupported() {
        return Map.of();
    }
}
