package build.jenesis.repository.cache.storage.testkit;

import module java.base;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.CacheStorage.Entry;
import build.jenesis.repository.cache.storage.CacheStorage.Stored;
import build.jenesis.repository.cache.storage.CacheStorageProvider;
import build.jenesis.repository.walk.Traversal;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executable {@link CacheStorage} contract: one parameterized body of checks that every backend runs through a
 * {@link CacheStorageFixture}, so a storage property is stated once and proven four times instead of being
 * re-asserted - and quietly re-interpreted - in four hand-written backend suites.
 *
 * <p>The properties are derived from what {@link CacheStorage} itself documents, not copied from the
 * {@code StoreContract}. The two interfaces share a family resemblance and, where the clause really is the same, the
 * assertion here is deliberately the same shape: a blob round-trip whose absence is a sentinel, an aborted write
 * that commits nothing, a scope that is a subspace, a scope name screened against traversal, a compare-and-set with
 * create-if-absent and update-if-unchanged legs, and an opaque per-version token. Everything else differs, in both
 * directions. The artifact store's content-addressed {@code blobs/<sha>} namespace, its key-shape caps, its ordered
 * {@code startAfter} paging and its per-entry batch outcomes have <em>no</em> counterpart here: a cache entry is
 * addressed by a coordinate the client already hashed, and this SPI has no batch. Its enumerations, however, page
 * exactly as the artifact store's do and are asserted here to: they take a bound and a cursor and answer the same
 * shared {@link Traversal.Result}, because "the listing came back short" has to be a value a caller reads, never a
 * silence it has no way to notice. The cache adds five clauses the artifact store has none of: recency and its
 * {@code stamp}, capacity reporting, per-project config with its own revalidation token, the relative
 * {@code .users/} config tree, and project provisioning.
 *
 * <p>Assertion-library-free on purpose: a check throws {@link AssertionError} naming the backend, the property and
 * the expectation, so this module stays {@code java.base} + the cache-storage SPI. The JUnit driver lives under
 * {@code test/**} and turns each check into one dynamic test.
 *
 * <p>Every check runs inside its own freshly scoped subspace of the fixture's storage, so the checks are hermetic
 * and order-independent: a check may assert emptiness, and one check's leftovers can never be another's enumeration.
 *
 * <h2>Clauses this kit discharges</h2>
 * Most of this
 * kit's twenty checks are about {@code CacheStorage}, which is the product interface rather than the discovered SPI;
 * what it proves about the <em>provider</em> is the tenant-scoping clause, through {@code TENANT_SCOPE_ISOLATION} and
 * {@code TENANT_NAME_REJECTED} over a real backend.
 *
 * @jenesis.covers build.jenesis.repository.cache.storage.CacheStorageProvider 6
 */
public final class CacheStorageContract {

    /**
     * One documented contract clause of {@link CacheStorage}. The enum is the kit's vocabulary: a fixture excludes a
     * property by name and reason, and the census fails on a property no fixture anywhere exercises, so the list can
     * never grow a clause that is asserted nowhere.
     */
    public enum Property {
        /** An entry blob round-trips through {@code store}/{@code exists}/{@code read}; absence is {@code false}
         *  and an {@code IOException}, never a silent empty body a client would cache as a hit; a re-store replaces
         *  the body whole. */
        ENTRY_BLOB_ROUND_TRIP,
        /** A source that fails mid-stream commits nothing: the entry stays absent and enumerates nowhere, so a
         *  truncated body can never be served as a hit that no repair upload can replace. */
        ABORTED_STORE_COMMITS_NOTHING,
        /** {@code store} refuses an entry that is not a valid project plus two hex segments - the same screen every
         *  backend already applies when it enumerates - and stores nothing. */
        ENTRY_ADDRESS_REJECTED,
        /** A tenant scope is a subspace: a sibling tenant sees none of its entries, projects or config files. */
        TENANT_SCOPE_ISOLATION,
        /** {@code scope} admits exactly a valid tenant name and rejects everything else, so the tenant segment is
         *  traversal-free by construction rather than by the backend's own path handling. */
        TENANT_NAME_REJECTED,
        /** A project's {@code cache.properties} round-trips through {@code writeConfig}/{@code readConfig}, an
         *  absent project or file reads as an <em>empty</em> {@code Properties}, and a rewrite replaces rather than
         *  merges. {@code createProject} is idempotent. */
        PROJECT_CONFIG_ROUND_TRIP,
        /** {@code configVersion} is {@code null} while the config is absent, non-null once it exists, unchanged by
         *  a pure read, and {@code null} again once the project's objects are gone. */
        CONFIG_VERSION_SENTINEL,
        /** {@code configVersion} advances on every {@code writeConfig} that changes the policy, however quickly the
         *  rewrites follow one another - the project-config counterpart of {@code VERSION_TOKEN_OPAQUE}. A token that
         *  can repeat across a rewrite is one the server's per-project config cache revalidates successfully against
         *  a policy that no longer exists, so it keeps serving the superseded caps. */
        CONFIG_VERSION_ADVANCES_ON_REWRITE,
        /** The config-tree writes refuse a path that escapes the scope or a project-config file name carrying a
         *  separator, and write nothing - the object-store half of the same screen the filesystem enforces by
         *  confining its resolved path. */
        CONFIG_PATH_REJECTED,
        /** A nested config file round-trips through {@code writeFile}/{@code readFile}; an absent path reads as an
         *  empty {@code Properties}; an overwrite replaces the whole document. */
        CONFIG_FILE_TREE_ROUND_TRIP,
        /** {@code listDir} returns the immediate child <em>containers</em> of a prefix - never a leaf document and
         *  never a descendant - and an absent prefix lists empty rather than failing. */
        CONTAINER_LISTING_IMMEDIATE_CHILDREN,
        /** {@code deleteDir} removes a whole subtree and converges on replay; deleting an absent path is a no-op. */
        RECURSIVE_DELETE_CONVERGES,
        /** {@code writeFileVersioned} with a {@code null} expectation is create-if-absent: it lands once and is
         *  refused while the document exists, leaving the stored content untouched. */
        VERSIONED_CREATE_IF_ABSENT,
        /** {@code writeFileVersioned} with a token is update-if-unchanged: it lands against the current token and is
         *  refused against a superseded one, leaving the stored content untouched. */
        VERSIONED_UPDATE_IF_UNCHANGED,
        /** The {@code fileVersion} token is opaque and per-version: {@code null} exactly when absent, never
         *  interpreted by the caller, changed by every successful write, and refused once superseded. */
        VERSION_TOKEN_OPAQUE,
        /** {@code entries} enumerates exactly the stored entry blobs of a project - a config
         *  document is never one - with their true byte size, a usable deletion token and a real recency instant.
         *  One residual asymmetry is deliberately <em>not</em> asserted, because it is unreachable through this
         *  SPI's own write path and closing it would shrink what a filesystem deployment can reclaim: the
         *  filesystem backend counts any regular file whose <em>leaf</em> name is hex at any depth below the
         *  project, while the three object stores require exactly a {@code <step>/<inputs>} pair with both hex. A
         *  hex-named project config file - {@code writeConfig(project, "aa", …)} - is therefore an entry to one
         *  backend and not to the other three. Nothing in the product writes one. */
        ENTRY_ENUMERATION_SHAPE,
        /** {@code delete} removes the enumerated blob and only it, disappears from the enumeration, and converges on
         *  a repeated call - the reaper replays over a snapshot a concurrent pass may already have drained. */
        DELETE_REMOVES_ENUMERATED_BLOB,
        /** {@code stamp} records recency as data beside the entry and damages nothing: content and size are unchanged,
         *  {@code recency} reads the instant back as a stamp - and an entry never stamped as its own time, the one the
         *  enumeration reports - the enumeration carries a stamp as the entry's recency, a renewal that names the
         *  previous stamp supersedes it, and stamping an absent entry stores nothing. */
        STAMP_RECORDS_RECENCY_WITHOUT_DAMAGE,
        /** {@code usableSpace}/{@code totalSpace} answer a consistent pair: either the documented unlimited sentinel
         *  ({@code Long.MAX_VALUE} and {@code 0}) or a real bounded pair, never a mixture the free-space sweep would
         *  read as a full volume. The pair is asked <em>before</em> anything is stored as well as after, and the
         *  answer's shape may not change between the two: "unlimited" is a backend's standing declaration that it
         *  has no capacity limit, never an admission that this particular probe could not answer. */
        CAPACITY_UNLIMITED_OR_REAL,
        /** {@code store} and {@code read} STREAM: an entry blob is never fully materialised in heap by the
         *  backend, whatever its size. The clause has been in {@code CacheStorage}'s contract block since it was
         *  written and nothing tested it - the largest payload anywhere else in this kit is three hundred bytes,
         *  and every other check hands {@code store} a {@code ByteArrayInputStream} over a small array. A backend
         *  calling {@code in.readAllBytes()} internally passed the entire kit.
         *
         *  <p><b>The falsifier is a bounded heap, and here that is the RIGHT bound rather than the wrong one.</b>
         *  The artifact side makes buffering fatal with a container memory limit, and {@code ServerContainer} warns
         *  that bounding an in-process run would bound "the SUITE's heap, which is a different thing wearing the
         *  same clothes". That warning is about a subject running in its own process. This subject does not: a
         *  {@code CacheStorage} backend runs in the suite's JVM, so the suite's heap is exactly the memory the
         *  clause is about, and a payload sized past it makes materialising fatal rather than merely visible.
         *
         *  <p>That is why this property runs only in its own lane
         *  ({@code -Djenesis.make.profiles=cachestream}), which bounds the heap so the payload can be small
         *  and the check fast. Under a default heap the payload would have to be gigabytes to prove anything. */
        ENTRY_BLOB_STREAMS,
        /** An endpoint an operator points the backend at is required to be {@code https}: a plaintext one is refused
         *  at resolution, naming the opt-out key, and is honoured only once that opt-out is explicitly set. A backend
         *  with no endpoint at all (the filesystem) has no transport to screen and excludes the property. */
        PLAINTEXT_ENDPOINT_REFUSED,
        /** Every enumeration - {@code projects}, {@code entries}, {@code listDir} - is bounded by the caller's limit
         *  and resumable from the cursor it hands back: a call delivers at most {@code limit}, says
         *  {@code TRUNCATED} when it did not deliver everything, and a resume from its cursor delivers exactly the
         *  remainder, once each. This is the property whose absence was the defect: the four enumerations returned
         *  whole collections, so a project a build had inflated came back as one list on the heap and a caller had no
         *  way at all to learn that what it held was a prefix of the store. */
        BOUNDED_ENUMERATION_RESUMES
    }

    /** One named, independently runnable contract check. */
    public record Check(Property property, String name, Body body) {

        public Check {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
        }
    }

    /** The body of a {@link Check}, run against a fresh subspace of the fixture's storage. */
    @FunctionalInterface
    public interface Body {
        void run(CacheStorage storage) throws Exception;
    }

    private CacheStorageContract() {
    }

    /**
     * Every contract check a live storage alone can express, in declaration order, independent of any fixture. The
     * list is the contract: a backend runs all of it or names - with a reason - the properties its environment cannot
     * express.
     *
     * <p>One clause of the SPI is about how a backend is <em>resolved</em> rather than about a storage it already
     * handed out - a plaintext endpoint has to be refused before there is any storage to check - so
     * {@link #checks(CacheStorageFixture)} appends it from the fixture's own declarations. The census counts
     * properties through that overload, so nothing here shrinks the contract.
     */
    public static List<Check> checks() {
        List<Check> checks = new ArrayList<>();
        checks.add(new Check(Property.ENTRY_BLOB_STREAMS,
                "an entry blob larger than this JVM's heap stores and reads back without being materialised",
                CacheStorageContract::entryBlobStreams));
        checks.add(new Check(Property.ENTRY_BLOB_ROUND_TRIP,
                "an entry blob round-trips and absence is false and an IOException",
                CacheStorageContract::entryBlobRoundTrip));
        checks.add(new Check(Property.ABORTED_STORE_COMMITS_NOTHING,
                "a source that fails mid-stream commits no entry at all",
                CacheStorageContract::abortedStoreCommitsNothing));
        checks.add(new Check(Property.ENTRY_ADDRESS_REJECTED,
                "store refuses a non-hex or traversal-shaped entry and stores nothing",
                CacheStorageContract::entryAddressRejected));
        checks.add(new Check(Property.TENANT_SCOPE_ISOLATION,
                "a tenant scope is a subspace a sibling tenant cannot read",
                CacheStorageContract::tenantScopeIsolation));
        checks.add(new Check(Property.TENANT_NAME_REJECTED,
                "scope admits a valid tenant name and rejects every other shape",
                CacheStorageContract::tenantNameRejected));
        checks.add(new Check(Property.PROJECT_CONFIG_ROUND_TRIP,
                "a project config round-trips and an absent one reads as empty",
                CacheStorageContract::projectConfigRoundTrip));
        checks.add(new Check(Property.CONFIG_VERSION_SENTINEL,
                "configVersion is null while absent, non-null once written and null again once gone",
                CacheStorageContract::configVersionSentinel));
        checks.add(new Check(Property.CONFIG_VERSION_ADVANCES_ON_REWRITE,
                "configVersion advances on every rewrite, however quickly they follow one another",
                CacheStorageContract::configVersionAdvancesOnRewrite));
        checks.add(new Check(Property.CONFIG_PATH_REJECTED,
                "the config writes refuse a path that escapes the scope and write nothing",
                CacheStorageContract::configPathRejected));
        checks.add(new Check(Property.CONFIG_FILE_TREE_ROUND_TRIP,
                "a nested config file round-trips and an absent one reads as empty",
                CacheStorageContract::configFileTreeRoundTrip));
        checks.add(new Check(Property.CONTAINER_LISTING_IMMEDIATE_CHILDREN,
                "listDir returns the immediate child containers of a prefix and nothing else",
                CacheStorageContract::containerListingImmediateChildren));
        checks.add(new Check(Property.RECURSIVE_DELETE_CONVERGES,
                "deleteDir removes a whole subtree and converges on replay",
                CacheStorageContract::recursiveDeleteConverges));
        checks.add(new Check(Property.VERSIONED_CREATE_IF_ABSENT,
                "writeFileVersioned against a null expectation is create-if-absent",
                CacheStorageContract::versionedCreateIfAbsent));
        checks.add(new Check(Property.VERSIONED_UPDATE_IF_UNCHANGED,
                "writeFileVersioned against a token is update-if-unchanged",
                CacheStorageContract::versionedUpdateIfUnchanged));
        checks.add(new Check(Property.VERSION_TOKEN_OPAQUE,
                "the file version token is opaque, per-version and refused once superseded",
                CacheStorageContract::versionTokenOpaque));
        checks.add(new Check(Property.ENTRY_ENUMERATION_SHAPE,
                "entries enumerates the stored blobs with size, token and recency",
                CacheStorageContract::entryEnumerationShape));
        checks.add(new Check(Property.BOUNDED_ENUMERATION_RESUMES,
                "every enumeration honours its bound, reports the truncation and resumes from its cursor",
                CacheStorageContract::boundedEnumerationResumes));
        checks.add(new Check(Property.DELETE_REMOVES_ENUMERATED_BLOB,
                "delete removes the enumerated blob only and converges on replay",
                CacheStorageContract::deleteRemovesEnumeratedBlob));
        checks.add(new Check(Property.STAMP_RECORDS_RECENCY_WITHOUT_DAMAGE,
                "stamp records recency beside the entry, reads back, enumerates, supersedes and invents nothing",
                CacheStorageContract::stampRecordsRecencyWithoutDamage));
        checks.add(new Check(Property.CAPACITY_UNLIMITED_OR_REAL,
                "usableSpace and totalSpace answer a consistent unlimited-or-real pair, empty scope included",
                CacheStorageContract::capacityUnlimitedOrReal));
        return List.copyOf(checks);
    }

    /**
     * The checks {@code fixture} runs: every check whose property the fixture does not exclude. Excluding a property
     * the enum does not declare, or excluding one without a reason, fails here rather than silently shrinking the
     * suite.
     *
     * <p>The resolution-level checks are built here, closed over {@code fixture}, because they drive
     * {@code CacheStorageProvider.resolve} with the fixture's own config rather than a storage it already produced.
     * They ignore the storage argument the driver hands them.
     */
    public static List<Check> checks(CacheStorageFixture fixture) {
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

    /**
     * One property's falsifier: the mutant that must break the check, and why that removal is the right one to
     * hold it to. The {@code why} is read out in the failure message when a check survives its mutation, because
     * at that point the question is which of the two is wrong - the check or the declaration.
     */
    public record Mutation(CacheStorageMutant mutant, String why) {

        public Mutation {
            Objects.requireNonNull(mutant, "mutant");
            if (why == null || why.isBlank()) {
                throw new IllegalArgumentException("a mutation must say why this removal is the right falsifier");
            }
        }
    }

    /**
     * The falsifier each property declares. A check is a claim until something shows it can fail, and the four
     * backends this kit covers are the ones a deployment's cache actually rides on, so "1 of 20 survive an inert
     * backend" measured once by hand is not enough - it has to be a leg that runs.
     *
     * <p>Keyed by property rather than by check, because the property is what the mutation is chosen against: the
     * removal has to be the defect the property exists to name, not merely something that makes the check red.
     */
    public static Map<Property, List<Mutation>> mutations() {
        Map<Property, List<Mutation>> mutations = new EnumMap<>(Property.class);
        mutations.put(Property.ENTRY_BLOB_ROUND_TRIP, List.of(new Mutation(
                CacheStorageMutant.A_STORE_THAT_DROPS_THE_BLOB,
                "a blob that does not come back is the whole of this property, and a backend that consumes the "
                        + "stream without writing is the plausible way to lose it")));
        mutations.put(Property.ABORTED_STORE_COMMITS_NOTHING, List.of(new Mutation(
                CacheStorageMutant.A_STORE_THAT_KEEPS_A_FAILED_WRITE,
                "committing the prefix a failed source managed to yield is exactly the half-write the property "
                        + "forbids, and it is what a backend that streams straight through does by default")));
        mutations.put(Property.ENTRY_ADDRESS_REJECTED, List.of(new Mutation(
                CacheStorageMutant.AN_ADDRESS_SCREEN_THAT_PASSES,
                "the property is the screen, so removing the refusal is the only removal that reaches it")));
        mutations.put(Property.TENANT_SCOPE_ISOLATION, List.of(new Mutation(
                CacheStorageMutant.A_SCOPE_THAT_IGNORES_THE_TENANT,
                "a scope that hands back the same storage is the leak the property forbids, and it is what an "
                        + "implementation that treats the tenant as decoration produces")));
        mutations.put(Property.TENANT_NAME_REJECTED, List.of(new Mutation(
                CacheStorageMutant.A_TENANT_NAME_SCREEN_THAT_PASSES,
                "admitting any name is how the tenant segment stops being a name and starts being a path")));
        mutations.put(Property.PROJECT_CONFIG_ROUND_TRIP, List.of(new Mutation(
                CacheStorageMutant.A_CONFIG_THAT_FORGETS_A_KEY,
                "dropping one property is the round trip failing partially, which is the shape a check that only "
                        + "asserts 'something came back' would miss")));
        mutations.put(Property.CONFIG_VERSION_SENTINEL, List.of(new Mutation(
                CacheStorageMutant.A_CONFIG_VERSION_THAT_IS_ALWAYS_PRESENT,
                "the sentinel IS the null-while-absent rule, so inventing a token for an absent config is the "
                        + "removal")));
        mutations.put(Property.CONFIG_VERSION_ADVANCES_ON_REWRITE, List.of(new Mutation(
                CacheStorageMutant.A_CONFIG_VERSION_THAT_NEVER_MOVES,
                "a frozen token makes a rewrite indistinguishable from no write, which is the compare-and-set "
                        + "hazard the property exists for")));
        mutations.put(Property.CONFIG_PATH_REJECTED, List.of(new Mutation(
                CacheStorageMutant.A_CONFIG_PATH_SCREEN_THAT_PASSES,
                "the property is the screen; letting an escaping path through is the defect itself")));
        mutations.put(Property.CONFIG_FILE_TREE_ROUND_TRIP, List.of(new Mutation(
                CacheStorageMutant.A_FILE_TREE_THAT_FORGETS,
                "reading back an empty document separates a real round trip from a check that only asserts the "
                        + "read did not throw")));
        mutations.put(Property.CONTAINER_LISTING_IMMEDIATE_CHILDREN, List.of(new Mutation(
                CacheStorageMutant.A_LISTING_THAT_INCLUDES_LEAVES,
                "emitting a leaf as a container is the exact confusion the property names, and the one a prefix "
                        + "listing over a flat key space falls into")));
        mutations.put(Property.RECURSIVE_DELETE_CONVERGES, List.of(new Mutation(
                CacheStorageMutant.A_RECURSIVE_DELETE_THAT_STOPS,
                "leaving the subtree is the failure; a delete that only removes the top is what a flat backend "
                        + "does when it forgets to enumerate")));
        mutations.put(Property.VERSIONED_CREATE_IF_ABSENT, List.of(new Mutation(
                CacheStorageMutant.A_VERSIONED_WRITE_THAT_ALWAYS_LANDS,
                "ignoring the null expectation turns create-if-absent into overwrite, which is the lost update")));
        mutations.put(Property.VERSIONED_UPDATE_IF_UNCHANGED, List.of(new Mutation(
                CacheStorageMutant.A_VERSIONED_WRITE_THAT_ALWAYS_LANDS,
                "the same removal reaches this half: a write that lands against a stale token is the update the "
                        + "property refuses")));
        mutations.put(Property.VERSION_TOKEN_OPAQUE, List.of(new Mutation(
                CacheStorageMutant.A_VERSION_TOKEN_THAT_IS_CONSTANT,
                "one constant for every version keeps the token non-null and absent-aware while destroying the "
                        + "per-version identity the compare-and-set depends on")));
        mutations.put(Property.ENTRY_ENUMERATION_SHAPE, List.of(new Mutation(
                CacheStorageMutant.AN_ENUMERATION_THAT_INCLUDES_THE_CONFIG,
                "the property is that the enumeration holds entry blobs and nothing else, so adding a config "
                        + "document is the removal")));
        mutations.put(Property.DELETE_REMOVES_ENUMERATED_BLOB, List.of(new Mutation(
                CacheStorageMutant.A_DELETE_THAT_DOES_NOTHING,
                "a delete that does not delete is the failure, and it is what a backend that swallows a missing-key "
                        + "error looks like")));
        mutations.put(Property.STAMP_RECORDS_RECENCY_WITHOUT_DAMAGE, List.of(new Mutation(
                CacheStorageMutant.A_STAMP_THAT_DESTROYS,
                "recording recency by rewriting the entry empty is the destructive stamp the property forbids, and "
                        + "it is the shape a backend with no metadata-only update reaches for"), new Mutation(
                CacheStorageMutant.A_STAMP_THAT_IS_FORGOTTEN,
                "a stamp that writes nothing is the backend that still relies on a modification time it does not "
                        + "have, and reads back as never used"), new Mutation(
                CacheStorageMutant.A_RECENCY_THAT_KNOWS_ONLY_STAMPS,
                "a recency that answers empty for an entry never stamped is the lookup that scanned the stamps and "
                        + "stopped, which made every node that had not stored an entry stamp it on first sight")));
        mutations.put(Property.CAPACITY_UNLIMITED_OR_REAL, List.of(new Mutation(
                CacheStorageMutant.A_CAPACITY_PAIR_THAT_DISAGREES,
                "the property is the pair being consistent, so one half answering the unlimited sentinel while the "
                        + "other answers a real number is precisely the inconsistency")));
        mutations.put(Property.BOUNDED_ENUMERATION_RESUMES, List.of(new Mutation(
                CacheStorageMutant.AN_ENUMERATION_THAT_IGNORES_THE_LIMIT,
                "answering in one unbounded page is the unbounded listing the property exists to forbid")));
        return Collections.unmodifiableMap(mutations);
    }

    /**
     * The properties no storage mutant can reach, each with the reason. A property here is not exempt from being
     * measured - it is measured by something a decorator over {@link CacheStorage} cannot get underneath.
     */
    public static Map<Property, String> unfalsifiable() {
        return Map.of(Property.ENTRY_BLOB_STREAMS,
                "the negation of \"streams\" is \"materialises\", and a backend that materialises behaves "
                        + "IDENTICALLY to one that streams until the entry is larger than the heap - at which "
                        + "point it does not fail the check, it exhausts the JVM. Measured, not assumed: a "
                        + "materialising mutant in the cachestream lane made this kit report \"did not fail ... - "
                        + "it broke\", which is the harness refusing an OutOfMemoryError. This kit requires a "
                        + "mutant to be "
                        + "answered with an AssertionError, and it is right to: an OutOfMemoryError is the harness "
                        + "being taken out from under the check rather than the check reporting anything, and it "
                        + "says nothing about whether the check measures its property. So the clause is held by the "
                        + "check alone, run in the cachestream lane whose bounded heap makes materialising fatal - "
                        + "the same posture the artifact side's streaming row takes, where the container is killed "
                        + "for trying and its own comment calls that a fact rather than a measurement. Written down "
                        + "here because a property with no mutation is otherwise indistinguishable from one nobody "
                        + "got round to falsifying.",
                Property.PLAINTEXT_ENDPOINT_REFUSED,
                "the check never touches a CacheStorage. It drives CacheStorageProvider.resolve with the fixture's "
                        + "own plaintext endpoint and asserts the resolution refuses, so the subject is the "
                        + "provider's configuration screen and there is no storage object to decorate. Falsifying it "
                        + "would mean mutating the provider, which is a different kit than this one.");
    }

    /** The key the large lanes set; the same one the artifact side's streaming row runs behind. */
    private static final String LARGE = "jenesis.test.large";

    /**
     * A property that needs a lane rather than an environment, with the reason - the third kind of "not run here",
     * beside a fixture's {@link CacheStorageFixture#unsupported() unsupported} declaration and
     * {@link CacheStorageFixture#skipReason skipReason}'s environment gate.
     *
     * <p>{@link Property#ENTRY_BLOB_STREAMS} is the only one. It is not unsupported by any backend and its
     * environment is always available; what it needs is a bounded heap, because that bound is the thing that makes
     * materialising an entry fatal instead of invisible. Run it with
     * {@code -Djenesis.make.profiles=cachestream}.
     */
    public static Optional<String> laneOnly(Property property) {
        if (property != Property.ENTRY_BLOB_STREAMS || Boolean.getBoolean(LARGE)) {
            return Optional.empty();
        }
        return Optional.of("the streaming clause is falsified by a bounded heap, so it runs in its own lane: "
                + "-Djenesis.make.profiles=cachestream (which sets -Xmx and " + LARGE + "). Under a default "
                + "heap the payload would have to be gigabytes to prove anything, and a payload that fits proves "
                + "nothing at all");
    }

    /**
     * An entry larger than this JVM can hold stores and reads back intact.
     *
     * <p>Nothing here materialises the blob either: the payload is generated procedurally as it is read, and the
     * read side digests it as it arrives. A check that buffered the body to compare it would be the same defect it
     * is looking for, one frame further out.
     */
    private static void entryBlobStreams(CacheStorage storage) throws Exception {
        long size = Math.max(64L << 20, Runtime.getRuntime().maxMemory() * 3 / 2);
        Entry entry = new Entry("streams", "cc", "03");

        storage.store(entry, procedural(size));

        MessageDigest read = MessageDigest.getInstance("SHA-256");
        long[] counted = {0L};
        storage.read(entry, new OutputStream() {
            @Override
            public void write(int b) {
                read.update((byte) b);
                counted[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) {
                read.update(b, off, len);
                counted[0] += len;
            }
        });

        equal(counted[0], size, "the stored entry reads back at its stored length");
        equal(read.digest(), digestOf(size), "the bytes that come back are the bytes that went in");
    }

    /** The payload, generated as it is read - never a byte[] of its own. */
    private static InputStream procedural(long length) {
        return new InputStream() {
            private long at;

            @Override
            public int read() {
                return at < length ? (byte) at++ & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (at >= length) {
                    return -1;
                }
                int n = (int) Math.min(len, length - at);
                for (int i = 0; i < n; i++) {
                    b[off + i] = (byte) (at + i);
                }
                at += n;
                return n;
            }
        };
    }

    /** The digest the generator would produce, computed the same way: streamed, never held. */
    private static byte[] digestOf(long length) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream in = procedural(length)) {
            for (int read; (read = in.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    /** A tenant name no other check, fixture or run shares - the subspace one check owns. */
    public static String uniqueTenant() {
        return "kit" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1);
    }

    // --- the contract ------------------------------------------------------------------------------------------

    private static void entryBlobRoundTrip(CacheStorage storage) throws Exception {
        Entry entry = new Entry("roundtrip", "aa", "01");
        byte[] body = ramp(64);

        isFalse(storage.exists(entry), "an unwritten entry does not exist");
        throwsIo(() -> storage.read(entry, new ByteArrayOutputStream()),
                "reading an absent entry - an empty body would be cached by the client as a hit");

        storage.store(entry, new ByteArrayInputStream(body));
        isTrue(storage.exists(entry), "a stored entry exists");
        equal(read(storage, entry), body, "read streams the stored bytes back byte-identically");

        // A re-store replaces the body whole rather than appending or merging: the client re-uploads an entry when
        // its own hash changed, and half of the old body behind the new name would be served as a hit forever.
        byte[] replacement = ramp(17);
        storage.store(entry, new ByteArrayInputStream(replacement));
        equal(read(storage, entry), replacement, "a re-store replaces the stored body whole");

        // An empty body is a legal entry, not an absence: a build step that produced nothing still caches a hit.
        Entry empty = new Entry("roundtrip", "bb", "02");
        storage.store(empty, new ByteArrayInputStream(new byte[0]));
        isTrue(storage.exists(empty), "an empty body is a stored entry, not an absence");
        equal(read(storage, empty), new byte[0], "an empty entry streams back empty");
    }

    private static void abortedStoreCommitsNothing(CacheStorage storage) throws Exception {
        Entry entry = new Entry("aborted", "aa", "01");
        throwsIo(() -> storage.store(entry, failsAfter(3)), "a source that hangs up mid-stream");
        isFalse(storage.exists(entry),
                "an aborted store commits nothing - a truncated entry would answer every later GET as a hit, and a "
                        + "repair upload cannot replace an entry the server already reports as present");
        equal(entries(storage, "aborted"), List.of(), "and it enumerates nowhere either");

        // ... and the coordinate is still clean, so the real bytes land afterwards.
        byte[] body = ramp(16);
        storage.store(entry, new ByteArrayInputStream(body));
        equal(read(storage, entry), body, "the real bytes land after the earlier abort left the entry absent");
    }

    private static void entryAddressRejected(CacheStorage storage) throws Exception {
        // This check exists because the four backends did three different things here. The filesystem confined its
        // resolved path, so a traversal-shaped entry was refused - and `<project>/<step>/..`, which normalises back
        // to the project folder, made store() return normally having stored NOTHING. The three object-store backends
        // composed an opaque key and stored the entry LITERALLY, at a key their own entries() then skips
        // forever, because all four already screen a non-hex `<step>/<inputs>` pair when they enumerate: the object
        // was stored, invisible to the size cap, invisible to the ttl and unreachable by the free-space sweep. The
        // screen now sits on the one shared predicate (Names.isEntry) before any I/O, so all four refuse identically.
        List<Entry> unaddressable = new ArrayList<>();
        unaddressable.add(new Entry("rejected", "..", "01"));
        unaddressable.add(new Entry("rejected", "aa", ".."));
        unaddressable.add(new Entry("..", "aa", "01"));
        unaddressable.add(new Entry("rejected/../escape", "aa", "01"));
        unaddressable.add(new Entry("rejected", "zz", "01"));
        unaddressable.add(new Entry("rejected", "aa", "0/1"));
        unaddressable.add(new Entry("rejected", "", "01"));
        unaddressable.add(new Entry("rejected", "aa", ""));
        unaddressable.add(new Entry(null, "aa", "01"));
        unaddressable.add(new Entry("rejected", null, "01"));
        for (Entry entry : unaddressable) {
            throwsIae(() -> storage.store(entry, new ByteArrayInputStream(ramp(4))),
                    "storing the unaddressable entry " + entry);
        }
        equal(everyEntry(storage), List.of(), "a refused entry stores nothing anywhere in the scope");
        equal(entries(storage, "rejected"), List.of(), "and nothing under the project it aimed at");

        // ... and the screen is not simply refusing everything: the addressable coordinate still lands.
        Entry legal = new Entry("rejected", "aa", "01");
        storage.store(legal, new ByteArrayInputStream(ramp(4)));
        isTrue(storage.exists(legal), "a valid project plus two hex segments still stores");
    }

    private static void tenantScopeIsolation(CacheStorage storage) throws Exception {
        CacheStorage left = storage.scope("kitleft"), right = storage.scope("kitright");
        Entry entry = new Entry("isolate", "aa", "01");
        Properties properties = properties("v", "left");

        left.store(entry, new ByteArrayInputStream(ramp(8)));
        left.writeFile(".users/login.properties", properties);
        left.writeConfig("isolate", "cache.properties", properties("size", "100"));

        isTrue(left.exists(entry), "the writing tenant sees its own entry");
        isFalse(right.exists(entry), "a sibling tenant never reads across the subspace boundary");
        equal(entries(right, "isolate"), List.of(), "a sibling tenant enumerates none of its entries");
        equal(everyEntry(right), List.of(), "not through the union across its own projects either");
        isFalse(projects(right).contains("isolate"), "nor through the project listing");
        equal(right.readFile(".users/login.properties").stringPropertyNames(), Set.of(),
                "a sibling tenant reads none of its config tree");
        equal(right.fileVersion(".users/login.properties"), null,
                "and sees no version token for a document it cannot read");
        equal(right.readConfig("isolate", "cache.properties").stringPropertyNames(), Set.of(),
                "nor the project's policy - which would leak another tenant's size and ttl caps");
        isFalse(right.projectExists("isolate"), "and the project itself is invisible");

        // Re-scoping is a pure view: the same tenant name resolves the same durable content.
        equal(storage.scope("kitleft").readFile(".users/login.properties").getProperty("v"), "left",
                "re-scoping to the same tenant is a view of the same content, not a fresh space");
    }

    private static void tenantNameRejected(CacheStorage storage) throws Exception {
        // Deliberately narrower than the artifact store's segment screen, and the contrast is the point: there a
        // hidden internal space like `.tests` is a legal segment, here the tenant alphabet is [A-Za-z0-9_-]+, so a
        // dot is not a name at all. Whatever a backend's own path handling would make of these, the SPI refuses them.
        for (String tenant : new String[]{"..", "../escape", "a/b", "a\\b", ".", "", ".hidden", "a.b", "a b", "a:b"}) {
            throwsIae(() -> storage.scope(tenant), "scoping to the invalid tenant name '" + tenant + "'");
        }
        throwsIae(() -> storage.scope(null), "scoping to a null tenant");

        // ... and the alphabet the store-side tenant gates accept really is accepted, hyphen included.
        for (String tenant : new String[]{"acme", "acme-corp", "acme_corp", "ACME9"}) {
            CacheStorage scoped = storage.scope(tenant);
            notNull(scoped, "a valid tenant name scopes to a storage view");
            equal(projects(scoped), List.of(), "a freshly scoped tenant holds nothing");
        }
    }

    private static void projectConfigRoundTrip(CacheStorage storage) throws Exception {
        String project = "conf";
        equal(storage.readConfig(project, "cache.properties").stringPropertyNames(), Set.of(),
                "an absent project config reads as an empty Properties, never null and never an exception");
        isFalse(storage.projectExists(project), "an absent project does not exist");

        storage.writeConfig(project, "cache.properties", properties("size", "100", "ttl", "7d"));
        isTrue(storage.projectExists(project), "a project holding content exists");
        equal(storage.readConfig(project, "cache.properties").getProperty("size"), "100",
                "the project policy reads back");
        equal(storage.readConfig(project, "cache.properties").getProperty("ttl"), "7d",
                "every key reads back");

        // A rewrite replaces the document rather than merging into it: a removed cap must really be removed, or a
        // lowered-then-cleared limit would keep applying from a stale key nobody can see in the console.
        storage.writeConfig(project, "cache.properties", properties("size", "200"));
        equal(storage.readConfig(project, "cache.properties").getProperty("size"), "200", "the new value stands");
        equal(storage.readConfig(project, "cache.properties").getProperty("ttl"), null,
                "and a key the rewrite dropped is gone - writeConfig replaces, it does not merge");

        equal(storage.readConfig(project, "absent.properties").stringPropertyNames(), Set.of(),
                "an absent file inside a live project reads as empty too");
        equal(storage.readConfig("absent-project", "cache.properties").stringPropertyNames(), Set.of(),
                "as does a file inside a project that does not exist");

        // createProject is provisioning, not content: it converges on replay and never fails for a legal name. What
        // it makes visible is backend-specific by contract (a directory on a filesystem, nothing on an object
        // store), so the contract pins only that it is idempotent and silent.
        storage.createProject("provisioned");
        storage.createProject("provisioned");
    }

    private static void configVersionSentinel(CacheStorage storage) throws Exception {
        String project = "versioned";
        equal(storage.configVersion(project), null, "an absent project config carries no version token");

        storage.writeConfig(project, "cache.properties", properties("size", "100"));
        Object token = storage.configVersion(project);
        notNull(token, "a stored project config carries a version token the config cache revalidates against");

        // A read never advances the token: the server's per-project config cache would otherwise reload on every
        // request and the LRU would be pure overhead.
        storage.readConfig(project, "cache.properties");
        equal(storage.configVersion(project), token, "a pure read leaves the revalidation token untouched");

        storage.deleteDir(project);
        equal(storage.configVersion(project), null, "the token is null again once the project's objects are gone");
    }

    /**
     * The revalidation token has to move whenever the policy behind it moves, and "whenever" includes two rewrites a
     * console form-submit apart.
     *
     * <p>The filesystem backend's token was the config file's last-modified time, and a kernel's coarse timestamp
     * advances only once per tick - roughly a millisecond, where a rewrite takes microseconds. {@code writeConfig}
     * did not force the stamp forward the way {@code writeFileVersioned} explicitly does, so two rewrites inside one
     * tick left the token identical: the server's per-project config cache revalidated successfully and went on
     * serving the <em>superseded</em> policy, with the operator's lowered size cap or shortened ttl silently not
     * applied and nothing anywhere to show for it. The object stores mint an ETag or generation per write and were
     * always correct here, which is exactly why the property belongs in the shared contract rather than in a
     * filesystem test: it states what all four owe the config cache.
     *
     * <p>The rewrites run back to back in a loop rather than once with a sleep, so the check leans on no timer at
     * all: whatever a volume's timestamp granularity is, sixteen consecutive rewrites either all move the token or
     * expose the one that did not. Each rewrite carries different content, because a content-addressed token (an S3
     * ETag is the body's MD5) legitimately repeats when the body does, and an unchanged policy is not a stale one.
     *
     * <p>Honest limitation, and where it is covered: on a volume whose timestamps are finer than the cost of a write
     * - a current ext4, for one - the filesystem backend's stamp advances on its own and this check cannot manufacture
     * the collision, so it states the guarantee without being able to witness its absence there. The deterministic
     * half lives in the filesystem storage tests, which pin the stored stamp ahead of the clock and requires
     * the rewrite to move past it - the same repair, for the same reason, provable on any machine.
     */
    private static void configVersionAdvancesOnRewrite(CacheStorage storage) throws Exception {
        String project = "revalidated";
        storage.writeConfig(project, "cache.properties", properties("size", "100"));
        Object token = storage.configVersion(project);
        notNull(token, "a stored project config carries a version token");

        for (int rewrite = 1; rewrite <= 16; rewrite++) {
            storage.writeConfig(project, "cache.properties", properties("size", Integer.toString(100 + rewrite)));
            Object next = storage.configVersion(project);
            notNull(next, "the token survives rewrite " + rewrite);
            if (Objects.equals(token, next)) {
                throw failure("the project-config version token must differ after a rewrite that changed the policy, "
                        + "or the server's per-project config cache revalidates against a token that has not moved "
                        + "and keeps serving the superseded caps - but rewrite " + rewrite + " left it at " + next);
            }
            token = next;
        }
        equal(storage.readConfig(project, "cache.properties").getProperty("size"), "116",
                "and the token tracks the policy the store now holds, not one of the sixteen it replaced");
    }

    private static void configPathRejected(CacheStorage storage) throws Exception {
        // The config-tree half of the addressability screen. The filesystem backend confined and refused these; the
        // object-store backends composed the same string into an opaque key and stored it literally - a document at
        // `<tenant>/../escape.properties` that no listing of the tenant will ever show and no tenant purge will ever
        // delete. All four now refuse before any request is signed.
        String[] unaddressable = {"../escape.properties", "a/../b.properties", "./x.properties", "/absolute.properties",
                "a//b.properties", "", "a\\b.properties", "trailing/", "..", "."};
        Properties body = properties("v", "1");
        for (String path : unaddressable) {
            throwsIae(() -> storage.writeFile(path, body), "writing the unaddressable path '" + path + "'");
            throwsIae(() -> storage.writeFileVersioned(path, body, null),
                    "versioned-writing the unaddressable path '" + path + "'");
            throwsIae(() -> storage.deleteDir(path), "recursively deleting the unaddressable path '" + path + "'");
        }
        throwsIae(() -> storage.writeFile(null, body), "writing a null path");

        // A project-config file name is one segment inside the container, never a nested or escaping path: the
        // filesystem backend resolved this straight out of its root before the screen landed.
        for (String file : new String[]{"../../escape.properties", "nested/cache.properties", "..", ".", ""}) {
            throwsIae(() -> storage.writeConfig("screened", file, body),
                    "writing the project config file name '" + file + "'");
        }
        throwsIae(() -> storage.writeConfig("../escape", "cache.properties", body),
                "writing a project config under an invalid project name");

        equal(listDir(storage, "").isEmpty(), true, "not one refused write landed anywhere in the scope");

        // ... and the screen admits what the product actually writes: a nested, dot-prefixed console document.
        storage.writeFile(".users/login.properties", body);
        equal(storage.readFile(".users/login.properties").getProperty("v"), "1",
                "the real console membership path is addressable");
    }

    private static void configFileTreeRoundTrip(CacheStorage storage) throws Exception {
        String path = ".users/2f6b/projects.properties";
        equal(storage.readFile(path).stringPropertyNames(), Set.of(),
                "an absent config document reads as an empty Properties, never null");

        storage.writeFile(path, properties("acme", "admin", "globex", "reader"));
        equal(storage.readFile(path).getProperty("acme"), "admin", "the document reads back");
        equal(storage.readFile(path).getProperty("globex"), "reader", "every key reads back");

        storage.writeFile(path, properties("acme", "reader"));
        equal(storage.readFile(path).getProperty("acme"), "reader", "an overwrite replaces the value");
        equal(storage.readFile(path).getProperty("globex"), null,
                "and drops the keys it did not carry - writeFile replaces the whole document");

        equal(storage.readFile(".users/2f6b/absent.properties").stringPropertyNames(), Set.of(),
                "a sibling document that was never written still reads as empty");
        equal(storage.readFile("never/written/at/all.properties").stringPropertyNames(), Set.of(),
                "as does a whole branch that was never written");
    }

    private static void containerListingImmediateChildren(CacheStorage storage) throws Exception {
        storage.writeFile(".users/alpha/projects.properties", properties("v", "1"));
        storage.writeFile(".users/beta/projects.properties", properties("v", "2"));
        storage.writeFile("top.properties", properties("v", "3"));
        storage.store(new Entry("listed", "aa", "01"), new ByteArrayInputStream(ramp(4)));

        equal(sorted(listDir(storage, "")), List.of(".users", "listed"),
                "the scope root lists its immediate containers - a leaf document is not a container");
        equal(sorted(listDir(storage, ".users")), List.of("alpha", "beta"),
                "a container lists its own children and nothing deeper");
        equal(listDir(storage, ".users/alpha"), List.of(),
                "a container holding only documents lists no children");
        equal(listDir(storage, "never-written"), List.of(),
                "an absent prefix lists empty rather than failing");
        equal(listDir(storage, "top.properties"), List.of(),
                "and a leaf document is not a container to descend into");
    }

    private static void recursiveDeleteConverges(CacheStorage storage) throws Exception {
        storage.writeFile("branch/one.properties", properties("v", "1"));
        storage.writeFile("branch/deep/two.properties", properties("v", "2"));
        storage.writeFile("keep/three.properties", properties("v", "3"));
        equal(sorted(listDir(storage, "")), List.of("branch", "keep"), "both branches are present to begin with");

        storage.deleteDir("branch");
        equal(storage.readFile("branch/one.properties").stringPropertyNames(), Set.of(),
                "the deleted subtree's documents are gone");
        equal(storage.readFile("branch/deep/two.properties").stringPropertyNames(), Set.of(),
                "including the ones nested below it");
        equal(sorted(listDir(storage, "")), List.of("keep"), "and the container itself no longer lists");
        equal(storage.readFile("keep/three.properties").getProperty("v"), "3",
                "while the sibling branch is untouched - a subtree delete is not a scope wipe");

        // A tenant purge re-runs after a crash mid-delete, so a repeat and an absent path both converge silently.
        storage.deleteDir("branch");
        storage.deleteDir("never-written");
    }

    private static void versionedCreateIfAbsent(CacheStorage storage) throws Exception {
        String path = ".users/login.properties";
        isTrue(storage.writeFileVersioned(path, properties("v", "one"), null),
                "create-if-absent lands against a null expectation");
        isFalse(storage.writeFileVersioned(path, properties("v", "two"), null),
                "create-if-absent is refused while the document exists, rather than overwriting it");
        equal(storage.readFile(path).getProperty("v"), "one", "the refused write left the stored content untouched");
    }

    private static void versionedUpdateIfUnchanged(CacheStorage storage) throws Exception {
        String path = ".users/login.properties";
        isTrue(storage.writeFileVersioned(path, properties("v", "v1"), null), "the document is created");
        Object token = storage.fileVersion(path);
        isTrue(storage.writeFileVersioned(path, properties("v", "v2"), token),
                "update-if-unchanged lands against the current token");
        isFalse(storage.writeFileVersioned(path, properties("v", "v3"), token),
                "the same token no longer passes once superseded - a lost update is impossible");
        equal(storage.readFile(path).getProperty("v"), "v2", "the refused write left the stored content untouched");
    }

    private static void versionTokenOpaque(CacheStorage storage) throws Exception {
        String path = "cas/doc.properties";
        equal(storage.fileVersion(path), null, "an absent document carries no version token");

        isTrue(storage.writeFileVersioned(path, properties("v", "a"), null), "the document is created");
        Object first = storage.fileVersion(path);
        notNull(first, "a present document always carries a version token");

        isTrue(storage.writeFileVersioned(path, properties("v", "b"), first),
                "the token the backend handed out is the one it accepts");
        Object second = storage.fileVersion(path);
        notNull(second, "the token survives an update");
        if (Objects.equals(first, second)) {
            throw failure("the version token advances on every successful write (a backend whose token can repeat "
                    + "lets a writer holding the pre-update token pass a stale write off as current), but the token "
                    + "was " + first + " before and after");
        }

        isTrue(storage.writeFileVersioned(path, properties("v", "c"), second), "the current token still passes");
        isFalse(storage.writeFileVersioned(path, properties("v", "d"), first),
                "a token two versions stale is refused, not merely the immediately previous one");
        equal(storage.readFile(path).getProperty("v"), "c", "the refused write left the stored content untouched");

        storage.deleteDir("cas");
        equal(storage.fileVersion(path), null, "a deleted document carries no version token again");
    }

    private static void entryEnumerationShape(CacheStorage storage) throws Exception {
        byte[] small = ramp(8), large = ramp(300);
        storage.store(new Entry("alpha", "aa", "01"), new ByteArrayInputStream(small));
        storage.store(new Entry("alpha", "bb", "02"), new ByteArrayInputStream(large));
        storage.store(new Entry("beta", "cc", "03"), new ByteArrayInputStream(small));
        // A project's own policy document lives beside its entries and must never be enumerated as one: the reaper
        // would otherwise evict the project's configuration to reclaim space.
        storage.writeConfig("alpha", "cache.properties", properties("size", "100"));

        equal(sizes(entries(storage, "alpha")), List.of((long) small.length, (long) large.length),
                "a project enumerates exactly its stored entries, at their true byte sizes");
        equal(sizes(entries(storage, "beta")), List.of((long) small.length), "and only its own");
        equal(entries(storage, "never-stored"), List.of(),
                "an unknown project enumerates empty rather than failing");
        equal(sizes(everyEntry(storage)),
                List.of((long) small.length, (long) small.length, (long) large.length),
                "and the union across every project - the free-space reclaim's input - is the sum of them");

        equal(sorted(projects(storage)).contains("alpha"), true, "and the projects themselves enumerate");
        equal(sorted(projects(storage)).contains("beta"), true, "each of them");

        for (Stored stored : everyEntry(storage)) {
            notNull(stored.token(), "an enumerated entry carries the opaque token delete() takes");
            notNull(stored.recency(), "an enumerated entry carries a recency instant, never null");
            if (Instant.MIN.equals(stored.recency())) {
                throw failure("an enumerated entry's recency is the real access-or-write time; Instant.MIN is the "
                        + "backend's 'unknown' fallback and would sort every entry to the front of the eviction "
                        + "queue, evicting the whole cache on the first free-space pass");
            }
        }
    }

    /**
     * The paging clause, stated once and run against every backend.
     *
     * <p>This is the property whose absence was the defect. All four enumerations returned whole collections:
     * a project's entries, the scope's projects, a config container's children. There was no bound, so one call
     * materialised however much a build had put there - and, worse than the heap, there was <em>nowhere to say so</em>.
     * A backend that hit an internal cap, or a listing that stopped early, handed back a short {@code List} that a
     * caller could not distinguish from a complete one, and the callers acted on it: the size-cap sweep sorted "every
     * entry", the ttl pass deleted from "every entry", the console listed "every tenant".
     *
     * <p>So the check asserts the two halves that make a bound honest, on all three enumerations, at a page size small
     * enough that the object stores can seed past it in a handful of objects:
     * <ol>
     *   <li><b>the bound binds</b> - a call over more than {@code limit} delivers exactly {@code limit} and answers
     *       {@linkplain Traversal.Result#truncated() truncated} with a cursor. A backend that ignores the bound and delivers
     *       everything fails here even though it "returned the right names", which is precisely the pre-change
     *       behaviour;</li>
     *   <li><b>the cursor resumes</b> - driving the cursor to exhaustion delivers the whole set, each thing exactly
     *       once, in a stable order. A cursor that skipped a name would lose data silently; one that re-delivered a
     *       name would make an eviction pass count a blob twice.</li>
     * </ol>
     * Two smaller edges ride along, because both are ways a bound goes quietly wrong: a limit that exactly equals what
     * remains answers exhausted rather than costing the caller an empty round, and a non-positive limit is refused
     * rather than answered with an empty page - an empty page is indistinguishable from a drained container, so a
     * caller that passed a mis-computed zero would read "the store is empty" and, in a reclaim, "nothing to free".
     */
    private static void boundedEnumerationResumes(CacheStorage storage) throws Exception {
        // Five of each: past a page of two, so a drain takes three rounds and the middle one is neither first nor last.
        List<String> expectedProjects = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            String project = "paged" + index;
            expectedProjects.add(project);
            storage.store(new Entry(project, "aa", "0" + index), new ByteArrayInputStream(ramp(4)));
            storage.writeFile(".users/paged" + index + "/projects.properties", properties("v", "1"));
        }
        List<String> expectedEntries = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            expectedEntries.add("b" + index);
            storage.store(new Entry("paged0", "b" + index, "01"), new ByteArrayInputStream(ramp(4)));
        }
        // The project's own policy document sits among its entries and is not one, so a backend that pages by raw
        // object order has to keep reading past it rather than let it eat a slot of the caller's bound.
        storage.writeConfig("paged0", "cache.properties", properties("size", "100"));

        // The scope now holds at least five projects (the console's own .users tree is a top-level container too, so
        // a backend may legitimately count six), five containers under .users, and six entries under paged0 - its own
        // "aa/0*" plus the five "b*/01". Each check states the minimum it seeded rather than an exact total, so it
        // pins the paging without pinning what a backend counts as a top-level container.
        paged("projects", 5, (cursor, limit, sink) -> storage.projects(cursor, limit, sink));
        paged("listDir(.users)", 5, (cursor, limit, sink) -> storage.listDir(".users", cursor, limit, sink));
        List<Long> sizes = new ArrayList<>();
        paged("entries(paged0)", 6, (cursor, limit, sink) ->
                storage.entries("paged0", cursor, limit, stored -> {
                    sizes.add(stored.size());
                    sink.accept(String.valueOf(stored.token()));
                }));
        equal(sizes.size() >= 6, true, "the drained entry pages carry the entries themselves, not just their keys");
    }

    /**
     * Drive one paged enumeration through the halves above. {@code seeded} is the minimum this check put in scope, so
     * a backend that legitimately counts one more container than the check placed there still runs the property - what
     * is compared is the enumeration against <em>itself</em> at two different page widths, never against a shape only
     * one backend has. The minimum is what keeps that from going vacuous: a backend answering nothing at all would
     * otherwise agree with itself perfectly.
     */
    private static void paged(String what, int seeded, Paged enumeration) throws IOException {
        // 0. The scope as one generous call sees it - the reference the paged runs are compared against.
        List<String> whole = new ArrayList<>();
        Traversal.Result all = enumeration.page(null, 1_000, whole::add);
        isTrue(all.exhausted(), what + " answers exhausted when its bound is far above what the scope holds");
        int expected = whole.size();
        if (expected < seeded) {
            throw failure(what + " must see at least the " + seeded + " things this check seeded, or the comparisons "
                    + "below hold vacuously - it saw " + expected);
        }

        // 1. The bound binds, and the truncation is a value rather than a silence.
        List<String> first = new ArrayList<>();
        Traversal.Result page = enumeration.page(null, 2, first::add);
        equal(first.size(), 2, what + " delivers exactly the caller's bound when more than that is in scope");
        isTrue(page.truncated(), what + " reports a bound it reached as TRUNCATED - a caller cannot otherwise tell a "
                + "page from a complete listing, which is the whole defect");
        isTrue(page.cursor().isPresent(), what + " carries the continuation cursor its truncation promises");
        equal(page.delivered(), 2L, what + " counts what it delivered");

        // 2. The cursor resumes: the whole set, in the same order, once each, driven to exhaustion.
        List<String> drained = new ArrayList<>(first);
        String cursor = page.cursor().orElseThrow();
        for (int round = 0; round <= expected; round++) {
            Traversal.Result next = enumeration.page(cursor, 2, drained::add);
            if (next.exhausted()) {
                cursor = null;
                break;
            }
            cursor = next.cursor().orElseThrow();
        }
        if (cursor != null) {
            throw failure(what + " never exhausted: a cursor that makes no forward progress is a livelock dressed up "
                    + "as paging, and a caller draining it would never finish");
        }
        equal(drained, whole, what + " delivers the whole scope across its pages, in the one order a cursor can "
                + "resume against - a name dropped between two pages is data a size cap never counts and a reclaim "
                + "never frees, and one delivered twice is a blob an eviction pass counts twice");
        equal(new LinkedHashSet<>(drained).size(), expected,
                what + " delivers each thing exactly once across its pages, never re-delivering the boundary");

        // 3. A bound met exactly at the end is exhausted, not a truncation the caller pays an empty round for.
        List<String> exactly = new ArrayList<>();
        Traversal.Result exact = enumeration.page(null, expected, exactly::add);
        equal(exactly, whole, what + " delivers everything when the bound allows exactly that much");
        isTrue(exact.exhausted(), what + " answers exhausted when the bound was met exactly at the end of the scope, "
                + "rather than sending the caller back for a page that is not there");
        isTrue(exact.cursor().isEmpty(), what + " carries no cursor when it exhausted the scope");

        // 4a. The largest legal bound is answered, not overflowed. Every backend in this family collects one more
        // than the caller's bound to learn whether more remains, and at Integer.MAX_VALUE that addition wrapped:
        // the underlying page came back empty and the read died on a bare NoSuchElementException, in a place that
        // named neither the bound nor the caller. MAX_VALUE is a positive, legal bound and the obvious way to ask
        // for everything, so it must answer everything.
        List<String> unbounded = new ArrayList<>();
        Traversal.Result largest = enumeration.page(null, Integer.MAX_VALUE, unbounded::add);
        equal(unbounded, whole, what + " answers the whole scope for the largest legal bound");
        isTrue(largest.exhausted(), what + " exhausts at Integer.MAX_VALUE rather than overflowing the probe it "
                + "adds to the caller's bound");

        // 4. A non-positive bound is refused, never answered with an empty page a caller reads as an empty store.
        for (int limit : new int[]{0, -1, Integer.MIN_VALUE}) {
            throwsIae(() -> enumeration.page(null, limit, _ -> {
            }), what + " called with the non-positive bound " + limit);
        }
    }

    /** One paged enumeration under test, reduced to the shape all three share. */
    @FunctionalInterface
    private interface Paged {
        Traversal.Result page(String cursor, int limit, Consumer<String> names) throws IOException;
    }

    private static void deleteRemovesEnumeratedBlob(CacheStorage storage) throws Exception {
        Entry doomed = new Entry("dropped", "aa", "01"), kept = new Entry("dropped", "bb", "02");
        storage.store(doomed, new ByteArrayInputStream(ramp(8)));
        storage.store(kept, new ByteArrayInputStream(ramp(16)));

        Stored stored = entries(storage, "dropped").stream()
                .filter(candidate -> candidate.size() == 8)
                .findFirst()
                .orElseThrow(() -> failure("the entry to delete must be enumerable before it is deleted"));
        storage.delete(stored);

        isFalse(storage.exists(doomed), "the deleted entry is gone");
        isTrue(storage.exists(kept), "and only it - its neighbour in the same project survives");
        equal(sizes(entries(storage, "dropped")), List.of(16L), "the enumeration no longer offers the deleted blob");

        // The reaper selects a batch, then deletes; a concurrent pass may have drained the same blob first, and the
        // reaper re-runs after a crash, so a repeated delete must converge rather than throw.
        storage.delete(stored);
        isTrue(storage.exists(kept), "a repeated delete of an already-deleted blob converges and touches nothing");
    }

    private static void stampRecordsRecencyWithoutDamage(CacheStorage storage) throws Exception {
        Entry entry = new Entry("stamped", "aa", "01");
        byte[] body = ramp(24);
        storage.store(entry, new ByteArrayInputStream(body));
        Stored before = only(entries(storage, "stamped"));
        CacheStorage.Recency unstamped = storage.recency(entry).orElseThrow(
                () -> failure("an entry that exists has a recency - its own time, when it was never stamped"));
        isFalse(unstamped.stamped(), "an entry never stamped answers with its own time, not a stamp");
        equal(unstamped.at(), before.recency(),
                "and that time is the one the enumeration reports for it, so a point answer and a sweep agree");

        Instant first = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3600);
        storage.stamp(entry, first, null);

        isTrue(storage.exists(entry), "a stamped entry still exists");
        equal(read(storage, entry), body, "and its body is untouched - a stamp is recency beside the entry, not a rewrite");
        equal(storage.recency(entry).orElse(null), new CacheStorage.Recency(first, true),
                "the stamp reads back as the instant it recorded, and as a stamp");
        Stored after = only(entries(storage, "stamped"));
        equal(after.size(), before.size(), "its size is unchanged");
        equal(after.recency(), first, "the enumeration carries the stamp as the entry's recency, over any backend time");

        Instant second = first.plusSeconds(1800);
        storage.stamp(entry, second, first);
        equal(storage.recency(entry).orElse(null), new CacheStorage.Recency(second, true),
                "a renewal supersedes the stamp it was told about");
        equal(only(entries(storage, "stamped")).recency(), second, "and the enumeration follows it");

        // Never a write for an entry that is not there: a stamp with no entry behind it would be invisible to the
        // enumeration the reaper drives and therefore immortal.
        Entry absent = new Entry("stamped", "bb", "02");
        storage.stamp(absent, second, null);
        isFalse(storage.exists(absent), "stamping an absent entry creates nothing");
        equal(sizes(entries(storage, "stamped")), List.of(24L), "and adds nothing to the enumeration");
        isTrue(storage.recency(absent).isEmpty(), "and an entry that is not there has no recency at all");
    }

    private static void capacityUnlimitedOrReal(CacheStorage storage) throws Exception {
        // Asked FIRST, on a scope that holds nothing yet - which is where a capacity probe is most likely to be
        // unable to ask, and where the filesystem backend used to answer the unlimited sentinel: a scoped view's
        // directory does not exist until something is written to it, Files.getFileStore threw, and the catch returned
        // Long.MAX_VALUE. That is the one answer that switches the free-space sweep off entirely, so a broken volume
        // - or merely a brand-new tenant - read as infinite free space and nothing was ever reclaimed. "Unlimited" is
        // a backend's standing declaration that it has no capacity limit; it may never also mean "I could not ask".
        boolean unlimited = shape(storage, "before anything is stored");

        storage.store(new Entry("sized", "aa", "01"), new ByteArrayInputStream(ramp(8)));
        equal(shape(storage, "once the scope holds an entry"), unlimited,
                "a backend either has a capacity limit or it does not; the answer's shape cannot depend on whether "
                        + "the scope happens to hold anything yet, or the sweep is off for exactly as long as a "
                        + "tenant is empty - which is exactly while it is cheapest to keep it that way");

        // The sweep asks on every pass; the answer's shape may not flip under it.
        equal(shape(storage, "on a repeated call"), unlimited, "the capacity answer keeps its shape across calls");
    }

    /** The capacity pair's shape: {@code true} for the documented unlimited sentinel, {@code false} for a real
     *  bounded pair. A mixture, a negative reading or a probe that throws is a failure here rather than a shape. */
    private static boolean shape(CacheStorage storage, String when) {
        long usable = storage.usableSpace(), total = storage.totalSpace();
        if (usable < 0 || total < 0) {
            throw failure("capacity is never negative, but " + when + " usableSpace was " + usable
                    + " and totalSpace " + total);
        }
        boolean unlimited = usable == Long.MAX_VALUE && total == 0;
        boolean bounded = usable > 0 && total > 0 && usable <= total;
        if (!unlimited && !bounded) {
            throw failure("usableSpace/totalSpace must be a consistent pair - the documented unlimited sentinel "
                    + "(Long.MAX_VALUE and 0) for a backend with no capacity limit, or a real bounded pair for one "
                    + "with a volume behind it. A mixture is read by the free-space sweep as a volume that is "
                    + "already full, which evicts the whole cache. " + when + " usableSpace was " + usable
                    + " and totalSpace " + total);
        }
        return unlimited;
    }

    /**
     * The transport screen, driven through the resolution path a deployment takes rather than through a live storage:
     * the fixture's own config - the one it reaches its emulator over plaintext {@code http} with - must be refused
     * when its opt-out is taken away, and honoured when it is put back.
     *
     * <p>This is the property whose absence was the defect: the {@code s3} and {@code gcs} cache backends accepted an
     * {@code http://} endpoint in silence, where the artifact-store siblings had refused one since they
     * were written, so a mistyped scheme put the backend's credentials and every cached byte on a plaintext wire with
     * no operator signal at all. {@code azure-blob} carried the same gap through its connection string, where the
     * account key and the transport selection travel in one value.
     *
     * <p>The fixture supplying the config is also what makes the check honest in the other direction: because the
     * emulators are only reachable over {@code http}, the whole containerised leg is proof that the opt-out really is
     * an opt-out, and the negative leg here is proof the screen really bites.
     */
    private static void plaintextEndpointRefused(CacheStorageFixture fixture) {
        CacheStorageFixture.Plaintext plaintext = fixture.plaintext().orElseThrow(() -> failure(
                "the '" + fixture.backend() + "' fixture runs PLAINTEXT_ENDPOINT_REFUSED but declares no plaintext() "
                        + "config; a backend with an endpoint must declare the config it reaches it over and the key "
                        + "that opts out, and a backend with no endpoint must exclude the property with a reason"));
        Map<String, String> allowed = new LinkedHashMap<>(plaintext.config());
        String optOut = allowed.remove(plaintext.allowInsecureKey());
        if (!Boolean.parseBoolean(optOut)) {
            throw failure("the '" + fixture.backend() + "' fixture's declared config must itself carry "
                    + plaintext.allowInsecureKey() + "=true - its emulator is reachable only over plaintext http, so "
                    + "a fixture that resolves without the opt-out is proof the screen is not applied at all, but "
                    + "the value was " + optOut);
        }

        String message = throwsIse(() -> CacheStorageProvider.resolve(allowed::get),
                "resolving the '" + fixture.backend() + "' backend against its plaintext endpoint with the opt-out "
                        + "removed - credentials and cached bytes would travel in clear with no operator signal");
        if (!message.contains(plaintext.allowInsecureKey())) {
            throw failure("the refusal must name the opt-out key '" + plaintext.allowInsecureKey() + "', or an "
                    + "operator running a local emulator has no way to act on it, but the message was: " + message);
        }

        // ... and the screen is an opt-out, not a ban: the same config resolves once the operator sets it.
        notNull(CacheStorageProvider.resolve(plaintext.config()::get),
                "the very same plaintext endpoint resolves once the opt-out is explicitly set");
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    /**
     * The page width every other check in this kit drains at. Deliberately tiny: it means the whole contract - the
     * isolation checks, the delete and touch checks, the enumeration-shape check - runs through a <em>resumed</em>
     * enumeration rather than a single page, so a backend whose cursor drops or repeats a name fails the check that
     * happens to notice first rather than only the one check written for paging.
     */
    private static final int DRAIN = 2;

    /** How many rounds a drain may take before it is called a livelock rather than a large store. Every scope this kit
     *  builds holds a handful of things, so a drain that has not finished by here is a cursor making no progress. */
    private static final int ROUNDS = 64;

    /** Every project name in the scope, drained through the paged enumeration. */
    private static List<String> projects(CacheStorage storage) throws IOException {
        return drain("projects", (cursor, sink) -> storage.projects(cursor, DRAIN, sink));
    }

    /** Every immediate child container of {@code prefix}, drained through the paged enumeration. */
    private static List<String> listDir(CacheStorage storage, String prefix) throws IOException {
        return drain("listDir(" + prefix + ")", (cursor, sink) -> storage.listDir(prefix, cursor, DRAIN, sink));
    }

    /** Every stored entry of one project, drained through the paged enumeration. */
    private static List<Stored> entries(CacheStorage storage, String project) throws IOException {
        List<Stored> collected = new ArrayList<>();
        String cursor = null;
        for (int round = 0; round < ROUNDS; round++) {
            Traversal.Result result = storage.entries(project, cursor, DRAIN, collected::add);
            if (result.exhausted()) {
                return collected;
            }
            cursor = result.cursor().orElseThrow();
        }
        throw failure("draining the entries of '" + project + "' did not exhaust in " + ROUNDS + " rounds");
    }

    /**
     * The union of every project's entries - what the free-space reclaim composes for itself, project at a time.
     *
     * <p>The SPI deliberately offers no whole-store sweep to call instead. It used to ({@code allEntries}), and that
     * one method was the only place in this interface that asked a backend to materialise every entry of every
     * project in heap - on the one code path, a low-disk reclaim, where an OOM lands exactly when the node is already
     * degraded. Nothing in the product called it; the reclaim already streamed project-at-a-time, precisely to avoid
     * it. So the union is composed here, out of the two bounded enumerations, the same way its one real caller does.
     */
    private static List<Stored> everyEntry(CacheStorage storage) throws IOException {
        List<Stored> all = new ArrayList<>();
        for (String project : projects(storage)) {
            all.addAll(entries(storage, project));
        }
        return all;
    }

    private static List<String> drain(String what, Enumeration enumeration) throws IOException {
        List<String> collected = new ArrayList<>();
        String cursor = null;
        for (int round = 0; round < ROUNDS; round++) {
            Traversal.Result result = enumeration.page(cursor, collected::add);
            if (result.exhausted()) {
                return collected;
            }
            cursor = result.cursor().orElseThrow();
        }
        throw failure("draining " + what + " did not exhaust in " + ROUNDS + " rounds - a cursor that makes no "
                + "forward progress is a livelock dressed up as paging");
    }

    @FunctionalInterface
    private interface Enumeration {
        Traversal.Result page(String cursor, Consumer<String> names) throws IOException;
    }

    private static byte[] read(CacheStorage storage, Entry entry) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        storage.read(entry, out);
        return out.toByteArray();
    }

    private static Properties properties(String... pairs) {
        Properties properties = new Properties();
        for (int index = 0; index < pairs.length; index += 2) {
            properties.setProperty(pairs[index], pairs[index + 1]);
        }
        return properties;
    }

    /** The enumerated sizes, sorted, so a check compares a set of blobs without depending on discovery order. */
    private static List<Long> sizes(List<Stored> entries) {
        return entries.stream().map(Stored::size).sorted().toList();
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().sorted().toList();
    }

    private static Stored only(List<Stored> entries) {
        if (entries.size() != 1) {
            throw failure("expected exactly one enumerated entry but found " + entries.size());
        }
        return entries.getFirst();
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

    // One line each over AssertJ, which this kit may use since it moved under test/. They stay as helpers rather
    // than being inlined at their hundred-odd call sites: the order here is (actual, expected) and AssertJ's is
    // assertThat(actual).isEqualTo(expected), so an inlining that transposed a pair would still pass - equality is
    // symmetric - and only the message would lie. See StoreContract, which took the same shape.

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

    /** A body that must fail with an {@link IllegalStateException} - the SPI's resolution-refusal shape (&sect;9),
     *  the one {@code Providers.exclusiveWithDefault} propagates unchanged out of a provider's {@code create}.
     *  Returns the message, so a check can also assert the refusal is actionable. */
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

}
