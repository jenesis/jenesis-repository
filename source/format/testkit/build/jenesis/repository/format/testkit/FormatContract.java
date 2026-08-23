package build.jenesis.repository.format.testkit;

import module java.base;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;

/**
 * The executable {@link RepositoryFormat} / {@link ProxyFormat} / {@link ArtifactLayout} contract: one parameterized
 * body of checks that every format runs through a {@link FormatFixture}, so a serve-side property is stated once and
 * proven N times instead of being re-asserted - and quietly re-interpreted - in N hand-written per-format suites.
 * Each {@link Property} names one documented contract clause; {@link #checks(FormatFixture)} binds them to a fixture,
 * skipping only the properties that format's <em>protocol</em> genuinely does not have (with a reason).
 *
 * <p>Assertion-library-free on purpose: a check throws {@link AssertionError} naming the format, the property and the
 * expectation, so this module stays {@code java.base} + the format SPI and the downstream distribution can require it
 * for its own fixtures exactly as it already requires the store testkit. The JUnit driver lives under {@code test/**}
 * and turns each check into one dynamic test.
 *
 * <p>Three checks are deliberately built on proofs rather than on observations, because the observation a naive check
 * makes is also what a broken implementation produces:
 * <ul>
 *   <li>{@link Property#HEAD_ANSWERS_FROM_METADATA} runs the {@code HEAD} against a {@link WitnessStore} that
 *       <em>seals</em> the artifact's blob, so a format that streams the body and discards it fails by opening a key
 *       it was refused - and the same check proves the seal bites by watching a {@code GET} trip it;</li>
 *   <li>{@link Property#PROXY_STREAMS_UPSTREAM_BODY} feeds a {@link GeneratedBody} that never exists as an array and
 *       asks the witness how much of it the format had already read when it handed the stream to the store. Zero is
 *       the streaming answer; a buffered implementation shows the whole length. No timing, no heap sampling;</li>
 *   <li>{@link Property#REQUEST_PATH_TRAVERSAL_REFUSED} judges the refusal by walking the store afterwards and
 *       requiring every key to lie inside the fixture's declared namespaces, so "refused" is a statement about what
 *       was stored rather than about a status code a format could answer while still having written somewhere.</li>
 * </ul>
 *
 * <h2>Clauses this kit discharges</h2>
 * so a clause named here leaves the principle-checkup checklist. They restate, in machine-readable form,
 * exactly the clause numbers each {@link Property}'s javadoc already cites - no more: {@code RepositoryFormat} 2 and
 * 4 ({@code PUBLISH_SERVES_EXACT_BYTES}, {@code HEAD_ANSWERS_FROM_METADATA}), 6
 * ({@code REQUEST_PATH_TRAVERSAL_REFUSED}), 7 ({@code WITHHELD_VERSION_LEAVES_EVERY_ENUMERATION}) and 12
 * ({@code GENERATED_INDEX_IS_REVALIDATABLE}); {@code ArtifactLayout} 3; {@code ProxyFormat} 3 and 5.
 *
 * <p>What it deliberately does <b>not</b> claim is {@code ProxyFormat} clause 9 - an upstream-supplied name is as
 * untrusted as a client-supplied one. This kit drives a scripted upstream it controls, so it can prove a leg refuses
 * a hostile <em>name</em>; it holds no reference for whether a leg's outbound <em>URL</em> handling is right, which
 * is a per-format question against that ecosystem's protocol and is the earlier first checkup theme.
 *
 * @jenesis.covers build.jenesis.repository.format.RepositoryFormat 2, 4, 6, 7, 12
 * @jenesis.covers build.jenesis.repository.format.ArtifactLayout 3
 * @jenesis.covers build.jenesis.repository.format.ProxyFormat 3, 5
 */
public final class FormatContract {

    /** The artifact body the serve-side checks publish: large enough that a truncated or mis-offset copy is visible,
     *  small enough to compare byte for byte. */
    private static final int ARTIFACT_BYTES = 4096;

    /** The upstream body the streaming leg pulls: far past any plausible buffer, so a materialising implementation is
     *  unmistakable, and cheap enough to stream through a temporary directory in a unit test. */
    private static final long STREAMED_BYTES = 5L << 20;

    /** The largest body the streaming leg tolerates through a small-object {@code byte[]} write - a generous ceiling
     *  for an index or a manifest, far below the artifact. */
    private static final long SMALL_OBJECT_BYTES = 1L << 20;

    /**
     * One documented contract clause. The enum is the kit's vocabulary: a fixture excludes a property by name and
     * reason, and the census fails on a property no fixture anywhere exercises, so the list can never grow a clause
     * that is asserted nowhere.
     */
    public enum Property {
        /** A published artifact serves back byte for byte, repeatably, from its content-addressed blob
         *  ({@code RepositoryFormat} clauses 2 and 4). */
        PUBLISH_SERVES_EXACT_BYTES,
        /** A {@code HEAD} answers {@code 200} with the artifact's length taken from the store's metadata and
         *  <em>without opening the blob</em>; an absent path answers {@code 404} (clause 4). */
        HEAD_ANSWERS_FROM_METADATA,
        /** Every shared traversal probe vector is refused at the request seam: a decoded {@code .}/{@code ..} path is
         *  a client error, an encoded one stays a literal name, an over-shaped one is refused by the store's key
         *  screen - and after all of them the store holds nothing outside this format's namespaces (clause 6). */
        REQUEST_PATH_TRAVERSAL_REFUSED,
        /** The coordinate seam refuses the same shapes: {@link ArtifactLayout#paths} never composes a traversal-shaped
         *  request path out of a hostile coordinate or version, and a published artifact's coordinate round-trips
         *  back to the path it occupies ({@code ArtifactLayout} clause 3). */
        COORDINATE_TRAVERSAL_REFUSED,
        /** A version listed by every enumeration surface leaves all of them the moment it is held, and its served
         *  path answers {@code 404} - one hold, no surface left behind (clause 7). */
        WITHHELD_VERSION_LEAVES_EVERY_ENUMERATION,
        /** A proxied body whose advertised upstream digest disagrees with its bytes is refused and nothing is cached,
         *  while the honest body is accepted ({@code ProxyFormat} clause 5). */
        PROXY_VERIFIES_UPSTREAM_INTEGRITY,
        /** A proxied artifact goes from the network into the content-addressed store without being materialised, and
         *  arrives byte-exact ({@code ProxyFormat} clause 3). */
        PROXY_STREAMS_UPSTREAM_BODY,
        /** A generated document arrives through the buffered response overload, is a pure function of the stored state
         *  it renders, revalidates to {@code 304} against its own validator, and stops doing so once the state moves
         *  (clause 12). */
        GENERATED_INDEX_IS_REVALIDATABLE,
        /** Every absolute URL a generated document emits back at this deployment carries the scheme the request
         *  arrived on. A document that tells a client to fetch over {@code http} from a deployment serving TLS
         *  downgrades every credential the client attaches to that URL - and a cache may hand that document to
         *  others (clause 12, and the request-base rule the formats share). */
        GENERATED_INDEX_CARRIES_THE_REQUEST_SCHEME
    }

    /** One named, independently runnable contract check. */
    public record Check(Property property, String name, Body body) {

        public Check {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
        }
    }

    /** The body of a {@link Check}, run against a fixture and a fresh, empty, already-scoped store. */
    @FunctionalInterface
    public interface Body {
        void run(FormatFixture fixture, ArtifactStore store) throws Exception;
    }

    private FormatContract() {
    }

    /**
     * Every contract check, in declaration order, independent of any fixture. The list is the contract: a format runs
     * all of it or names - with a reason - the properties its protocol does not have.
     */
    public static List<Check> checks() {
        return List.of(
                new Check(Property.PUBLISH_SERVES_EXACT_BYTES,
                        "a published artifact serves back byte for byte from its content-addressed blob",
                        FormatContract::publishServesExactBytes),
                new Check(Property.HEAD_ANSWERS_FROM_METADATA,
                        "a HEAD answers from the store's metadata without opening the artifact",
                        FormatContract::headAnswersFromMetadata),
                new Check(Property.REQUEST_PATH_TRAVERSAL_REFUSED,
                        "every shared traversal vector is refused and nothing lands outside the namespace",
                        FormatContract::requestPathTraversalRefused),
                new Check(Property.COORDINATE_TRAVERSAL_REFUSED,
                        "a hostile coordinate maps nowhere and a real one round-trips to its own path",
                        FormatContract::coordinateTraversalRefused),
                new Check(Property.WITHHELD_VERSION_LEAVES_EVERY_ENUMERATION,
                        "a held version leaves every enumeration surface and 404s where it served",
                        FormatContract::withheldVersionLeavesEveryEnumeration),
                new Check(Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY,
                        "an upstream body that fails its advertised digest is refused and never cached",
                        FormatContract::proxyVerifiesUpstreamIntegrity),
                new Check(Property.PROXY_STREAMS_UPSTREAM_BODY,
                        "a proxied artifact is never materialised and arrives byte-exact",
                        FormatContract::proxyStreamsUpstreamBody),
                new Check(Property.GENERATED_INDEX_IS_REVALIDATABLE,
                        "a generated document is buffered, deterministic and conditionally revalidatable",
                        FormatContract::generatedIndexIsRevalidatable),
                new Check(Property.GENERATED_INDEX_CARRIES_THE_REQUEST_SCHEME,
                        "a generated document's absolute URLs carry the scheme the request arrived on",
                        FormatContract::generatedIndexCarriesTheRequestScheme));
    }

    /**
     * The checks {@code fixture} runs: every check whose property the fixture does not exclude. Excluding a property
     * without a reason fails here rather than silently shrinking the suite.
     */
    public static List<Check> checks(FormatFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        fixture.unsupported().forEach((property, reason) -> {
            Objects.requireNonNull(property, "unsupported property");
            if (reason == null || reason.isBlank()) {
                throw new AssertionError("The '" + fixture.format() + "' fixture excludes " + property
                        + " without a reason; an exclusion must say which part of the format's protocol does not have "
                        + "the property, and where the property is proven instead.");
            }
        });
        return checks().stream().filter(check -> !fixture.unsupported().containsKey(check.property())).toList();
    }

    // --- the contract ------------------------------------------------------------------------------------------

    private static void publishServesExactBytes(FormatFixture fixture, ArtifactStore store) throws Exception {
        byte[] body = ramp(ARTIFACT_BYTES);
        FormatFixture.Published published = fixture.publish(store, body);

        isTrue(fixture.serving().handles(published.servedPath()), fixture,
                "the format claims the path its own publish laid the artifact out at ("
                        + published.servedPath() + ")");
        isTrue(store.exists("blobs/" + published.contentHash()), fixture,
                "the artifact is stored content-addressed at blobs/" + published.contentHash());

        ContractExchange get = get(fixture, published.servedPath());
        fixture.serving().handle(get, store);
        equal(get.status(), 200, fixture, "a GET of the published path serves it");
        equal(get.responseBytes(), body, fixture, "the served bytes are the published bytes, byte for byte");

        // ... and again: a read renders stored state, so a second GET of unchanged state is identical. A format whose
        // serve mutated something (a counter written into the served document, a re-derived timestamp) diverges here.
        ContractExchange again = get(fixture, published.servedPath());
        fixture.serving().handle(again, store);
        equal(again.status(), 200, fixture, "a repeated GET still serves");
        equal(again.responseBytes(), body, fixture, "a repeated GET serves identical bytes");

        // A path the format claims but nothing published is absence, not an empty 200: a resolver reads a zero-byte
        // 200 as a real, empty artifact and caches it.
        ContractExchange missing = get(fixture, fixture.probe("t202a-never-published/absent.bin"));
        fixture.serving().handle(missing, store);
        isTrue(missing.status() >= 400, fixture,
                "an unpublished path this format claims answers a client error, never an empty 200 (was "
                        + missing.status() + ")");
    }

    private static void headAnswersFromMetadata(FormatFixture fixture, ArtifactStore store) throws Exception {
        byte[] body = ramp(ARTIFACT_BYTES);
        FormatFixture.Published published = fixture.publish(store, body);
        String blob = "blobs/" + published.contentHash();

        // The proof: the artifact's bytes are made unreadable. exists/size/pointer reads still answer, so a format
        // that answers a HEAD from the store's metadata is unaffected, while one that opens the blob to learn its
        // length (or streams it and discards the body) fails by touching a key the witness refuses.
        WitnessStore sealed = WitnessStore.over(store).seal(blob);
        ContractExchange head = head(fixture, published.servedPath());
        fixture.serving().handle(head, sealed);

        equal(head.status(), 200, fixture, "a HEAD of the published path answers 200");
        equal(head.responseLength(), 0L, fixture, "a HEAD writes no body");
        equal(head.responseHeader("Content-Length"), Long.toString(body.length), fixture,
                "a HEAD advertises the artifact's length, read from the store's metadata - a client sizing an "
                        + "artifact before pulling it must get the same answer a GET would give");

        // ... and the seal is not vacuous: the same store really does refuse the blob, so the HEAD above passed
        // because it never needed the bytes rather than because nothing was sealed.
        boolean tripped = false;
        try {
            ContractExchange get = get(fixture, published.servedPath());
            fixture.serving().handle(get, sealed);
        } catch (AssertionError expected) {
            tripped = expected.getMessage() != null && expected.getMessage().contains(blob);
        }
        isTrue(tripped, fixture, "the sealed blob is genuinely unreadable - a GET through the same store must trip "
                + "the witness, or the HEAD leg above proves nothing");

        // Absence is a status, never a 200 with no length.
        ContractExchange missing = head(fixture, fixture.probe("t202a-never-published/absent.bin"));
        fixture.serving().handle(missing, store);
        isTrue(missing.status() >= 400, fixture,
                "a HEAD of an unpublished path answers a client error (was " + missing.status() + ")");
    }

    private static void requestPathTraversalRefused(FormatFixture fixture, ArtifactStore store) throws Exception {
        for (TraversalVectors.Vector vector : TraversalVectors.all()) {
            String path = fixture.probe(vector.relative());
            isTrue(fixture.serving().handles(path), fixture,
                    "the '" + vector.id() + "' probe must land on a path this format claims, or it probes nothing ("
                            + path + ")");
            probe(fixture, store, vector, path, "GET");
            probe(fixture, store, vector, path, "HEAD");
            probe(fixture, store, vector, path, "PUT");
        }

        // The refusal that matters is not the status but what is on disk afterwards: every key the probes left behind
        // must lie inside a namespace this format declared. A traversal that "was refused" but wrote one level up
        // fails here, and it fails wherever the escape landed rather than only where the check thought to look.
        List<String> escaped = new ArrayList<>();
        walk(store, "", key -> {
            // A format's stored listings live under the shared listing/ space, keyed by the format's own names -
            // the same names its blob namespaces carry - so a probe that lands there landed inside the format.
            String owned = key.startsWith(StoredListing.ROOT) ? key.substring(StoredListing.ROOT.length()) : key;
            if (fixture.namespaces().stream().noneMatch(
                    namespace -> owned.equals(namespace) || owned.startsWith(namespace + "/")
                            || (key.startsWith(StoredListing.ROOT) && owned.startsWith(namespace.substring(
                                    namespace.indexOf('/') + 1) + "/")))) {
                escaped.add(key);
            }
        });
        if (!escaped.isEmpty()) {
            throw failure(fixture, "after the traversal probes the store holds " + escaped.size() + " key(s) outside "
                    + "this format's declared namespaces " + fixture.namespaces() + " - a probe escaped: " + escaped);
        }
    }

    private static void probe(FormatFixture fixture, ArtifactStore store, TraversalVectors.Vector vector,
                              String path, String method) throws Exception {
        ContractExchange exchange = method.equals("PUT")
                ? ContractExchange.of(method, path, fixture.probeBody()).settings(fixture::setting)
                : ContractExchange.of(method, path).settings(fixture::setting);
        RuntimeException unchecked = null;
        IOException checked = null;
        try {
            fixture.serving().handle(exchange, store);
        } catch (IllegalArgumentException refused) {
            unchecked = refused;
        } catch (IOException refused) {
            checked = refused;
        }
        switch (vector.kind()) {
            case DECODED -> {
                if (unchecked != null || checked != null) {
                    throw failure(fixture, "the '" + vector.id() + "' vector (" + method + " " + path + ") escaped as "
                            + (unchecked != null ? unchecked : checked) + ". A path carrying a . or .. segment "
                            + "addresses nothing in this format's namespace, so it is the format's own 404 - an "
                            + "exception out of handle() is an unmapped 500 where the truth is 'no such artifact'.");
                }
                isTrue(exchange.status() >= 400 && exchange.status() < 500, fixture,
                        "the '" + vector.id() + "' vector (" + method + " " + path + ") is refused with a client "
                                + "error, never served (was " + exchange.status() + ")");
            }
            case ENCODED -> {
                // A format never decodes its own path, so this is a literal name and any status is legal; what must
                // not happen is that it becomes a traversal one layer below the dispatcher that already decoded. The
                // namespace walk after the loop is the assertion.
                if (unchecked != null) {
                    throw failure(fixture, "the '" + vector.id() + "' vector (" + method + " " + path + ") escaped as "
                            + unchecked + ". A percent-encoded traversal is an ordinary literal name here; refusing it "
                            + "with an exception means something below decoded it.");
                }
            }
            case SHAPE_CAP -> {
                // No traversal to screen: the store's own key cap is the refusal, and it throws. Either shape is
                // correct - what is not correct is a 2xx, which would mean the cap did not bind at all.
                isTrue(unchecked != null || checked != null || exchange.status() < 200 || exchange.status() >= 300,
                        fixture, "the '" + vector.id() + "' vector (" + method + " " + path + ") must be refused - "
                                + "by the format, or by the store's key cap - but it answered "
                                + exchange.status());
            }
        }
    }

    private static void coordinateTraversalRefused(FormatFixture fixture, ArtifactStore store) throws Exception {
        if (!(fixture.serving() instanceof ArtifactLayout layout)) {
            throw failure(fixture, "this format implements no ArtifactLayout, so it has no coordinate seam. Exclude "
                    + Property.COORDINATE_TRAVERSAL_REFUSED + " with a reason naming where its client-supplied names "
                    + "are screened instead.");
        }

        // Non-vacuity first, and a real property in its own right: a published artifact's coordinate must map back to
        // the folder it occupies, so the guard below is proven to be screening a seam that otherwise answers.
        byte[] body = ramp(ARTIFACT_BYTES);
        FormatFixture.Published published = fixture.publish(store, body);
        ArtifactDescriptor descriptor = layout.describe(published.servedPath()).orElseThrow(() -> failure(fixture,
                "describe(" + published.servedPath() + ") resolves the published artifact's coordinate"));
        notNull(descriptor.coordinate(), fixture, "the published artifact describes to a coordinate");
        notNull(descriptor.version(), fixture, "the published artifact describes to a version");
        List<String> round = layout.paths(descriptor.coordinate(), descriptor.version());
        isTrue(!round.isEmpty(), fixture, "the coordinate maps back to the paths its version occupies");
        isTrue(published.servedPath().startsWith(round.getFirst() + "/"), fixture,
                "the primary path a coordinate maps to is the folder its artifact was published under (expected "
                        + published.servedPath() + " to sit under " + round.getFirst() + ")");

        // ... and the same seam refuses the same shapes. These arrive as coordinates and versions rather than as
        // request paths - from a published name, an advisory feed, a console form - and what comes back is handed to
        // eviction, which unpublishes and DELETES under it. The hostile part is spliced into the format's own real
        // coordinate as well as offered alone, so a layout whose coordinate has internal structure (a Maven
        // {@code group:artifact}) is probed in the part that actually becomes a path segment, not only in the whole.
        for (String hostile : HOSTILE_PARTS) {
            for (String coordinate : hostileCoordinates(descriptor.coordinate(), hostile)) {
                for (String version : List.of(descriptor.version(), hostile)) {
                    List<String> named = List.of("coordinate='" + coordinate + "', version='" + version + "'");
                    traversalFreePaths(fixture, layout.paths(coordinate, version), named);
                    traversalFreePaths(fixture, layout.paths(coordinate, version, store), named);
                }
            }
        }
    }

    /** The name parts a coordinate or a version must not be able to smuggle into a composed request path. */
    private static final List<String> HOSTILE_PARTS =
            List.of("..", ".", "../..", "a/../b", "a/b", "", "..\\b", "/");

    /** {@code hostile} offered as the whole coordinate and spliced into the last structural component of the real
     *  one, so a coordinate whose parts are separated ({@code group:artifact}) is probed part by part. */
    private static List<String> hostileCoordinates(String real, String hostile) {
        List<String> coordinates = new ArrayList<>();
        coordinates.add(hostile);
        coordinates.add(real);
        int colon = real.lastIndexOf(':');
        if (colon >= 0) {
            coordinates.add(real.substring(0, colon + 1) + hostile);
            coordinates.add(hostile + real.substring(colon));
        }
        return coordinates;
    }

    private static void traversalFreePaths(FormatFixture fixture, List<String> paths, List<String> coordinate) {
        notNull(paths, fixture, "paths(" + coordinate + ") answers a list, never null");
        for (String path : paths) {
            if (!ArtifactStore.traversalFree(path)) {
                throw failure(fixture, "paths(" + coordinate + ") composed the traversal-shaped request path '" + path
                        + "'. These paths are handed to eviction, which unpublishes and deletes under them, so a "
                        + "coordinate that is not addressable must map NOWHERE (an empty list - the answer this "
                        + "method already documents for a coordinate that maps nowhere).");
            }
        }
    }

    private static void withheldVersionLeavesEveryEnumeration(FormatFixture fixture, ArtifactStore store)
            throws Exception {
        FormatFixture.Enumerated enumerated = fixture.enumerated(store).orElseThrow(() -> failure(fixture,
                "this fixture seeds no enumeration surface. Either seed one, or exclude "
                        + Property.WITHHELD_VERSION_LEAVES_EVERY_ENUMERATION + " with a reason saying the format "
                        + "publishes none."));
        isTrue(!enumerated.probes().isEmpty(), fixture,
                "an enumeration leg must name at least one surface, or it asserts nothing");

        for (FormatFixture.Probe probe : enumerated.probes()) {
            String before = body(fixture, store, probe.path());
            isTrue(before.contains(probe.token()), fixture,
                    "before the hold, " + probe.path() + " lists '" + probe.token() + "' - otherwise the check below "
                            + "would pass over a version that was never disclosed in the first place");
        }
        ContractExchange served = get(fixture, enumerated.heldPath());
        fixture.serving().handle(served, store);
        equal(served.status(), 200, fixture, "before the hold, " + enumerated.heldPath() + " serves");

        enumerated.hold().apply(store);

        for (FormatFixture.Probe probe : enumerated.probes()) {
            String after = body(fixture, store, probe.path());
            if (after.contains(probe.token())) {
                throw failure(fixture, "after the hold, " + probe.path() + " still discloses '" + probe.token()
                        + "'. A listing may never name a version whose GET answers 404 - the existence of a held "
                        + "artifact is itself a disclosure.");
            }
        }
        ContractExchange held = get(fixture, enumerated.heldPath());
        fixture.serving().handle(held, store);
        equal(held.status(), 404, fixture, "a held version's served path answers 404");

        // A hold writer's converge pass re-applies its hold, so re-holding is a no-op rather than a second, different
        // state - the enumeration must not come back.
        enumerated.hold().apply(store);
        for (FormatFixture.Probe probe : enumerated.probes()) {
            isTrue(!body(fixture, store, probe.path()).contains(probe.token()), fixture,
                    "a re-applied hold is idempotent: " + probe.path() + " still omits '" + probe.token() + "'");
        }
    }

    private static void proxyVerifiesUpstreamIntegrity(FormatFixture fixture, ArtifactStore store) throws Exception {
        ProxyFormat proxy = proxying(fixture, Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY);
        GeneratedBody body = GeneratedBody.of(ARTIFACT_BYTES);

        // The positive control first, in its own subspace: the fixture's upstream really is answerable, so a refusal
        // below is the digest check firing rather than a fetcher that never worked.
        ArtifactStore honestSpace = store.scope("honest");
        FormatFixture.Upstream honest = fixture.upstream(body).orElseThrow(() -> failure(fixture,
                "a format that verifies upstream integrity must also supply the honest leg it verifies against"));
        ContractExchange accepted = get(fixture, honest.requestPath());
        isTrue(proxy.proxy(accepted, honestSpace, honest.root(), honest.fetcher()), fixture,
                "the honest upstream body is served through the proxy leg");
        equal(accepted.status(), 200, fixture, "the honest proxied artifact serves 200");
        equal(accepted.responseSha256(), body.sha256(), fixture, "the honest proxied artifact serves its own bytes");

        // ... and now the same leg with the upstream's advertised digest disagreeing with the bytes it hands over.
        ArtifactStore tamperedSpace = store.scope("tampered");
        body.rewind();
        FormatFixture.Upstream tampered = fixture.tampered(body).orElseThrow(() -> failure(fixture,
                "this fixture supplies no digest-mismatching leg. Either supply one, or exclude "
                        + Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY + " with a reason saying the protocol advertises "
                        + "no digest to verify against."));
        ContractExchange refused = get(fixture, tampered.requestPath());
        boolean served = proxy.proxy(refused, tamperedSpace, tampered.root(), tampered.fetcher());
        if (served && refused.status() >= 200 && refused.status() < 300) {
            throw failure(fixture, "a body that fails its advertised upstream digest was served (" + refused.status()
                    + "). A mismatch is a refusal: nothing is linked, nothing is served, and the local 404 stands so "
                    + "a later pull re-hits the upstream.");
        }

        // Nothing may have been cached under the requested coordinate either, or the next plain GET would serve the
        // corrupted bytes without ever consulting the upstream again.
        ContractExchange after = get(fixture, tampered.requestPath());
        fixture.serving().handle(after, tamperedSpace);
        equal(after.status(), 404, fixture,
                "after a refused proxy fetch the path is still a local miss - a rejected body is never left cached");
    }

    private static void proxyStreamsUpstreamBody(FormatFixture fixture, ArtifactStore store) throws Exception {
        ProxyFormat proxy = proxying(fixture, Property.PROXY_STREAMS_UPSTREAM_BODY);
        GeneratedBody body = GeneratedBody.of(STREAMED_BYTES);
        FormatFixture.Upstream upstream = fixture.upstream(body).orElseThrow(() -> failure(fixture,
                "this fixture supplies no pull-through leg. Either supply one, or exclude "
                        + Property.PROXY_STREAMS_UPSTREAM_BODY + " with a reason saying the format is not a "
                        + "ProxyFormat."));

        WitnessStore witness = WitnessStore.over(store).watch(body).bufferedWriteCap(SMALL_OBJECT_BYTES);
        ContractExchange fetched = get(fixture, upstream.requestPath());
        isTrue(proxy.proxy(fetched, witness, upstream.root(), upstream.fetcher()), fixture,
                "the generated upstream body is served through the proxy leg");
        equal(fetched.status(), 200, fixture, "the proxied artifact serves 200");

        isTrue(witness.blobWrites() > 0, fixture,
                "the proxied artifact reaches storage through the content-addressed streaming write");
        long produced = witness.producedBeforeStore().orElseThrow(() -> failure(fixture,
                "no content-addressed write was witnessed, so the streaming tripwire never armed"));
        if (produced != 0L) {
            throw failure(fixture, "the format had already read " + produced + " of the upstream body's "
                    + body.length() + " bytes when it handed the stream to the store. An artifact goes from the "
                    + "network to storage unread (§1) - anything else means it was materialised first, and a "
                    + "multi-gigabyte pull would carry the whole thing in heap.");
        }

        // ... and the bytes that landed are the bytes upstream had: content-addressed, so the store's own key is the
        // proof, and the serve streams them back byte-exact without either side buffering.
        isTrue(store.exists("blobs/" + body.sha256()), fixture,
                "the streamed body lands at its own content address blobs/" + body.sha256());
        equal(store.size("blobs/" + body.sha256()), body.length(), fixture, "the whole body landed, not a prefix");
        equal(fetched.responseLength(), body.length(), fixture, "the proxy serves the whole artifact through");
        equal(fetched.responseSha256(), body.sha256(), fixture, "the proxy serves the upstream's exact bytes");
    }

    /**
     * A generated document rendered over TLS emits no {@code http://} URL back at this deployment.
     *
     * <p>Five formats each carried a private {@code baseUrl(exchange)} that read {@code X-Forwarded-Proto} and fell
     * back to {@code http}, so a deployment serving TLS told clients to fetch tarballs, sparse-index entries and
     * service indexes over cleartext. They are one site now, and this is what holds them there: nothing stops a
     * sixth format composing its own base and reintroducing the defect one ecosystem at a time, which is exactly
     * how it reached five.
     *
     * <p>It asks only about URLs pointing back at <em>this</em> deployment. An index legitimately names upstream
     * addresses it does not own - a mirror, a vendor CDN, a checksum database - and their scheme is theirs. The
     * host the exchange was asked on is what separates the two.
     *
     * <p>A format that emits no absolute URL at all passes without asserting anything, deliberately: the property
     * is conditional by nature ("if you emit one, it carries the scheme"), and demanding an exclusion from every
     * format that simply has no absolute URLs would make the exclusion list the opposite of informative.
     */
    private static void generatedIndexCarriesTheRequestScheme(FormatFixture fixture, ArtifactStore store)
            throws Exception {
        FormatFixture.Index index = fixture.index(store).orElseThrow(() -> failure(fixture,
                "this fixture seeds no generated document. Either seed one, or exclude "
                        + Property.GENERATED_INDEX_CARRIES_THE_REQUEST_SCHEME + " with a reason saying the format "
                        + "generates none."));

        // The Host is set here rather than left to the caller, and that is load-bearing: an exchange without one
        // gives this check nothing to compare against, and an earlier version guarded on "host == null" and so
        // asserted nothing at all for every format. A planted private baseUrl - the exact defect five formats
        // carried - passed it green.
        String host = "repo.example:8443";
        ContractExchange over = get(fixture, index.path()).header("Host", host).servedOver("https");
        fixture.serving().handle(over, store);
        equal(over.status(), 200, fixture, "the generated document renders over TLS");

        String rendered = over.responseText();
        for (String downgraded : absoluteUrls(rendered, "http://")) {
            isTrue(!downgraded.regionMatches(true, "http://".length(), host, 0, host.length()),
                    fixture, "a generated document rendered over TLS emits '" + downgraded + "', which points back "
                            + "at this deployment over cleartext. Every credential a client attaches to that URL "
                            + "travels in the clear, and a cache may hand the document to others. Compose the base "
                            + "through the shared request-base seam rather than defaulting the scheme.");
        }
    }

    /** Every {@code prefix}-schemed absolute URL in {@code body}, as bare tokens - enough to tell which host a
     *  document points at without teaching this kit any format's document grammar. */
    private static List<String> absoluteUrls(String body, String prefix) {
        List<String> found = new ArrayList<>();
        for (int at = body.indexOf(prefix); at >= 0; at = body.indexOf(prefix, at + 1)) {
            int end = at;
            while (end < body.length() && " \t\r\n\"\'<>),]}".indexOf(body.charAt(end)) < 0) {
                end++;
            }
            found.add(body.substring(at, end));
        }
        return found;
    }

    private static void generatedIndexIsRevalidatable(FormatFixture fixture, ArtifactStore store) throws Exception {
        FormatFixture.Index index = fixture.index(store).orElseThrow(() -> failure(fixture,
                "this fixture seeds no generated document. Either seed one, or exclude "
                        + Property.GENERATED_INDEX_IS_REVALIDATABLE + " with a reason saying the format generates "
                        + "none."));

        ContractExchange first = get(fixture, index.path());
        fixture.serving().handle(first, store);
        equal(first.status(), 200, fixture, "the generated document renders");
        isTrue(first.responseLength() > 0, fixture, "the generated document is not empty");
        isTrue(first.buffered(), fixture,
                "the generated document is handed over whole (respond(status, byte[])), not streamed against a "
                        + "length. A streamed response carries no content-derived validator, so a client polling this "
                        + "index could never be answered 304 however stable the bytes are.");
        String validator = first.responseHeader("ETag");
        notNull(validator, fixture, "a buffered document carries a validator derived from its own bytes");

        // Determinism is the property the format actually owns: a document re-rendered from unchanged stored state
        // must be byte-identical, or its content-derived validator changes on every poll and revalidation never
        // succeeds however correct the dispatcher is.
        ContractExchange second = get(fixture, index.path());
        fixture.serving().handle(second, store);
        equal(second.responseBytes(), first.responseBytes(), fixture,
                "two renders of unchanged stored state are byte-identical");
        equal(second.responseHeader("ETag"), validator, fixture, "and therefore carry the same validator");

        ContractExchange conditional = get(fixture, index.path()).header("If-None-Match", validator);
        fixture.serving().handle(conditional, store);
        equal(conditional.status(), 304, fixture, "a conditional re-fetch of an unchanged document is a 304");
        equal(conditional.responseLength(), 0L, fixture, "a 304 carries no body");

        // ... and it discriminates: once the stored state moves, the same validator must no longer satisfy the
        // request, or a client would keep a stale index forever.
        index.change().apply(store);
        ContractExchange changed = get(fixture, index.path()).header("If-None-Match", validator);
        fixture.serving().handle(changed, store);
        equal(changed.status(), 200, fixture,
                "once the stored state changes the old validator no longer matches - a 304 here would pin a client "
                        + "to an index that is missing a published version");
        isTrue(!Arrays.equals(changed.responseBytes(), first.responseBytes()), fixture,
                "the re-rendered document reflects the change");
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private static ProxyFormat proxying(FormatFixture fixture, Property property) {
        if (fixture.serving() instanceof ProxyFormat proxy) {
            return proxy;
        }
        throw failure(fixture, "this format implements no ProxyFormat, so it has no pull-through leg. Exclude "
                + property + " with a reason.");
    }

    private static ContractExchange get(FormatFixture fixture, String path) {
        return ContractExchange.of("GET", path).settings(fixture::setting);
    }

    private static ContractExchange head(FormatFixture fixture, String path) {
        return ContractExchange.of("HEAD", path).settings(fixture::setting);
    }

    private static String body(FormatFixture fixture, ArtifactStore store, String path) throws IOException {
        ContractExchange exchange = get(fixture, path);
        fixture.serving().handle(exchange, store);
        return new String(exchange.responseBytes(), StandardCharsets.UTF_8);
    }

    /** Every stored key under {@code prefix}, found by the store's own child enumeration - iterative, so a probe that
     *  planted a deep key cannot overflow the walk that is meant to catch it. */
    private static void walk(ArtifactStore store, String prefix, Consumer<String> keys) {
        Deque<String> frontier = new ArrayDeque<>();
        frontier.push(prefix);
        while (!frontier.isEmpty()) {
            String current = frontier.pop();
            List<String> children = store.list(current);
            if (children.isEmpty()) {
                if (!current.isEmpty()) {
                    keys.accept(current);
                }
                continue;
            }
            for (String child : children) {
                frontier.push(current.isEmpty() ? child : current + "/" + child);
            }
        }
    }

    /** A body whose every byte differs from its neighbours, so a truncated or mis-offset read is visible. */
    private static byte[] ramp(int length) {
        byte[] body = new byte[length];
        for (int index = 0; index < length; index++) {
            body[index] = (byte) (index * 31 + 7);
        }
        return body;
    }

    private static void equal(Object actual, Object expected, FormatFixture fixture, String what) {
        if (!Objects.deepEquals(actual, expected)) {
            throw failure(fixture, what + " - expected " + render(expected) + " but was " + render(actual));
        }
    }

    private static void isTrue(boolean actual, FormatFixture fixture, String what) {
        if (!actual) {
            throw failure(fixture, what);
        }
    }

    private static void notNull(Object actual, FormatFixture fixture, String what) {
        if (actual == null) {
            throw failure(fixture, what + " - expected a value but was null");
        }
    }

    private static AssertionError failure(FormatFixture fixture, String message) {
        return new AssertionError(fixture.format() + ": " + message);
    }

    private static String render(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes.length + " bytes " + HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 32));
        }
        return String.valueOf(value);
    }
}
