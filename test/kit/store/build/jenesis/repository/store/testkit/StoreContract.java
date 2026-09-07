package build.jenesis.repository.store.testkit;

import module java.base;
import module java.net.http;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executable {@link ArtifactStore} contract: one parameterized body of checks that every backend runs through a
 * {@link StoreFixture}, so a store property is stated once and proven four times instead of being re-asserted - and
 * quietly re-interpreted - in four hand-written backend suites. Each {@link Property} names one documented contract
 * clause; {@link #checks(StoreFixture)} binds them to a fixture's store, skipping only the properties that fixture's
 * environment declares (with a reason) it cannot express.
 *
 * <p>Assertion-library-free on purpose: a check throws {@link AssertionError} naming the backend, the property and the
 * expectation, so this module stays {@code java.base} + the store SPI and the downstream distribution can require it
 * exactly as it already requires {@link FaultInjectingStore}. The JUnit driver lives under {@code test/**} and turns
 * each check into one dynamic test.
 *
 * <p>One of the kit's checks drives a fixture this module already ships rather than re-implementing it:
 * {@link Property#STORE_INVARIANTS} runs {@link StoreInvariants} against a freshly scoped subspace of the live
 * backend.
 *
 * <h2>Clauses this kit discharges</h2>
 * Almost all of
 * this kit's properties are about {@link build.jenesis.repository.store.ArtifactStore}, which is not an inventoried
 * surface - no {@code uses}/{@code provides} clause names it - so they are claimed nowhere and the clause-to-property
 * mapping stays where it already is, in that interface's own enforcement preamble. What this kit proves about the
 * discovered <em>provider</em> is its transport-security clause, through {@link Property#PLAINTEXT_ENDPOINT_REFUSED}
 * against a real backend.
 *
 * @jenesis.covers build.jenesis.repository.store.ArtifactStoreProvider 8
 */
public final class StoreContract {

    /**
     * One documented contract clause of {@link ArtifactStore}. The enum is the kit's vocabulary: a fixture excludes a
     * property by name and reason, and the census fails on a property no fixture anywhere exercises, so the list can
     * never grow a clause that is asserted nowhere.
     */
    public enum Property {
        /** A keyed blob round-trips through {@code write}/{@code read}/{@code open}; absence is {@code false},
         *  {@code -1} and an {@code IOException}, never a silent empty stream; {@code delete} is idempotent. */
        KEYED_BLOB_ROUND_TRIP,
        /** {@code writeBlob} content-addresses by SHA-256, lands at {@code blobs/<hash>}, and an identical body
         *  dedupes to the same key rather than being stored twice. */
        CONTENT_ADDRESSED_WRITE,
        /** A source that fails mid-stream commits nothing: the key stays absent and sizes to {@code -1}, so a
         *  truncated body can never be mistaken for the real one (and never poisons the CAS dedupe probe). */
        ABORTED_WRITE_COMMITS_NOTHING,
        /** A scoped view is a subspace: a sibling scope sees none of its objects, and the parent addresses them
         *  under the scope segment. */
        SCOPE_ISOLATION,
        /** {@code scope} rejects every traversal-shaped segment through {@link ArtifactStore#segment}, while a
         *  hidden internal space stays legal. */
        SEGMENT_TRAVERSAL_REJECTED,
        /** Write paths reject a key past {@link ArtifactStore#MAX_SEGMENTS} or {@link ArtifactStore#MAX_KEY_BYTES}
         *  through {@link ArtifactStore#key}, storing nothing; a key at the cap is accepted. */
        KEY_SHAPE_REJECTED,
        /** Write paths reject a key carrying a {@code .} or {@code ..} segment, or a {@code \} or C0 control
         *  character anywhere, storing nothing - the same screen {@code scope} applies to a segment, applied to the
         *  key, separator and character halves included. */
        KEY_TRAVERSAL_REJECTED,
        /** {@code list} returns the immediate children of a prefix and nothing deeper; a leaf and an absent prefix
         *  both list empty. */
        LISTING_IMMEDIATE_CHILDREN,
        /** {@code page} streams immediate children in lexicographic order, strictly after {@code startAfter},
         *  bounded by {@code limit}, and repeated pages traverse the whole child set exactly once. */
        PAGING_ORDER_AND_START_AFTER,
        /** The backend answers {@code page} <em>natively</em> rather than inheriting the SPI's {@code list}-and-sort
         *  fallback: the fallback emits the right names but materialises the whole container to do it, which is what
         *  paging exists to avoid, so a real backend declares its own. */
        NATIVE_PAGING,
        /** {@code scan} enumerates a prefix RECURSIVELY in key order, resumes strictly after its cursor, and reports
         *  truncation with one - so a sweep sees every object under the prefix exactly once across pages. */
        SCAN_IS_RECURSIVE_AND_RESUMES,
        /** {@code scan} delivers each entry's size and modification time out of the backend's own listing, so a sweep
         *  costs its listings and nothing per object. A names-only scan turns every sweep into an N+1. */
        SCAN_CARRIES_LISTING_METADATA,
        /** {@code modified(key)} is a point read of one key's age: present and the object's own for a stored key,
         *  empty for a missing one - what a listing would report, without the listing. */
        MODIFIED_IS_A_POINT_READ,
        /** {@code pageListed} delivers the same children, in the same order, as {@code page} - and carries each
         *  stored child's size and modification time from the backend's own listing, so a descent that needs them
         *  spends no request per child. A container reports neither, having none of its own. */
        PAGE_LISTED_AGREES_AND_CARRIES_METADATA,
        /** {@code version} answers the token without downloading the body - the backend overrides the inherited
         *  read-the-object default, except where its token genuinely needs the bytes. */
        VERSION_WITHOUT_BODY,
        /** {@code writeVersioned} with a {@code null} expectation is create-if-absent: it lands once and is refused
         *  while the object exists, leaving the stored content untouched. */
        VERSIONED_CREATE_IF_ABSENT,
        /** {@code writeVersioned} with a token is update-if-unchanged: it lands against the current token and is
         *  refused against a superseded one, leaving the stored content untouched. */
        VERSIONED_UPDATE_IF_UNCHANGED,
        /** The version token is opaque and per-version: never {@code null}, never interpreted by the caller, changed
         *  by every successful write, and refused once superseded. Absence reads as {@code Optional.empty()}. */
        VERSIONED_STREAMED_OBEYS_THE_SAME_CONDITION,
        VERSION_TOKEN_OPAQUE,
        /** The version token identifies the stored <em>incarnation</em>, not the instant of the write: a key deleted
         *  and re-created with different content never re-issues a token a reader of the previous incarnation holds,
         *  so a compare-and-set from before the delete is refused. */
        VERSION_TOKEN_PER_INCARNATION,
        /** The store-primitive invariants hold on a live backend: no {@code publish/} pointer without its blob, no
         *  unreferenced blob - and a planted dangling pointer is caught. */
        STORE_INVARIANTS,
        /** A read through a {@link ArtifactStore.RangedSink} delivers exactly the window it asks for - the bytes from
         *  its offset for its length, and to the end of the object where the window runs past it - so a client
         *  {@code Range} is served as a slice and never as the whole object. */
        RANGED_READ_IS_A_WINDOW,
        /** {@link ArtifactStore#presign} mints a URL a plain HTTP client fetches the stored bytes through, valid for
         *  the ttl asked for; a backend that mints none answers empty by contract and excludes the property. */
        PRESIGNED_GET_FETCHES_THE_BYTES,
        /** An endpoint an operator points the backend at is required to be {@code https}: a plaintext one is refused
         *  at resolution, naming the opt-out key, and is honoured only once that opt-out is explicitly set. A backend
         *  with no endpoint at all (the filesystem) has no transport to screen and excludes the property. */
        PLAINTEXT_ENDPOINT_REFUSED
    }

    /** One named, independently runnable contract check. */
    public record Check(Property property, String name, Body body) {

        public Check {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
        }
    }

    /** The body of a {@link Check}, run against the fixture's store. */
    @FunctionalInterface
    public interface Body {
        void run(ArtifactStore store) throws Exception;
    }

    private StoreContract() {
    }

    /**
     * Every contract check a live store alone can express, in declaration order, independent of any fixture. The list
     * is the contract: a backend runs all of it or names - with a reason - the properties its environment cannot
     * express.
     *
     * <p>One clause of the SPI is about how a backend is <em>resolved</em> rather than about a store it already handed
     * out - a plaintext endpoint has to be refused before there is any store to check - so {@link #checks(StoreFixture)}
     * appends it from the fixture's own declarations. The census counts properties through that overload, so nothing
     * here shrinks the contract.
     */
    public static List<Check> checks() {
        List<Check> checks = new ArrayList<>();
        checks.add(new Check(Property.KEYED_BLOB_ROUND_TRIP,
                "a keyed blob round-trips and absence is false, -1 and an IOException",
                StoreContract::keyedBlobRoundTrip));
        checks.add(new Check(Property.CONTENT_ADDRESSED_WRITE,
                "writeBlob content-addresses by SHA-256 and dedupes an identical body",
                StoreContract::contentAddressedWrite));
        checks.add(new Check(Property.ABORTED_WRITE_COMMITS_NOTHING,
                "a source that fails mid-stream commits nothing at the key",
                StoreContract::abortedWriteCommitsNothing));
        checks.add(new Check(Property.SCOPE_ISOLATION,
                "a scoped view is a subspace a sibling scope cannot read",
                StoreContract::scopeIsolation));
        checks.add(new Check(Property.SEGMENT_TRAVERSAL_REJECTED,
                "scope rejects every traversal-shaped segment and admits a hidden space",
                StoreContract::segmentTraversalRejected));
        checks.add(new Check(Property.KEY_SHAPE_REJECTED,
                "a write past the segment or byte cap is rejected and stores nothing",
                StoreContract::keyShapeRejected));
        checks.add(new Check(Property.KEY_TRAVERSAL_REJECTED,
                "a write whose key carries a . or .. segment, a backslash or a control character is rejected",
                StoreContract::keyTraversalRejected));
        checks.add(new Check(Property.LISTING_IMMEDIATE_CHILDREN,
                "list returns the immediate children of a prefix and nothing deeper",
                StoreContract::listingImmediateChildren));
        checks.add(new Check(Property.PAGING_ORDER_AND_START_AFTER,
                "page streams ordered children strictly after the boundary, bounded by the limit",
                StoreContract::pagingOrderAndStartAfter));
        checks.add(new Check(Property.NATIVE_PAGING,
                "the backend answers page natively rather than inheriting the list-and-sort fallback",
                StoreContract::nativePaging));
        checks.add(new Check(Property.SCAN_IS_RECURSIVE_AND_RESUMES,
                "scan enumerates a prefix recursively in key order and resumes strictly after its cursor",
                StoreContract::scanRecursiveAndResumes));
        checks.add(new Check(Property.MODIFIED_IS_A_POINT_READ,
                "modified(key) answers a stored key's age and empty for a missing one",
                StoreContract::modifiedPointRead));
        checks.add(new Check(Property.SCAN_CARRIES_LISTING_METADATA,
                "scan carries each entry's size and modification time from the backend's own listing",
                StoreContract::scanCarriesListingMetadata));
        checks.add(new Check(Property.PAGE_LISTED_AGREES_AND_CARRIES_METADATA,
                "pageListed agrees with page and carries each stored child's size and modification time",
                StoreContract::pageListedAgreesAndCarriesMetadata));
        checks.add(new Check(Property.VERSION_WITHOUT_BODY,
                "version answers a token without downloading the body",
                StoreContract::versionWithoutBody));
        checks.add(new Check(Property.VERSIONED_CREATE_IF_ABSENT,
                "writeVersioned against a null expectation is create-if-absent",
                StoreContract::versionedCreateIfAbsent));
        checks.add(new Check(Property.VERSIONED_UPDATE_IF_UNCHANGED,
                "writeVersioned against a token is update-if-unchanged",
                StoreContract::versionedUpdateIfUnchanged));
        checks.add(new Check(Property.VERSIONED_STREAMED_OBEYS_THE_SAME_CONDITION,
                "the streaming writeVersioned obeys the same create-if-absent and update-if-unchanged rules",
                StoreContract::versionedStreamedObeysTheSameCondition));
        checks.add(new Check(Property.VERSION_TOKEN_OPAQUE,
                "the version token is opaque, per-version and refused once superseded",
                StoreContract::versionTokenOpaque));
        checks.add(new Check(Property.VERSION_TOKEN_PER_INCARNATION,
                "a token from a deleted-and-re-created incarnation of a key no longer passes",
                StoreContract::versionTokenPerIncarnation));
        checks.add(new Check(Property.STORE_INVARIANTS,
                "the store-primitive invariants hold and a dangling pointer is caught",
                StoreContract::storeInvariants));
        checks.add(new Check(Property.RANGED_READ_IS_A_WINDOW,
                "a ranged read delivers exactly the window asked for, and to the end where the window runs past it",
                StoreContract::rangedReadIsAWindow));
        checks.add(new Check(Property.PRESIGNED_GET_FETCHES_THE_BYTES,
                "a presigned GET fetches the stored bytes through a plain HTTP client",
                StoreContract::presignedGetFetchesTheBytes));
        return List.copyOf(checks);
    }

    /**
     * The checks {@code fixture} runs: every check whose property the fixture does not exclude. Excluding a property
     * the enum does not declare, or excluding one without a reason, fails here rather than silently shrinking the
     * suite.
     *
     * <p>The resolution-level check is built here, closed over {@code fixture}, because it drives
     * {@link ArtifactStoreProvider#resolve} with the fixture's own config rather than a store it already produced. It
     * ignores the store argument the driver hands it.
     */
    public static List<Check> checks(StoreFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        Map<Property, String> unsupported = fixture.unsupported();
        unsupported.forEach((property, reason) -> {
            Objects.requireNonNull(property, "unsupported property");
            if (reason == null || reason.isBlank()) {
                throw new AssertionError("The '" + fixture.backend() + "' fixture excludes " + property
                        + " without a reason; an exclusion must say what cannot be expressed and where the property "
                        + "is proven instead.");
            }
        });
        List<Check> checks = new ArrayList<>(checks());
        checks.add(new Check(Property.PLAINTEXT_ENDPOINT_REFUSED,
                "a plaintext endpoint is refused at resolution unless the operator opts out",
                _ -> plaintextEndpointRefused(fixture)));
        return checks.stream().filter(check -> !unsupported.containsKey(check.property())).toList();
    }

    // --- the contract ------------------------------------------------------------------------------------------

    private static void keyedBlobRoundTrip(ArtifactStore store) throws Exception {
        String key = "kit/roundtrip/artifact.bin";
        byte[] body = ramp(64);

        isFalse(store.exists(key), "an unwritten key does not exist");
        equal(store.size(key), -1L, "an unwritten key sizes to -1 rather than 0");
        throwsIo(() -> store.read(key, new ByteArrayOutputStream()), "reading an absent key");
        throwsIo(() -> drain(store.open(key)), "opening an absent key");

        store.write(key, new ByteArrayInputStream(body));
        isTrue(store.exists(key), "a written key exists");
        equal(store.size(key), (long) body.length, "the stored byte length");
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        store.read(key, read);
        equal(read.toByteArray(), body, "read streams the stored bytes back");
        equal(drain(store.open(key)), body, "open streams the same bytes back");

        store.delete(key);
        isFalse(store.exists(key), "a deleted key no longer exists");
        equal(store.size(key), -1L, "a deleted key sizes to -1");
        store.delete(key);      // a repeated delete converges rather than throwing - crash-resume replays it
    }

    private static void contentAddressedWrite(ArtifactStore store) throws Exception {
        byte[] body = "kit/content-addressed/payload".getBytes(StandardCharsets.UTF_8);
        String expected = sha256(body);

        String hash = store.writeBlob(new ByteArrayInputStream(body));
        equal(hash, expected, "writeBlob returns the content's SHA-256 in lowercase hex");
        isTrue(store.exists("blobs/" + hash), "the blob lands at the content-addressed key blobs/<hash>");
        equal(drain(store.open("blobs/" + hash)), body, "the content-addressed blob streams back byte-identical");
        equal(store.writeBlob(new ByteArrayInputStream(body)), hash,
                "an identical body dedupes to the one blob rather than being stored twice");
        store.delete("blobs/" + hash);
    }

    private static void abortedWriteCommitsNothing(ArtifactStore store) throws Exception {
        String key = "kit/aborted/artifact.bin";
        throwsIo(() -> store.write(key, failsAfter(3)), "a source that fails mid-stream");
        isFalse(store.exists(key), "an aborted write commits nothing - no truncated body at the key");
        equal(store.size(key), -1L, "an aborted write leaves no partial length behind");

        // ... and the key is still clean, so the real bytes land afterwards: an aborted upload must never be able to
        // park a truncated body that a later content-addressed probe would then treat as already stored.
        byte[] body = ramp(16);
        store.write(key, new ByteArrayInputStream(body));
        equal(drain(store.open(key)), body, "the real bytes land after the earlier abort left the key clean");
        store.delete(key);
    }

    private static void scopeIsolation(ArtifactStore store) throws Exception {
        ArtifactStore left = store.scope("kitleft"), right = store.scope("kitright");
        left.write("space/object", new ByteArrayInputStream(ramp(8)));

        isTrue(left.exists("space/object"), "the writing scope sees its own object");
        isFalse(right.exists("space/object"), "a sibling scope never reads across the subspace boundary");
        equal(right.list("space"), List.of(), "a sibling scope enumerates none of it either");
        isTrue(store.exists("kitleft/space/object"), "the parent addresses it under the scope segment");
        left.delete("space/object");
    }

    private static void segmentTraversalRejected(ArtifactStore store) throws Exception {
        for (String segment : new String[]{"..", "../escape", "a/b", "a\\b", ".", ""}) {
            throwsIae(() -> store.scope(segment), "scoping to the traversal-shaped segment '" + segment + "'");
        }
        throwsIae(() -> store.scope(null), "scoping to a null segment");

        // A plain hidden subspace (the .tests / .scans internal spaces) is a legal segment, not a traversal.
        ArtifactStore hidden = store.scope(".tests");
        hidden.write("object", new ByteArrayInputStream(ramp(4)));
        isTrue(store.exists(".tests/object"), "a hidden internal space still scopes as a subspace");
        hidden.delete("object");
    }

    private static void keyShapeRejected(ArtifactStore store) throws Exception {
        String atCap = String.join("/", Collections.nCopies(ArtifactStore.MAX_SEGMENTS, "a"));
        store.write(atCap, new ByteArrayInputStream(ramp(4)));
        isTrue(store.exists(atCap), "a key at exactly the segment cap is accepted");
        store.delete(atCap);

        String overDeep = String.join("/", Collections.nCopies(ArtifactStore.MAX_SEGMENTS + 1, "a"));
        String overLong = "kit/" + "a".repeat(ArtifactStore.MAX_KEY_BYTES);
        // The rejection is the screen ArtifactStore.key runs before the backend touches a path or a wire, so nothing is
        // attempted; probing exists() for the over-shaped key back is deliberately not asserted, because an object
        // store answers a key past its own 1 KiB limit with a protocol error rather than a clean miss.
        for (String key : new String[]{overDeep, overLong}) {
            throwsIae(() -> store.write(key, new ByteArrayInputStream(ramp(4))),
                    "writing a key past the shape cap");
            throwsIae(() -> store.writeVersioned(key, ramp(4), null),
                    "versioned-writing a key past the shape cap");
        }
    }

    private static void keyTraversalRejected(ArtifactStore store) throws Exception {
        // This check found a real three-way divergence, which is why it exists. Before the screen landed in
        // ArtifactStore.key, one `store.write("kit/../escape", ...)` did three different things: the filesystem
        // silently NORMALISED it and stored the body one level up, at a key the caller never named; Azure stored it
        // LITERALLY at the traversal-shaped key; and S3/GCS answered a transport IOException from the object store's
        // own key screen. No backend suite tested a traversal-shaped key on the write path, so nothing saw it. The
        // screen sits at the one choke point every backend already calls, before any I/O, so all four now refuse the
        // same publish the same way and a store migration cannot relocate or lose an object.
        // The backslash rows are the same divergence one alphabet over, and they are here because the screen once read
        // only '/': "kit\..\escape" carries no '/'-delimited traversal segment at all, so it passed the screen
        // and reached the backends - where a Windows-hosted filesystem store resolves it as a REAL traversal and lands
        // the body a level up, while S3, GCS and Azure store it as one literal key with a backslash in the name. The
        // bare "kit\escape" row is the same fact without the traversal: one key, two placements, so a store migration
        // would relocate it. Both are refused at the shared screen, so all four backends stay interchangeable.
        // The control-character rows are the third alphabet of the same divergence, and the last one the free core
        // screened nowhere - not in traversalFree, not in key, not in segment - while this product's own request
        // guard had refused them since it was written. A NUL truncates the key at the first C API that handles it, so
        // a key screened whole is acted on in part and the four backends need not even agree on which object was
        // meant; a CR or LF forges a line in every log record and generated listing the key later reaches, so a
        // coordinate can write rows that read as the server's own. Refused at the shared screen, before any I/O, so
        // no backend has to have an opinion.
        for (String key : new String[]{"kit/../escape", "../escape", "kit/./here", "..", ".",
                "kit\\..\\escape", "..\\escape", "kit\\.\\here", "kit\\escape", "\\",
                "kit/esc\u0000ape", "kit/esc\nape", "kit/esc\rape", "kit/esc\tape", "\u0000",
                "\u0001kit/escape", "kit/escape\u001f"}) {
            throwsIae(() -> store.write(key, new ByteArrayInputStream(ramp(4))),
                    "writing the traversal-shaped key '" + key + "'");
            throwsIae(() -> store.writeVersioned(key, ramp(4), null),
                    "versioned-writing the traversal-shaped key '" + key + "'");
        }
        isFalse(store.exists("escape"), "a rejected traversal key stores nothing where it aimed");
    }

    private static void listingImmediateChildren(ArtifactStore store) throws Exception {
        String base = "kit/listing";
        store.write(base + "/alpha", new ByteArrayInputStream(ramp(4)));
        store.write(base + "/beta/nested", new ByteArrayInputStream(ramp(4)));
        store.write(base + "/gamma", new ByteArrayInputStream(ramp(4)));

        equal(store.list(base), List.of("alpha", "beta", "gamma"),
                "list returns the immediate children - a container by its name, not its descendants");
        equal(store.list(base + "/beta"), List.of("nested"), "a container lists its own children");
        equal(store.list(base + "/alpha"), List.of(), "a leaf has no children");
        equal(store.list(base + "/absent"), List.of(), "an absent prefix lists empty rather than failing");
        // A prefix with a trailing slash names the same container: a caller that keeps a directory's slash (the hold
        // lifecycle does) gets the same listing, never a doubled delimiter the service refuses.
        equal(store.list(base + "/"), List.of("alpha", "beta", "gamma"), "a trailing slash names the same container");
        List<String> paged = new ArrayList<>();
        store.page(base + "/", "", 10, paged::add);
        equal(paged, List.of("alpha", "beta", "gamma"), "page accepts the trailing slash too");
        // pageListed composes a full key rather than a bare name, so the trailing slash reaches the key itself:
        // a backend that joins the raw prefix answers a/b//alpha, which no other read of that key would match.
        List<String> listedKeys = new ArrayList<>();
        store.pageListed(base + "/", "", 10, listed -> listedKeys.add(listed.key()));
        equal(listedKeys, List.of(base + "/alpha", base + "/beta", base + "/gamma"),
                "pageListed keys a trailing-slash prefix exactly as it keys the bare one");
        List<String> scanned = new ArrayList<>();
        store.scan(base + "/", "", 10, listed -> scanned.add(listed.key()));
        equal(scanned, List.of(base + "/alpha", base + "/beta/nested", base + "/gamma"),
                "scan accepts the trailing slash and keys its results without it");

        store.delete(base + "/alpha");
        store.delete(base + "/beta/nested");
        store.delete(base + "/gamma");
    }

    private static void pagingOrderAndStartAfter(ArtifactStore store) throws Exception {
        // "beta" (a container) beside "beta.txt" (a leaf) is the ordering trap every object-store backend has to
        // repair: '.' sorts below '/', so the raw key stream hands out beta.txt before the grouped prefix beta/,
        // while the child name `beta` must page first. A backend that streams raw key order fails here.
        String base = "kit/paging";
        store.write(base + "/alpha", new ByteArrayInputStream(ramp(4)));
        store.write(base + "/beta/nested", new ByteArrayInputStream(ramp(4)));
        store.write(base + "/beta.txt", new ByteArrayInputStream(ramp(4)));
        store.write(base + "/delta", new ByteArrayInputStream(ramp(4)));
        List<String> children = List.of("alpha", "beta", "beta.txt", "delta");

        equal(page(store, base, "", 10), children, "page streams every immediate child in lexicographic order");
        equal(page(store, base, "beta", 10), List.of("beta.txt", "delta"),
                "startAfter is strict - the boundary name itself never re-emits, container or leaf");
        equal(page(store, base, "", 2), List.of("alpha", "beta"), "the limit bounds the page");
        equal(page(store, base, "", 0), List.of(), "a non-positive limit emits nothing");
        equal(page(store, base, "zzz", 10), List.of(), "a boundary past every child emits nothing");
        equal(page(store, base + "/absent", "", 10), List.of(), "an absent prefix pages empty rather than failing");
        equal(page(store, base + "/alpha", "", 10), List.of(), "a leaf pages empty");

        // Repeated single-entry pages, each resuming after the last name of the one before, traverse the whole child
        // set exactly once - the primitive the shared artifact walk is built on.
        List<String> traversed = new ArrayList<>();
        for (String cursor = ""; ; ) {
            List<String> next = page(store, base, cursor, 1);
            if (next.isEmpty()) {
                break;
            }
            traversed.addAll(next);
            cursor = next.get(next.size() - 1);
        }
        equal(traversed, children, "paging in strides of one traverses every child exactly once, in order");
        equal(new ArrayList<>(store.list(base)), children, "list and a full paging agree on the child set");

        store.delete(base + "/alpha");
        store.delete(base + "/beta/nested");
        store.delete(base + "/beta.txt");
        store.delete(base + "/delta");
    }

    /**
     * A backend declares {@code pageListed} itself - the one paging primitive, from which {@code page} derives. The
     * SPI's {@code default} is a correctness fallback that sorts a whole {@link ArtifactStore#list} - it emits the
     * right children, so no behavioural check can tell it from a native implementation on a small container, which is
     * exactly why every shipped backend already overrode it and nothing caught the trap. The only observable
     * difference is the declaring class, so that is what is asserted: a backend whose {@code pageListed} resolves to
     * {@link ArtifactStore}'s own body would materialise a millions-entry namespace to answer one page, and is refused
     * here rather than at whatever scale first exhausts a production heap. A decorator passes because it overrides
     * the method to delegate, which is the only correct thing for a decorator to do with it.
     */
    private static void nativePaging(ArtifactStore store) throws Exception {
        Class<?> declaring = store.getClass()
                .getMethod("pageListed", String.class, String.class, int.class, Consumer.class)
                .getDeclaringClass();
        if (declaring == ArtifactStore.class) {
            throw new AssertionError(store.getClass().getName() + " inherits ArtifactStore's list-and-sort pageListed "
                    + "fallback instead of paging natively: it materialises the container's whole child set to answer "
                    + "one page, which is the opposite of what paging is for, and it refuses outright past "
                    + ArtifactStore.MAX_INHERITED_CHILDREN + " children. Implement pageListed(...) over the backend's "
                    + "own start-after pagination; page derives from it.");
        }
    }

    /** The keys one scan check plants: two levels deep, so a NON-recursive implementation misses the deep ones, and
     *  lexicographically interleaved so a page boundary lands in the middle of a directory. */
    private static final List<String> SCAN_KEYS = List.of(
            "kit/scan/a/one", "kit/scan/a/two", "kit/scan/b/three", "kit/scan/b/four", "kit/scan/c/five");

    private static void scanRecursiveAndResumes(ArtifactStore store) throws Exception {
        for (String key : SCAN_KEYS) {
            store.write(key, new ByteArrayInputStream(utf8(key)));
        }
        List<String> all = new ArrayList<>();
        ArtifactStore.Scan whole = store.scan("kit/scan", "", 100, listed -> all.add(listed.key()));
        isFalse(whole.truncated(), "a scan inside its limit is not truncated");
        equal(new TreeSet<>(all), new TreeSet<>(SCAN_KEYS),
                "scan is recursive: it delivers the keys two levels below the prefix, not the directory names above "
                        + "them");
        equal(all, all.stream().sorted().toList(), "scan delivers in key order");

        // Resume: two pages of two must cover the same set, once each, with nothing skipped between them.
        List<String> paged = new ArrayList<>();
        String cursor = "";
        int rounds = 0;
        while (rounds++ < 10) {
            ArtifactStore.Scan page = store.scan("kit/scan", cursor, 2, listed -> paged.add(listed.key()));
            if (!page.truncated()) {
                break;
            }
            cursor = page.cursor().orElseThrow();
        }
        equal(paged, all, "paged scanning delivers exactly the keys one unbounded scan does, in the same order");
        for (String key : SCAN_KEYS) {
            store.delete(key);
        }
    }

    private static void scanCarriesListingMetadata(ArtifactStore store) throws Exception {
        String key = "kit/scan-meta/object";
        byte[] body = utf8("twenty-four characters!!");
        Instant before = Instant.now().minusSeconds(60);
        store.write(key, new ByteArrayInputStream(body));
        List<ArtifactStore.Listed> listed = new ArrayList<>();
        store.scan("kit/scan-meta", "", 10, listed::add);
        equal(listed.size(), 1, "the planted object is scanned");
        ArtifactStore.Listed entry = listed.getFirst();
        isTrue(entry.size().isPresent(),
                "scan carries the size from the backend's listing - without it every sweep stats once per object, "
                        + "which is the N+1 scan exists to avoid");
        equal(entry.size().getAsLong(), (long) body.length, "the carried size is the object's size");
        isTrue(entry.modified().isPresent(), "scan carries the modification time from the backend's listing");
        isTrue(entry.modified().orElseThrow().isAfter(before),
                "the carried modification time is the object's, not a placeholder");
        store.delete(key);
    }

    private static void pageListedAgreesAndCarriesMetadata(ArtifactStore store) throws Exception {
        // A leaf beside a container, so the check covers both shapes and the ordering rule that puts them in one
        // child stream.
        byte[] body = utf8("nineteen characters");
        Instant before = Instant.now().minusSeconds(60);
        store.write("kit/listed/leaf", new ByteArrayInputStream(body));
        store.write("kit/listed/folder/inner", new ByteArrayInputStream(utf8("x")));

        List<String> named = new ArrayList<>();
        store.page("kit/listed", "", 10, named::add);
        List<ArtifactStore.Listed> listed = new ArrayList<>();
        store.pageListed("kit/listed", "", 10, listed::add);

        equal(listed.stream().map(entry -> entry.key().substring("kit/listed/".length())).toList(), named,
                "pageListed delivers the same children in the same order as page - it is the same listing, and the "
                        + "names-only form is derived from it");

        ArtifactStore.Listed leaf = listed.stream()
                .filter(entry -> entry.key().endsWith("/leaf")).findFirst().orElseThrow();
        isTrue(leaf.size().isPresent(),
                "a stored child carries the size its listing reported - without it a descent stats once per leaf, "
                        + "which is a round trip per key on the pass that opens every key there is");
        equal(leaf.size().getAsLong(), (long) body.length, "the carried size is the object's size");
        isTrue(leaf.modified().isPresent(), "a stored child carries the modification time its listing reported");
        isTrue(leaf.modified().orElseThrow().isAfter(before), "the carried time is the object's, not a placeholder");

        ArtifactStore.Listed folder = listed.stream()
                .filter(entry -> entry.key().endsWith("/folder")).findFirst().orElseThrow();
        isFalse(folder.size().isPresent(),
                "a container reports no size: it has none of its own, and a descent reads a present size as proof "
                        + "that the child is a stored object");

        store.delete("kit/listed/leaf");
        store.delete("kit/listed/folder/inner");
    }

    private static void versionWithoutBody(ArtifactStore store) throws Exception {
        String key = "kit/version/object";
        equal(store.version(key).isPresent(), false, "an absent object has no version");
        store.writeVersioned(key, utf8("one"), null);
        Object token = store.version(key).orElseThrow();
        equal(token, store.readVersioned(key).orElseThrow().token(),
                "version reports the same token readVersioned pairs with the body - one incarnation, one token");
        store.writeVersioned(key, utf8("two"), token);
        isFalse(store.version(key).orElseThrow().equals(token), "a write moves the version on");
        store.delete(key);
    }

    private static void versionedCreateIfAbsent(ArtifactStore store) throws Exception {
        String key = "kit/versioned/create";
        equal(store.readVersioned(key).isPresent(), false, "an absent object reads as Optional.empty()");
        isTrue(store.writeVersioned(key, utf8("one"), null), "create-if-absent lands against a null expectation");
        isFalse(store.writeVersioned(key, utf8("two"), null),
                "create-if-absent is refused while the object exists, rather than overwriting it");
        equal(content(store, key), "one", "the refused write left the stored content untouched");
        store.delete(key);
    }

    private static void versionedUpdateIfUnchanged(ArtifactStore store) throws Exception {
        String key = "kit/versioned/update";
        isTrue(store.writeVersioned(key, utf8("v1"), null), "the object is created");
        Object token = store.readVersioned(key).orElseThrow().token();
        isTrue(store.writeVersioned(key, utf8("v2"), token), "update-if-unchanged lands against the current token");
        isFalse(store.writeVersioned(key, utf8("v3"), token),
                "the same token no longer passes once it has been superseded - a lost update is impossible");
        equal(content(store, key), "v2", "the refused write left the stored content untouched");
        store.delete(key);
    }

    /**
     * The streaming compare-and-set is the buffered one with a different body, and this is what holds it to that.
     *
     * <p>The failure it exists for is silent: a backend whose streaming upload drops the precondition still writes,
     * still returns true, and differs from a correct one only when two writers race - which no single-threaded test
     * notices. So both refusals are asserted, and the stored content is read back after each, because "returned
     * false" and "did not write" are separate claims and only the second one matters.
     */
    private static void versionedStreamedObeysTheSameCondition(ArtifactStore store) throws Exception {
        String key = "kit/versioned/streamed";
        equal(store.readVersioned(key).isPresent(), false, "an absent object reads as Optional.empty()");
        isTrue(streamed(store, key, "one", null), "create-if-absent lands against a null expectation");
        isFalse(streamed(store, key, "two", null),
                "create-if-absent is refused while the object exists, rather than overwriting it");
        equal(content(store, key), "one", "the refused streaming write left the stored content untouched");

        Object token = store.readVersioned(key).orElseThrow().token();
        isTrue(streamed(store, key, "v2", token), "update-if-unchanged lands against the current token");
        isFalse(streamed(store, key, "v3", token),
                "the same token no longer passes once superseded - a streamed lost update is impossible too");
        equal(content(store, key), "v2", "the refused streaming write left the stored content untouched");

        // The two forms have to agree about what they wrote, not merely about whether they were allowed to.
        isTrue(store.writeVersioned(key, utf8("v3"), store.readVersioned(key).orElseThrow().token()),
                "the buffered form still lands after a streamed one");
        equal(content(store, key), "v3", "a streamed write leaves an object the buffered form can update");
        store.delete(key);
    }

    private static boolean streamed(ArtifactStore store, String key, String content, Object expected)
            throws IOException {
        byte[] bytes = utf8(content);
        return store.writeVersioned(key, new ByteArrayInputStream(bytes), bytes.length, expected);
    }

    private static void versionTokenOpaque(ArtifactStore store) throws Exception {
        String key = "kit/versioned/token";
        isTrue(store.writeVersioned(key, utf8("a"), null), "the object is created");
        Object first = store.readVersioned(key).orElseThrow().token();
        notNull(first, "a present object always carries a version token");

        isTrue(store.writeVersioned(key, utf8("b"), first), "the token the store handed out is the one it accepts");
        Object second = store.readVersioned(key).orElseThrow().token();
        notNull(second, "the token survives an update");
        if (Objects.equals(first, second)) {
            throw failure("the version token advances on every successful write (a backend whose token can repeat "
                    + "lets a writer holding the pre-update token pass a stale write off as current), but the token "
                    + "was " + first + " before and after");
        }

        isTrue(store.writeVersioned(key, utf8("c"), second), "the current token still passes");
        isFalse(store.writeVersioned(key, utf8("d"), first),
                "a token two versions stale is refused, not merely the immediately previous one");
        equal(content(store, key), "c", "the refused write left the stored content untouched");

        store.delete(key);
        equal(store.readVersioned(key).isPresent(), false, "a deleted object reads as Optional.empty() again");
    }

    /**
     * How many delete-and-re-create cycles {@link #versionTokenPerIncarnation} runs. The property it asserts is
     * unconditional - a token from a previous incarnation must never pass, whenever the two incarnations happened -
     * but the shape that breaks it on a wall-clock-stamped backend only appears when both land inside one stamp tick
     *, and a single cycle straddles the tick boundary as often as not. A cycle is four small operations, so a
     * few dozen of them sweep across the boundary in well under a second on any backend while staying a bounded,
     * fixed amount of work. The deterministic form of the same probe - the two incarnations forced onto one stamp -
     * is the filesystem backend's own suite, where the stamp is reachable.
     */
    private static final int INCARNATIONS = 32;

    private static void versionTokenPerIncarnation(ArtifactStore store) throws Exception {
        String key = "kit/versioned/incarnation";
        for (int cycle = 0; cycle < INCARNATIONS; cycle++) {
            String before = "before-" + cycle, after = "after-" + cycle;
            isTrue(store.writeVersioned(key, utf8(before), null), "the first incarnation is created");
            Object stale = store.readVersioned(key).orElseThrow().token();
            store.delete(key);
            isTrue(store.writeVersioned(key, utf8(after), null),
                    "the key is re-created as a second, unrelated incarnation");
            isFalse(store.writeVersioned(key, utf8("stale-" + cycle), stale),
                    "a token read from an incarnation that has since been deleted no longer passes: the object it "
                            + "named is gone, so a compare-and-set holding it would land over content it never read. "
                            + "A backend whose token is a bare wall-clock stamp re-issues the same value when the "
                            + "re-creation lands inside the same tick, which is what makes this a property of the "
                            + "incarnation rather than of the instant");
            equal(content(store, key), after, "the refused write left the second incarnation untouched");
            store.delete(key);
        }
    }

    private static void rangedReadIsAWindow(ArtifactStore store) throws Exception {
        String key = "kit/ranged/artifact.bin";
        byte[] body = ramp(64);
        store.write(key, new ByteArrayInputStream(body));
        ByteArrayOutputStream window = new ByteArrayOutputStream();
        store.read(key, new Window(window, 10, 8));
        equal(window.toByteArray(), Arrays.copyOfRange(body, 10, 18), "a window inside the object is exactly its bytes");
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        store.read(key, new Window(tail, 60, 16));
        equal(tail.toByteArray(), Arrays.copyOfRange(body, 60, 64), "a window past the end reads to the end, never a 416");
        store.delete(key);
    }

    private static void presignedGetFetchesTheBytes(ArtifactStore store) throws Exception {
        String key = "kit/presigned/artifact.bin";
        byte[] body = ramp(96);
        store.write(key, new ByteArrayInputStream(body));
        Optional<URI> url = store.presign(key, Duration.ofMinutes(5));
        isTrue(url.isPresent(), "a backend that runs this property mints a URL; one that cannot excludes it with a reason");
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(url.get()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        equal((long) response.statusCode(), 200L, "the presigned GET is answered 200 by the store itself");
        equal(response.body(), body, "and carries the stored bytes");
        store.delete(key);
    }

    /** The serving layer's shape of a ranged read: an output stream that is also the window it asks for. */
    private static final class Window extends OutputStream implements ArtifactStore.RangedSink {
        private final OutputStream sink;
        private final long offset;
        private final long length;

        private Window(OutputStream sink, long offset, long length) {
            this.sink = sink;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public long offset() {
            return offset;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public OutputStream sink() {
            return sink;
        }

        @Override
        public void write(int b) {
            throw new UnsupportedOperationException("a ranged read writes to the sink");
        }
    }

    private static void storeInvariants(ArtifactStore store) throws Exception {
        // A freshly scoped subspace, so the blobs/ and publish/ namespaces the checker walks are this check's alone.
        ArtifactStore isolated = store.scope("kitinvariants");
        byte[] body = "kit/invariants/payload".getBytes(StandardCharsets.UTF_8);
        String hash = isolated.writeBlob(new ByteArrayInputStream(body));
        String pointer = "publish/maven/org/example/lib/1.0/lib-1.0.jar";
        isTrue(isolated.writeVersioned(pointer, utf8(hash), null), "the serving pointer links the stored blob");

        new StoreInvariants(isolated).assertConsistent();

        // ... and the checker is not vacuous on this backend: a pointer to a blob that was never stored is caught.
        isTrue(isolated.writeVersioned("publish/maven/org/example/lib/1.0/lib-1.0.pom", utf8("0".repeat(64)), null),
                "a dangling pointer is planted");
        boolean caught = false;
        try {
            new StoreInvariants(isolated).assertNoDanglingPointer();
        } catch (AssertionError expected) {
            caught = true;
        }
        isTrue(caught, "a publish/ pointer whose blob is missing is reported, not walked past");

        isolated.delete(pointer);
        isolated.delete("publish/maven/org/example/lib/1.0/lib-1.0.pom");
        isolated.delete("blobs/" + hash);
    }

    /**
     * The transport screen, driven through the resolution path a deployment takes rather than through a live store: the
     * fixture's own config - the one it reaches its emulator over plaintext {@code http} with - must be refused when
     * its opt-out is taken away, and honoured when it is put back.
     *
     * <p>The {@code s3} and {@code gcs} backends have refused a non-{@code https} endpoint override since they were
     * written; the {@code azure-blob} backend had no such screen at all, because its scheme rides <em>inside</em> the
     * connection string that also carries the account key, so the rule its siblings enforce never applied to it and a
     * mistyped scheme put the account key and every artifact byte on a plaintext wire with no operator signal. Stating
     * the rule once here is what stops the next endpoint-configured backend from arriving without it (&sect;13).
     *
     * <p>The fixture supplying the config is also what makes the check honest in the other direction: because the
     * emulators are only reachable over {@code http}, the whole containerised leg is proof that the opt-out really is
     * an opt-out, and the negative leg here is proof the screen really bites.
     */
    private static void plaintextEndpointRefused(StoreFixture fixture) {
        StoreFixture.Plaintext plaintext = fixture.plaintext().orElseThrow(() -> failure(
                "the '" + fixture.backend() + "' fixture runs PLAINTEXT_ENDPOINT_REFUSED but declares no plaintext() "
                        + "config; a backend with an endpoint must declare the config it reaches it over and the key "
                        + "that opts out, and a backend with no endpoint must exclude the property with a reason"));
        Map<String, String> allowed = new LinkedHashMap<>(plaintext.config());
        String optOut = allowed.remove(plaintext.allowInsecureKey());
        if (!Boolean.parseBoolean(optOut)) {
            throw failure("the '" + fixture.backend() + "' fixture's declared config must itself carry "
                    + plaintext.allowInsecureKey() + "=true - its emulator is reachable only over plaintext http, so "
                    + "a fixture that resolves without the opt-out is proof the screen is not applied at all, but the "
                    + "value was " + optOut);
        }

        String message = throwsIse(() -> ArtifactStoreProvider.resolve(fixture.backend(), allowed::get),
                "resolving the '" + fixture.backend() + "' backend against its plaintext endpoint with the opt-out "
                        + "removed - credentials and artifact bytes would travel in clear with no operator signal");
        if (!message.contains(plaintext.allowInsecureKey())) {
            throw failure("the refusal must name the opt-out key '" + plaintext.allowInsecureKey() + "', or an "
                    + "operator running a local emulator has no way to act on it, but the message was: " + message);
        }

        // ... and the screen is an opt-out, not a ban: the same config resolves once the operator sets it.
        notNull(ArtifactStoreProvider.resolve(fixture.backend(), plaintext.config()::get),
                "the very same plaintext endpoint resolves once the opt-out is explicitly set");
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private static List<String> page(ArtifactStore store, String prefix, String startAfter, int limit) {
        List<String> names = new ArrayList<>();
        store.page(prefix, startAfter, limit, names::add);
        return names;
    }

    private static String content(ArtifactStore store, String key) throws IOException {
        return new String(store.readVersioned(key).orElseThrow(
                () -> failure("expected an object at " + key + " but found none")).content(), StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** A body whose every byte differs from its neighbours, so a truncated or mis-offset read is visible. */
    private static byte[] ramp(int length) {
        byte[] body = new byte[length];
        for (int index = 0; index < length; index++) {
            body[index] = (byte) index;
        }
        return body;
    }

    /** A source that serves {@code served} bytes and then fails - a client hanging up mid-upload. */
    private static InputStream failsAfter(int served) {
        return new InputStream() {
            private int delivered;

            @Override
            public int read() throws IOException {
                if (delivered++ < served) {
                    return 'x';
                }
                throw new IOException("the source hung up mid-stream");
            }
        };
    }

    private static byte[] drain(InputStream in) throws IOException {
        try (InputStream stream = in) {
            return stream.readAllBytes();
        }
    }

    private static String sha256(byte[] body) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    // These four are now one line each over AssertJ, which this kit may use since it moved under test/. They stay
    // as helpers rather than being inlined at their hundred-odd call sites: the argument order here is
    // (actual, expected) and AssertJ's is assertThat(actual).isEqualTo(expected), so a mechanical inlining that
    // transposed a pair would still pass - equality is symmetric - and only the failure message would lie. Keeping
    // the call sites untouched removes that whole class of mistake, and the diff quality AssertJ brings arrives at
    // every one of them anyway. The hand-rolled render() that formatted the two sides went with them.

    private static void equal(Object actual, Object expected, String what) {
        assertThat(actual).as(what).isEqualTo(expected);
    }

    private static void isTrue(boolean actual, String what) {
        assertThat(actual).as(what).isTrue();
    }

    private static void isFalse(boolean actual, String what) {
        assertThat(actual).as(what).isFalse();
    }

    private static void notNull(Object actual, String what) {
        assertThat(actual).as(what).isNotNull();
    }

    /** A body that must fail with an {@link IOException} - the SPI's transport-failure shape. */
    private static void throwsIo(Fallible body, String what) {
        try {
            body.run();
        } catch (IOException expected) {
            return;
        } catch (Exception e) {
            throw failure(what + " - expected an IOException but " + e.getClass().getName() + " was thrown: "
                    + e.getMessage());
        }
        throw failure(what + " - expected an IOException but nothing was thrown");
    }

    /** A body that must fail with an {@link IllegalArgumentException} - the SPI's rejected-shape screen. */
    private static void throwsIae(Fallible body, String what) {
        try {
            body.run();
        } catch (IllegalArgumentException expected) {
            return;
        } catch (Exception e) {
            throw failure(what + " - expected an IllegalArgumentException but " + e.getClass().getName()
                    + " was thrown: " + e.getMessage());
        }
        throw failure(what + " - expected an IllegalArgumentException but nothing was thrown");
    }

    /** A body that must fail with an {@link IllegalStateException} - the SPI's refused-at-resolution shape (&sect;9) -
     *  answering its message, so a check can also require the diagnostic to name what the operator has to do. */
    private static String throwsIse(Fallible body, String what) {
        try {
            body.run();
        } catch (IllegalStateException expected) {
            return String.valueOf(expected.getMessage());
        } catch (Exception e) {
            throw failure(what + " - expected an IllegalStateException but " + e.getClass().getName()
                    + " was thrown: " + e.getMessage());
        }
        throw failure(what + " - expected an IllegalStateException but nothing was thrown");
    }

    @FunctionalInterface
    private interface Fallible {
        void run() throws Exception;
    }

    private static AssertionError failure(String message) {
        return new AssertionError(message);
    }

    private static void modifiedPointRead(ArtifactStore store) throws IOException {
        String key = "kit/modified/object";
        Instant before = Instant.now().minusSeconds(60);
        store.write(key, new ByteArrayInputStream(utf8("an object with an age")));
        Optional<Instant> modified = store.modified(key);
        isTrue(modified.isPresent(), "a stored key has an age, read for that one key");
        isTrue(modified.orElseThrow().isAfter(before), "the age is the object's own, not a placeholder");
        isTrue(store.modified("kit/modified/absent").isEmpty(), "a missing key has no age rather than an error");
        store.delete(key);
        isTrue(store.modified(key).isEmpty(), "a deleted key has no age");
    }

}
