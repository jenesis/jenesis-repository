package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.Limits;

/**
 * Turns an uploaded artifact into compliance {@link ComplianceGate.Subject}s for one ecosystem - the artifact's
 * own coordinate and licenses, and, where the format allows, those of its transitive dependencies - so the
 * publishing quality gate is pluggable per format. The shared {@link ComplianceGate} evaluates every subject
 * against the deployment's license and vulnerability policy; how to read a coordinate, its licenses, and its
 * dependency tree out of an artifact (a Maven POM, an npm {@code package.json}, a NuGet {@code .nuspec}, a Go
 * {@code go.mod}) is the format-specific part, supplied by an inspector discovered with {@link
 * java.util.ServiceLoader}. An artifact no inspector claims (a checksum, a metadata file) is not gated.
 *
 * <h2>Contract</h2>
 *
 * <ol>
 * <li><b>Thread-safety.</b> One instance per deployment, created once by {@link ServiceLoader} and called
 * concurrently from every publish and proxy request thread. An implementation must be safe to call concurrently:
 * fields are immutable and per-call state is method-local. A reusable parser or scanner may be held in a field only
 * when it is itself concurrency-safe. {@link ManifestSubjectBuilder} is immutable for exactly this reason.</li>
 *
 * <li><b>Idempotency / replay.</b> Inspection is a pure function of its arguments: the same {@code path} and the
 * same bytes must yield equal subjects however often they are inspected, on either leg, in any order. A publish is
 * re-screened on migration and re-inspection, so a result that drifted between two calls over identical input would
 * change a stored verdict without the artifact having changed. That equally forbids <em>carrying</em> a result over:
 * an inspector handed the same {@link Content} handle twice re-opens it and reads it again, and must never answer the
 * second call from what it kept of the first - a spooled handle is re-openable (clause 5) precisely so the body need
 * not be held, and an inspector that held a drained stream would answer the hardened leg's second pass off state
 * whose provenance nothing checks.</li>
 *
 * <li><b>Absence sentinel.</b> An empty {@code List} - never {@code null} - means "this leg carries nothing to
 * assess" (an index document, a checksum sibling, a non-publish request body). It is a positive claim that the
 * artifact was understood and declares nothing, so it must never be used to paper over a failed parse; see clause
 * 7 - <em>nor</em> a read a bound stopped, which is why the fully-spooled leg answers an {@link Inspection} that says
 * which of the two an empty list is (see clause 12: a screen cannot tell "I looked and there is nothing
 * there" from "I could not look" by the emptiness of the answer, and the empty one is what gets ALLOWed). It is also
 * what a claimed path that is not one of the format's own publish or download <em>routes</em> answers:
 * an inspector re-derives the route from the path and returns the sentinel for anything else it claims, rather than
 * parsing every body that reaches it as a package - the generated index documents that live under a format's prefix
 * would otherwise be refused as malformed packages, which is a &sect;13 divergence from every peer even though it
 * errs closed.</li>
 *
 * <li><b>Selection failure.</b> Not applicable: inspectors are an additive family, never explicitly selected by
 * name. Every discovered inspector that {@link #handles} a path runs, and an artifact no inspector claims is simply
 * not gated.</li>
 *
 * <li><b>Streaming.</b> No artifact is ever materialised whole. The {@code byte[]} legs receive at most a
 * {@link #PREFIX_INSPECTION_LIMIT} front prefix of the body, which is why an inspector deriving a whole-artifact
 * fact (a digest, a length) must first ask {@link BoundedBodyReader#completeArtifact(byte[])} rather than assume it
 * holds the artifact. {@link Content} is a re-openable handle to a spooled body and must be streamed, never drained
 * into an array; {@link Lookup#fetchBounded(String, int)} is the seam for reading a bounded fact off a companion so
 * a small referrer cannot pull a multi-gigabyte sibling into heap.</li>
 *
 * <li><b>Tenant scoping.</b> The {@code path} and the {@link Lookup} are already scoped to the publishing tenant by
 * the screen that called in. An inspector reads siblings only through that {@code Lookup} and derives no key of its
 * own, so it cannot reach another tenant's artifacts.</li>
 *
 * <li><b>Error visibility.</b> "Could not parse the artifact I claimed" is
 * {@link MalformedArtifactException} - fail-closed, the screens hold the publish and refuse the proxied body - and
 * is strictly distinct from the empty result of clause 3. A cap reached while reading an <em>optional</em>
 * declaration (a licence beside a coordinate that screens from the path) may degrade to "declares nothing", because
 * losing a licence can only under-declare, never hide a coordinate or a hold; a cap reached while reading the
 * artifact's <em>identity</em> must throw. No other failure is swallowed.</li>
 *
 * <li><b>Read purity.</b> Inspection performs no external I/O: no network fetch, no filesystem access, no store
 * write. The only reads are the bytes handed in and siblings fetched through {@link Lookup}. (The Maven inspector's
 * best-effort transitive resolution is the single declared exception, and it degrades to the artifact's own
 * coordinate when resolution fails.)</li>
 *
 * <li><b>Staleness.</b> Not applicable: an inspector holds no cached or externally-sourced state to be stale.</li>
 *
 * <li><b>Lifecycle / ownership.</b> {@link ServiceLoader}-created through {@link #all()}, and reused for the life of
 * the deployment. An inspector owns no threads, clients or connections and has nothing to close.</li>
 *
 * <li><b>Ordering / concurrency.</b> Every claiming inspector runs over the same bounded read; discovery order must
 * not change any inspector's result, and no inspector may assume it is the only one on a path (a format inspector
 * and the content scanners routinely claim the same artifact). Subjects within one result keep the inspector's own
 * order.</li>
 *
 * <li><b>Bounded work.</b> Every read an inspector makes is bounded, and the bounds are the shared tiers rather than
 * per-format inventions:
 * <ul>
 *   <li>the <b>manifest tier</b> {@link build.jenesis.repository.store.ArchiveInflation#largestEntry()} - the most of one embedded declaration
 *       that is materialised while a coordinate or licence is read off it;</li>
 *   <li>the <b>prefix tier</b> {@link #PREFIX_INSPECTION_LIMIT} - the most of a body that reaches the {@code byte[]}
 *       legs;</li>
 *   <li>the <b>archive-walk tier</b> {@link build.jenesis.repository.store.ArchiveWalk#largestWalk()} - the most of
 *       an archive that is fed to an entry walk looking for that declaration, or a body-relative ceiling from
 *       {@link build.jenesis.repository.store.ArchiveWalk#largestWalk(long, long)} for the layouts whose manifest may
 *       follow a large payload; and</li>
 *   <li>the <b>full-body tier</b> {@link #FULL_BODY_INSPECTION_LIMIT} - the most of a fully-spooled body an inspector
 *       that overrides {@link #inspectArtifact(String, Content, Lookup)} may consume.</li>
 * </ul>
 * They are a <em>ladder</em>, and the order carries meaning rather than being an accident of four independent
 * numbers: one embedded declaration cannot outgrow the prefix that carries it, and the full-body tier must sit
 * strictly <em>above</em> the prefix tier, because a whole-artifact leg that read less far than the bounded-prefix leg
 * would buy nothing and would make the screens' "was the body seen whole?" test - which is written against the prefix
 * tier - untrue for its inspector. The ladder is asserted, not commented: {@code BoundedInspectionTest} pins the four
 * constants' order and {@code InspectorContract} holds every full-body fixture's declared ceiling above the prefix
 * tier.
 * <p>A limit that is genuinely the format's own (a gemspec bigger than the manifest tier, a binary header's index and
 * store sanity bounds, a publish frame's length prefix) stays an explicit constant at its call site. Reaching a
 * bound is an outcome, never a shorter value: {@link build.jenesis.repository.store.ArchiveInflation#entry(InputStream, int)} yields no
 * value at all rather than a prefix that might still parse, and
 * {@link build.jenesis.repository.store.ArchiveWalk.Found#truncated()} says that a walk stopped early rather than
 * reporting it as an archive that declares nothing - and it carries no value at all when it does, so an archive cannot
 * plant a decoy declaration in front of the ceiling and have that read instead. Which of the two clause-7 dispositions
 * a bound then takes is spelt at the call site by the accessor it uses -
 * {@link build.jenesis.repository.store.ArchiveWalk.Found#orNull()} degrades an optional declaration,
 * {@link BoundedArchive#required(build.jenesis.repository.store.ArchiveWalk.Found, String, String)} refuses an
 * identity-bearing one - so an inspector picks a side rather than re-deriving one per format. There is no cancellation or timeout seam - boundedness, not
 * interruption, is what keeps an inspection from running away.
 * <p>Which tier an inspector reads at is decided by one thing and is <em>observable</em>: an inspector that does not
 * override {@link #inspectArtifact(String, Content, Lookup)} is a prefix-tier inspector and can never pull more than
 * {@link #PREFIX_INSPECTION_LIMIT} bytes off a body, because the default bridge is its only route to one; an
 * inspector that does override it reads to {@link #FULL_BODY_INSPECTION_LIMIT} and must not read past that. Both are
 * asserted per implementation against a counting {@link Content}, not by reading the source, so an inspector that
 * quietly grew a whole-body read is caught by the bytes it pulled.
 * <p>What a full-body inspector does when it <em>reaches</em> its tier is the same question clause 7 answers for every
 * other bound, and it is answered by what the read carries: a content scan's ceiling can only cost it a finding, so it
 * degrades rather than refusing. <b>That degradation is reported, not inferred.</b> The fully-spooled leg
 * answers an {@link Inspection}, whose {@link Inspection#complete()} says whether the read ran out of body or out of
 * bound - so a bound-stopped read is distinguishable from a clean empty one, per inspector, at the call the screen
 * actually made. It has to be reported rather than derived from the body's length, because the screens run several
 * inspectors over one body and merge their subjects: a screen that infers completeness from the merged list being
 * empty is blind to a bound-stopped read the moment any <em>other</em> inspector produced a subject, and blind to a
 * bound that is not a byte count at all (an entry ceiling, a finding ceiling, a container it could not decode).
 * Reporting it is what makes the ladder's guarantee - a body long enough for the full-body tier to bind is necessarily
 * longer than the prefix tier - the floor of the screens' completeness test rather than the whole of it.</li>
 *
 * <li><b>Durability / delivery.</b> Not applicable: inspection is a pure read that commits nothing. The screen that
 * called in owns the commit point and the disposition it derives from these subjects.</li>
 * </ol>
 */
public interface QualityInspector {

    /** Every inspector installed on this deployment, discovered with {@link ServiceLoader}. The loading lives here,
     *  next to the contract and its {@code uses} declaration, so a consumer names no discovery mechanism. */
    static List<QualityInspector> all() {
        return ServiceLoader.load(QualityInspector.class).stream().map(ServiceLoader.Provider::get).toList();
    }

    /** Whether this inspector understands the artifact at the given request path. */
    boolean handles(String path);

    /**
     * Whether this inspector's handling makes the content at the path <em>claimed</em>: read and screened as a
     * package it turns the body into, rather than admitted as unclaimed content screened from its path alone. A
     * package inspector claims what it handles, which is the default. A content-scan inspector - one that reads what
     * sits beside an artifact, a signature or a bundle - claims a path no layout places on a coordinate only when
     * something is actually stored beside it, which {@code siblings} answers with a point read: raw content with
     * nothing beside it stays unclaimed and is never read, and a bundle published beside a raw file is assessed
     * without switching the deny-list screening of the file off. The screens read this, never {@link #handles}, to
     * decide what is unclaimed.
     */
    default boolean claims(String path, Lookup siblings) {
        return handles(path);
    }

    /**
     * The compliance subjects extracted from the artifact - itself and, where resolvable, its transitive
     * dependencies - or an empty list if it carries nothing to assess. {@code lookup} fetches an already-published
     * sibling by request path, so an inspector can read a companion descriptor (a jar reading its sibling POM)
     * without depending on the storage layer.
     */
    List<ComplianceGate.Subject> inspect(String path, byte[] content, Lookup lookup) throws IOException;

    /**
     * The compliance subject for the artifact itself only - its own coordinate and licenses - without resolving any
     * transitive dependencies. The proxy fetch path uses this: resolving a proxied POM's whole closure on every
     * read would be ruinous, and is needless, because each dependency is itself proxied and gated as it flows
     * through. So the tree is covered artifact by artifact rather than tree by tree.
     */
    List<ComplianceGate.Subject> inspectArtifact(String path, byte[] content, Lookup lookup) throws IOException;

    /**
     * The request-path prefix whose <em>already-held</em> artifacts should be re-assessed now that {@code path} has
     * published - or empty, the default, when this publish completes nothing for anything else.
     *
     * <h2>Why an inspector gets to say this</h2>
     *
     * An ecosystem whose publish is one request per coordinate has nothing to declare here: everything an inspector
     * can read about the artifact is in the bytes it was handed. Maven is the counter-example, and it is not an
     * exotic one - a deploy is several requests, and the client sends the <b>jar first</b>. At the moment the jar is
     * screened, the POM that carries the coordinate's licence is not in the store, so the licence reads as unknown.
     * On a deployment that has set {@code license-unknown} above {@code ALLOW} that holds the main artifact of an
     * ordinary release; on one that has not, it records the release as unlicensed. The declaration was never
     * missing; it had not arrived yet - which is why the shipped default is {@code ALLOW} and why the fact still
     * has to converge either way.
     *
     * <p>So the inspector that knows a format's publish is multi-request is the one that gets to say which stored
     * neighbours a given document completes. Answering a <em>prefix</em> rather than a list of paths is deliberate:
     * an inspector cannot enumerate the store and must not try, while the screen that calls this already can, and
     * knows which of those neighbours are actually held.
     *
     * <h2>What answering costs, and what it cannot do</h2>
     *
     * A non-empty answer buys each held neighbour one re-assessment through the ordinary publish gate. It is not an
     * override and not a review: a neighbour is released only if the gate now answers {@code ALLOW} on its own
     * stored bytes, so one still held for any other reason - a denied licence, an advisory, a deny-listed coordinate
     * - stays exactly where it is. The idempotency clause is intact, because nothing here re-decides the same
     * evidence: the evidence itself changed when the sibling landed.
     *
     * @param path the request path just published
     */
    default Optional<String> completes(String path) {
        return Optional.empty();
    }

    /** The most bytes a spool-backed inspection reads from the front of a fully-spooled body into heap when it bridges
     *  to the {@code byte[]} prefix path - the same 32 MiB bound the publish and proxy screens cap a claimed artifact's
     *  heap materialisation at (they read it from here rather than declaring it again). A default-bridged inspector
     *  reads its declaration from this front prefix (a POM, an npm {@code package.json}, a NuGet {@code .nuspec} sit at
     *  the front by the format's nature); a full-body-tier inspector reads past it by overriding
     *  {@link #inspectArtifact(String, Content, Lookup)}, up to {@link #FULL_BODY_INSPECTION_LIMIT}. */
    int PREFIX_INSPECTION_LIMIT = 32 * 1024 * 1024;

    /**
     * The key an operator raises or lowers {@link #prefixInspectionLimit()} with.
     *
     * <p>Deployment-global and read live, exactly as {@code jenreg.archive.largest-walk} beside it is, and for the
     * same reason: it is a per-process budget on heap taken on the publish thread, and a store round-trip per
     * artifact to learn it would be absurd. A registry whose artifacts genuinely carry their declaration further in
     * than 32 MiB should be able to say so rather than silently screening a prefix - the truncated case is handled
     * (the screen falls back to a path-derived subject and records the incomplete screening), but "handled" is not
     * the same as "what the operator wanted".
     */
    String PREFIX_INSPECTION_LIMIT_KEY = "jenreg.inspection.prefix-bytes";

    /**
     * The configured prefix tier - {@link #PREFIX_INSPECTION_LIMIT} unless an operator set
     * {@link #PREFIX_INSPECTION_LIMIT_KEY}.
     *
     * <p>Every reader goes through here rather than the constant, because the constant is the SPI's shared tier: an
     * inspector is written against it and the screen compares against it to decide whether the inspectors saw the
     * artifact WHOLE. Two readers disagreeing about the number would make that completeness test answer about a
     * different body than the one that was read.
     *
     * @throws IllegalArgumentException when the key is set to something that is not a positive number of bytes - an
     *         operator who raised a cap and got the spelling wrong must not be left believing they raised it
     */
    static int prefixInspectionLimit() {
        return Limits.positive(PREFIX_INSPECTION_LIMIT_KEY, PREFIX_INSPECTION_LIMIT);
    }

    /** The configured full-body tier, twice the prefix tier unless set outright. */
    String FULL_BODY_INSPECTION_LIMIT_KEY = "jenreg.inspection.full-body-bytes";

    /** The configured full-body ceiling - twice {@link #prefixInspectionLimit()} unless an operator overrides it. */
    static long fullBodyInspectionLimit() {
        // Twice the prefix tier by default, so raising the prefix raises this with it - the relationship the compiled
        // constants already express - while an operator who needs the two decoupled can still say so outright.
        return Limits.isSet(FULL_BODY_INSPECTION_LIMIT_KEY)
                ? Limits.positive(FULL_BODY_INSPECTION_LIMIT_KEY, FULL_BODY_INSPECTION_LIMIT)
                : 2L * prefixInspectionLimit();
    }

    /**
     * What a deployment does with an artifact that runs past {@link #prefixInspectionLimit()}.
     *
     * <p>Until this existed there was one answer and nobody had chosen it: the screen read the prefix, the
     * inspectors concluded from a head, and a claimed artifact whose declaration sat beyond the bound fell back to a
     * coordinate derived from its own request path. That is sound as far as it goes - it never reads as a clean
     * screening - but it makes an artifact's verdict depend on where in its archive the format happened to put a
     * declaration, and for the formats that store theirs at the back it is the wrong verdict on the shipped
     * defaults. Measured 2026-09-15 through real clients: a 1.5 GiB NuGet package and a {@code .deb} of the same
     * size both published and were then held, because the signature material each carries INSIDE itself sits past
     * the bound and an unreadable signature is scored with the untrusted dial.
     *
     * <p>So the bound stays what it always was - the most an inspector is ever handed in one heap array - and the
     * question of what to do when an artifact exceeds it becomes the operator's:
     *
     * <ul>
     *   <li>{@link #STREAM} - screen it anyway, from the store, through
     *       {@link #inspectArtifact(String, Content, Lookup)}. An inspector reads it as a stream under the full-body
     *       tier, so a declaration anywhere in the archive is reachable and no heap array grows. This is the
     *       default, because concluding from a head is what produced the holds above.</li>
     *   <li>{@link #QUARANTINE} - hold it, saying it is too large to screen. For a deployment that would rather
     *       review a big artifact than pay to stream it.</li>
     *   <li>{@link #REJECT} - refuse it for the same reason, which is the answer for a registry that has decided
     *       artifacts of that size do not belong in it.</li>
     * </ul>
     *
     * <p>The two that are not {@link #STREAM} are deliberately about the artifact's SIZE and say so: neither claims
     * anything about its licence, its signature or its contents, because nothing read them.
     */
    enum Oversized {

        /** Stream it from the store and screen it whole, bounded by {@link #fullBodyInspectionLimit()}. */
        STREAM,

        /** Hold it for review, naming its size and the bound - nothing was screened and nothing pretends it was. */
        QUARANTINE,

        /** Refuse the publish, naming its size and the bound. */
        REJECT
    }

    /** The setting an operator chooses {@link Oversized} with, by the bare name every settings surface keys on. */
    String OVERSIZED_KEY = "inspection.oversized";

    /**
     * The value a deployment that says nothing gets, and the ONE place it is written.
     *
     * <p>A {@code String} rather than the enum constant because the settings catalogue is extracted from compiled
     * constants: {@code Oversized.STREAM.name()} is a method call, so a row keyed to it would render its default
     * blank and the generated reference would describe a product nobody ships. {@code LicensePolicy.UNKNOWN_DEFAULT}
     * is the same shape for the same reason.
     */
    String OVERSIZED_DEFAULT = "STREAM";

    /**
     * What {@code settings} says to do with an over-bound artifact, defaulting to {@link #OVERSIZED_DEFAULT}.
     *
     * <p>Read from the deployment's live settings rather than from a property, unlike the tiers above: those are
     * read per artifact on the publish thread and argue their way out of a store round-trip, while this is read
     * only for an artifact that already exceeded the bound - rare by construction, and worth an operator being able
     * to change without a restart.
     *
     * @throws IllegalArgumentException when the setting is present and names nothing this understands - an operator
     *         who chose a policy and mistyped it must not be left on the default believing otherwise
     */
    static Oversized oversized(UnaryOperator<String> settings) {
        String value = settings.apply(OVERSIZED_KEY);
        if (value == null || value.isBlank()) {
            return Oversized.valueOf(OVERSIZED_DEFAULT);
        }
        String token = value.strip();
        try {
            return Oversized.valueOf(token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("setting '" + OVERSIZED_KEY + "' must be one of "
                    + Stream.of(Oversized.values()).map(Enum::name).collect(Collectors.joining(", "))
                    + ", not '" + token + "'", unknown);
        }
    }

    /**
     * Whether this inspector reads the artifact as a STREAM when a screen offers one, rather than from the bounded
     * prefix it is otherwise handed.
     *
     * <p>{@code false} by default, and the default is not a hedge - it is what the bridge already does. An inspector
     * that has not overridden {@link #inspectArtifact(String, Content, Lookup)} reads the same front prefix whichever
     * leg calls it, so routing it through the streamed leg would buy nothing and cost something: the bridge answers
     * {@code inspectArtifact}, while the byte[] leg a publish takes is {@code inspect}, and for at least one shipped
     * inspector those differ (Maven's {@code inspect} also walks a POM's declared closure). Saying so here keeps a
     * deployment's behaviour for a large artifact exactly what it was, except where an inspector really does reach
     * further.
     *
     * <p>So this is the same shape as {@link build.jenesis.repository.format.ArtifactSignatures#embedsEvidence}: the
     * SPI asks, and the work happens only where the answer yields something. An inspector that overrides the spooled
     * leg returns {@code true} and owes that leg an honest {@link Inspection#complete()}.
     *
     * <p><b>Which inspectors need it, measured rather than assumed.</b> Most formats keep their declaration where
     * the container fixes it - a {@code .nuspec} at the zip root, a gem's metadata as the first tar member, a
     * wheel's {@code METADATA} - so a front prefix carries it whatever the artifact's size, and those inspectors
     * stay bridged because reading further would buy nothing. Three shapes do not, and all three say so: a jar's
     * embedded descriptor is wherever the packager wrote it, a {@code .deb}'s DEP-5 copyright is behind every file
     * the package installs, and signature material and secrets can be anywhere at all. Driving every ecosystem's
     * matrix row at 1.5 GiB on 2026-09-15 is what separated the two lists; a format that grows a declaration whose
     * position its container does not fix belongs on this side of it.
     */
    default boolean streams() {
        return false;
    }

    /**
     * The FULL-BODY tier: the most of a fully-spooled body an {@link #inspectArtifact(String, Content, Lookup)}
     * override may consume. Full-body means "past the prefix", not "unbounded" - a whole-artifact scanner still owes a
     * work bound, and this is it.
     *
     * <p><b>Why it is a multiple of the prefix tier rather than a number of its own.</b> The spooled leg exists for one
     * reason: to see <em>more</em> of the artifact than the bounded {@code byte[]} leg does. A full-body ceiling at or
     * below {@link #PREFIX_INSPECTION_LIMIT} would therefore buy bounded heap and no reach at all - the override would
     * be pure cost - and, worse, it would silently break the screens: both the publish screen and the proxy/hardened
     * screens decide "did the inspectors see the whole artifact?" by comparing the body's length against the prefix
     * tier, and fall back to a path-derived subject when it is exceeded. That test is only sound while <em>no</em>
     * inspector reads less far than the prefix tier: an inspector whose reach stopped earlier would return an empty
     * result over a body the screen believes was seen whole, and an empty result is read as "understood, declares
     * nothing" and ALLOWed. Deriving this constant from the prefix tier is what keeps the two in the only order that
     * makes the screens' completeness test true; {@code InspectorContract} holds every {@code FULL_BODY} fixture's
     * declared ceiling to the same order, so a future inspector cannot re-open the gap with a ceiling of its own.
     *
     * <p><b>Why the multiplier is 2.</b> A content scan is CPU-bound, not heap-bound: it streams, so the bound exists
     * to cap the <em>work</em> one artifact may demand of a publish or proxy thread, and the ceiling is whatever that
     * work budget affords. Measured on the maintained secret ruleset, a scan costs on the order of 100 ms per MiB
     * scanned, so this tier is about eight seconds of worst-case scan for an artifact deliberately shaped to reach it -
     * four times what the previous, ad-hoc 16 MiB scanner budget allowed, and the same order as the other bounds a
     * single hostile artifact may already spend. Doubling again would double that. The value also lands exactly on the
     * archive-walk tier ({@link build.jenesis.repository.store.ArchiveWalk#LARGEST_WALK}), the product's answer to "how much of one
     * artifact may a screen chew through", so the four tiers span three numbers rather than four: a full-body scan that
     * stopped earlier than a prefix-tier inspector's archive walk over the same body would be the same inversion one
     * seam over.
     */
    long FULL_BODY_INSPECTION_LIMIT = 2L * PREFIX_INSPECTION_LIMIT;

    /**
     * The compliance subjects extracted from an artifact's FULLY-SPOOLED body - the full-body inspection tier the
     * hardened proxy leg screens an untrusted upstream artifact through, so a secret-scan / integrity /
     * attestation inspector sees the <em>whole</em> artifact rather than only a bounded prefix (that is the point of
     * spooling the body to completion before serving it). {@code body} is a re-openable handle to the complete artifact
     * on the spool (never a heap {@code byte[]}), streamable from its first byte as many times as an inspector needs.
     *
     * <p><b>Default-bridged.</b> The default reads a bounded {@link #PREFIX_INSPECTION_LIMIT} front prefix from the
     * spool and hands it to {@link #inspectArtifact(String, byte[], Lookup)}, so an inspector whose declaration sits at
     * the front of the artifact (a POM, a {@code package.json}, a {@code .nuspec}) reads exactly what it read before and
     * needs no change - the prefix carries its whole declaration while a pathologically large body is never pulled whole
     * into heap. Only a full-body-tier inspector (the embedded-secret content scanner) overrides this to stream the
     * whole body, bounded by {@link #FULL_BODY_INSPECTION_LIMIT} and by its own entry/finding/nesting ceilings so the
     * streamed read stays bomb-bounded (full-body does NOT mean unbounded, and it does not mean a ceiling per inspector
     * either - the byte ceiling is the shared tier, so it cannot be re-chosen below the prefix tier one implementation
     * at a time). A {@link MalformedArtifactException} thrown here means the same as on the {@code byte[]} path - the
     * inspector claimed the artifact but could not parse it - and the hardened leg refuses on it (fail-closed).
     *
     * <p><b>It answers an {@link Inspection}, not a bare list</b>, because this is the leg on which an empty answer is
     * ambiguous: the screen behind it has the whole body and believes it was screened whole, so an inspector that
     * stopped at a bound owes it that fact rather than an empty list that reads as "understood, declares nothing"
     * (clause 3). The default below reports it soundly for a bridged inspector - it sees exactly the front prefix, so
     * its answer stands for the whole artifact exactly when the artifact fits the prefix tier - and an inspector that
     * overrides this owes its own honest answer, because only it knows whether its engine ran out of body or out of
     * budget.
     */
    default Inspection inspectArtifact(String path, Content body, Lookup lookup) throws IOException {
        // The bridge reads the front prefix and nothing else, so its answer covers the whole artifact exactly when the
        // artifact ends within that prefix. The boundary is the same one the screens' truncation test uses, so a
        // bridged inspector's report and the screen's own body-length test can never disagree.
        //
        // A bridged inspector also reads SIBLINGS under their own bounds, and this test cannot see those. An
        // inspector that folds a truncated sibling into "declares nothing" declares incompleteOnTruncatedSibling()
        // to have that counted; one with a designed degrade for that case (an attestation whose artifact is too
        // large to digest records the digest as unknown and says so) reached a conclusion rather than falling
        // short, and leaves it false.
        Watched watched = new Watched(lookup);
        List<ComplianceGate.Subject> subjects = inspectArtifact(path, BoundedBodyReader.readPrefix(body), watched);
        // The CONFIGURED tier, never the compiled constant: readPrefix above reads the configured one, so
        // comparing against the constant would answer about a different body than the one that was read -
        // which is the trap prefixInspectionLimit() exists to close. An operator who LOWERED the key got a
        // whole-body verdict over a truncated read out of it, which is the fail-open direction.
        return new Inspection(subjects, body.size() <= prefixInspectionLimit()
                && !(incompleteOnTruncatedSibling() && watched.truncated()));
    }

    /**
     * Whether a bounded SIBLING read that came back short makes this inspector's answer an incomplete one.
     *
     * <p>{@code false} by default, and the two cases it separates are both real. An inspector that folds a
     * truncated sibling into "declares nothing" and carries on is right about what to do and wrong about what to
     * report - the facts that declaration would have carried are unknown rather than absent, so a screen behind it
     * must not record a whole-body verdict. {@code MavenQualityInspector}'s optional SBOM attachment is that case
     * and answers {@code true}.
     *
     * <p>An inspector with a <em>designed</em> degrade for the same event is the other case and stays {@code false}:
     * {@code AttestationInspector} reads its artifact sibling under a bound and, when that comes back short,
     * records the digest as unknown and says so in its subjects. Its read did not fall short; it reached a
     * documented conclusion. Reporting that as incomplete would feed a deliberate degrade to the fail-open
     * machinery, which is a behaviour change nobody asked for.
     *
     * <p>It is a declared property rather than an override of the bridge because overriding the streaming leg is
     * how this SPI's kit recognises a FULL-BODY-tier inspector, and an inspector wanting honest completeness about
     * its siblings is not thereby claiming to read whole bodies.
     */
    default boolean incompleteOnTruncatedSibling() {
        return false;
    }


    /** A {@link Lookup} that remembers whether any bounded read it served came back short - see the bridge above. */
    final class Watched implements Lookup {

        private final Lookup delegate;
        private boolean truncated;

        Watched(Lookup delegate) {
            this.delegate = Objects.requireNonNull(delegate, "lookup");
        }

        @Override
        public Optional<byte[]> fetch(String path) throws IOException {
            return delegate.fetch(path);
        }

        @Override
        public Optional<Lookup.Bounded> fetchBounded(String path, int limit) throws IOException {
            Optional<Lookup.Bounded> bounded = delegate.fetchBounded(path, limit);
            if (bounded.isPresent() && bounded.get().truncated()) {
                truncated = true;
            }
            return bounded;
        }

        /** Whether any bounded sibling read stopped at its limit during this inspection. */
        boolean truncated() {
            return truncated;
        }
    }

    /**
     * What one inspector answered about a fully-spooled body: the {@link #subjects()} it derived, and whether the read
     * behind them {@link #complete() ran to completion} or a bound stopped it first.
     *
     * <p><b>Why the completeness rides with the subjects.</b> The two facts are one answer. An empty subject
     * list means "I understood this artifact and it declares nothing to assess" only when the inspector saw everything
     * it needed to; when a bound stopped the read, the very same empty list means "I could not look", and the screens
     * are what turn the first into an {@code ALLOW}. Splitting them - a list here, a completeness question asked
     * separately - would let a caller act on the list without ever asking, which is precisely how the screens came to
     * infer completeness from the merged list being empty and so lose one inspector's bound-stopped read behind
     * another's subject. A non-empty answer carries the flag too: finding <em>a</em> secret in the first 64 MiB of a
     * 100 MiB artifact says nothing about the remaining 36, and a screen that records the verdict as a whole-body one
     * would be recording something no inspector claimed.
     *
     * <p>{@code complete} is about the <em>read</em>, not about the findings: an inspector that read the whole body and
     * found nothing is complete, and one that stopped at its byte, entry, finding or nesting ceiling - or on a
     * container it could not decode - is not, whatever it had accumulated by then. It is never an error channel: a
     * body the inspector claimed but could not parse is still {@link MalformedArtifactException} (clause 7), because
     * that is a fact about the artifact rather than about how far the read got.
     */
    record Inspection(List<ComplianceGate.Subject> subjects, boolean complete) {

        public Inspection {
            if (subjects == null) {
                throw new NullPointerException("An inspection returned null subjects; the absence sentinel is an "
                        + "empty List (clause 3), because null is never a legal return.");
            }
            subjects = List.copyOf(subjects);
        }

        /** The subjects of a read that ran to completion - the inspector saw everything it needed to, so an empty list
         *  here is the clause-3 positive claim that the artifact declares nothing to assess. */
        public static Inspection complete(List<ComplianceGate.Subject> subjects) {
            return new Inspection(subjects, true);
        }

        /** The subjects of a read a bound stopped: whatever was found before the ceiling, plus the fact that the rest
         *  was never looked at. An empty list here is "I could not look", and no screen may read it as a clean one. */
        public static Inspection boundStopped(List<ComplianceGate.Subject> subjects) {
            return new Inspection(subjects, false);
        }
    }

    /**
     * A re-openable handle to a fully-spooled artifact body - the whole artifact staged on the hardening proxy's spool,
     * streamable from byte zero without ever being pulled whole into a heap {@code byte[]} (§1). The
     * full-body inspection tier reads through this rather than a bounded prefix.
     */
    interface Content {

        /** The artifact's full length in bytes, as spooled - the length {@link #open()} really streams, so an
         *  inspector may budget against it without reading the body to find out how big it is. */
        long size() throws IOException;

        /** A fresh stream over the whole body from its first byte; the caller closes it. Re-openable for the life of
         *  the handle, so an inspector that needs two passes (hash, then scan) opens it twice rather than buffering
         *  the body - and so a second inspection of the same handle reads the same bytes as the first. Whoever
         *  supplies the handle owes that: a one-shot {@code Content} would silently turn a re-screen into an
         *  inspection of an empty artifact. */
        InputStream open() throws IOException;
    }

    /**
     * Reads an already-published sibling of the artifact under inspection - the companion an inspector needs to finish
     * its own read: a jar's sibling POM, the CycloneDX attachment beside a coordinate, the artifact an attestation
     * referrer names. The screen that calls an inspector supplies it, already scoped to the publishing tenant and
     * repository, so an inspector reaches no storage key of its own.
     *
     * <p><b>Two reads, two bounds, and neither may be expressed in terms of the other.</b> A companion is wanted in
     * one of exactly two shapes, and collapsing them is how a bound stops being honestly reported:
     * <ul>
     *   <li>{@link #fetch(String)} is the <em>whole-document</em> read - give me this small published metadata document
     *       entire, because half a POM or half an envelope is worthless. It takes no caller bound because the caller
     *       has no use for a partial answer, so it carries the supplier's own ceiling and past it it <b>throws</b>. It
     *       never returns a prefix: handing back part of a document the caller believes is whole is the
     *       silently-incomplete answer &sect;5 and &sect;9 forbid, and reading with no ceiling at all turns
     *       an inspector into an out-of-memory lever (&sect;1). Both shipped screens key that ceiling to the free
     *       core's {@link build.jenesis.repository.store.PublishInterceptor.Content#LARGEST_SIBLING} rather than
     *       restating a number, so the publish and proxy legs cannot drift on what "too large to read whole" means.</li>
     *   <li>{@link #fetchBounded(String, int)} is the <em>bounded-fact</em> read - give me at most this many bytes and
     *       tell me whether there were more, because the caller only needs a bounded fact off the companion (a digest,
     *       a size, the head of a large attachment) and has a defined answer for "there was more". It honours the
     *       <em>caller's</em> limit, not the supplier's ceiling, and it <b>never fails on size</b>: an over-limit
     *       sibling comes back as a {@code limit}-length prefix flagged {@link Bounded#truncated()}, which is
     *       bound-fails-visibly as an explicit result rather than an exception.</li>
     * </ul>
     * <p><b>Why there is no default for the bounded read.</b> This interface used to give
     * {@code fetchBounded} a default that routed through {@link #fetch} and trimmed the result in heap. That default
     * was the defect, not a convenience: it inherited the whole-document ceiling, so a caller asking for 32 MiB got an
     * exception above 8 MiB on the publish leg while the proxy leg - which overrode it - streamed and answered
     * {@code truncated}. The same sibling therefore degraded on one leg and raised on the other, and it buffered the
     * whole companion before deciding to discard most of it. No arithmetic rescues that shape, so the method is
     * abstract: every supplier owes a read really capped at its source, and this interface is deliberately no longer a
     * {@code @FunctionalInterface}. A supplier with nothing to read at all answers {@link #none()}.
     *
     * <h2>Contract</h2>
     * <ol>
     * <li><b>Thread-safety.</b> A lookup is handed to every claiming inspector on the publish or proxy thread that
     * created it and may be called by several inspectors over one artifact; an implementation holds no per-call state
     * in fields and is safe to call concurrently, since the same instance can serve concurrent screenings.</li>
     *
     * <li><b>Idempotency / replay.</b> Both legs are pure reads of stored state: the same path answers the same bytes
     * for as long as the sibling is unchanged, however often it is asked, so a re-screened publish derives the same
     * subjects (the inspector clause 2 this seam serves).</li>
     *
     * <li><b>Absence sentinel.</b> {@link Optional#empty()} means "nothing is published at that path" on both legs -
     * never a zero-length body a caller would parse as an empty document, and {@code null} is never a legal return. An
     * absent sibling is an ordinary outcome (a sidecar published before the artifact it names), not a failure.</li>
     *
     * <li><b>Selection failure.</b> Not applicable: a lookup is supplied by the calling screen, never selected by
     * name.</li>
     *
     * <li><b>Streaming.</b> Neither leg may materialise more than its own bound. {@link #fetchBounded} in particular
     * caps at the <em>source</em>: reading the sibling whole and trimming afterwards allocates exactly the heap the
     * bound exists to deny, and is the shape whose removal this seam is named for.</li>
     *
     * <li><b>Tenant scoping.</b> The lookup is already scoped to the publishing tenant and repository by the screen
     * that built it; an inspector passes request paths through it and can reach no other tenant's artifacts.</li>
     *
     * <li><b>Error visibility.</b> The two legs answer a bound in opposite ways, deliberately: {@link #fetch} throws
     * past its ceiling, {@link #fetchBounded} reports the overflow through {@link Bounded#truncated()} and never
     * raises on size. What neither may do is return a short answer that reads as a complete one. An I/O failure
     * propagates on both legs; the inspector's own clause 7 decides whether the caller degrades or refuses.</li>
     *
     * <li><b>Read purity.</b> A lookup renders durably stored state only - no upstream fetch, no lazy refresh, no
     * write. It runs on the publish and proxy paths, so it is a keyed read, never a scan.</li>
     *
     * <li><b>Staleness.</b> Not applicable: a lookup memoises nothing and has no snapshot to date-stamp.</li>
     *
     * <li><b>Lifecycle / ownership.</b> The screen creates one per screening and owns it; it is not
     * {@link ServiceLoader}-discovered. An inspector must not retain it past the call it was handed in, since the
     * store it reads is scoped to that publication.</li>
     *
     * <li><b>Ordering / concurrency.</b> Every claiming inspector receives the same lookup over the same artifact;
     * discovery order must not change what any of them reads through it.</li>
     *
     * <li><b>Bounded work / cancellation.</b> Stated above per leg. The boundary is exact and is {@code >}, not
     * {@code >=}: a sibling of <em>exactly</em> {@code limit} bytes is reported whole, because the caller really does
     * hold every byte of it and a digest computed over it really is the companion's digest. Implementations read one
     * byte past {@code limit} to tell the two apart. There is no cancellation or timeout seam.</li>
     *
     * <li><b>Durability / delivery.</b> Not applicable: a lookup commits nothing.</li>
     * </ol>
     */
    interface Lookup {

        /** The lookup for an inspection with no readable siblings at all - both legs answer the clause-3 absence
         *  sentinel for every path. It is a positive claim ("nothing is published anywhere this inspection can see"),
         *  not a bound being dodged: there is nothing to read, so there is nothing to cap. An inspector that reads no
         *  companion is screened through this rather than through a lookup that would have to invent a sibling. */
        Lookup NONE = new Lookup() {

            @Override
            public Optional<byte[]> fetch(String path) {
                return Optional.empty();
            }

            @Override
            public Optional<Bounded> fetchBounded(String path, int limit) {
                return Optional.empty();
            }
        };

        /** {@link #NONE} as a method, matching the {@code none()} convention the rest of this SPI's absence sentinels
         *  use, so a caller spells the empty case the same way whichever seam it is on. */
        static Lookup none() {
            return NONE;
        }

        /**
         * The bytes of the already-published sibling at this request path, entire, or empty if nothing is published
         * there. The whole document or nothing: past the supplier's own ceiling - {@link
         * build.jenesis.repository.store.PublishInterceptor.Content#LARGEST_SIBLING} for both shipped screens - this
         * <b>throws</b> rather than returning a prefix the caller would read as complete. A caller that can use a
         * prefix asks for one through {@link #fetchBounded(String, int)} instead.
         */
        Optional<byte[]> fetch(String path) throws IOException;

        /**
         * Up to {@code limit} bytes of the already-published sibling at this request path, read straight off the
         * source so nothing beyond the bound is ever materialised - or empty if nothing is published there. This is
         * the seam an inspector uses when it needs only a bounded fact off a companion (a digest, a size) and must not
         * pull a multi-gigabyte sibling whole into a {@code byte[]} on the publish thread - an attestation referrer
         * beside a huge jar.
         *
         * <p><b>The bound is the caller's, and it is honoured exactly.</b> A sibling of at most {@code limit} bytes
         * comes back whole with {@link Bounded#truncated()} {@code false}; a <em>longer</em> one comes back as its
         * first {@code limit} bytes with {@code truncated} {@code true}. Size alone never raises here, whatever
         * ceiling {@link #fetch} carries - an over-limit companion is a fact the caller asked to be told, not a
         * failure of the publish. The boundary is {@code >}, not {@code >=}: read one byte past {@code limit} to tell
         * a sibling of exactly {@code limit} bytes (whole) from a longer one (truncated), so a caller is never
         * pessimistically denied a digest over bytes it fully holds.
         *
         * <p>There is deliberately no default: expressing this read in terms of {@link #fetch} would give the caller
         * neither bound it asked for - it would fail above the whole-document ceiling however large a limit was
         * requested, and it would buffer the whole companion before discarding most of it.
         *
         * @param path  the sibling's published request path
         * @param limit the most bytes to materialise; must be positive
         */
        Optional<Bounded> fetchBounded(String path, int limit) throws IOException;

        /**
         * As {@link #fetchBounded}, but asking what is <em>stored</em> at the path rather than what a client would be
         * served - the read for a companion that is material of this publish rather than evidence about the world.
         *
         * <h4>Why the two differ, and when this is the right one</h4>
         *
         * {@link #fetchBounded} answers the serving question, and pays for it: the interceptor chain's withhold
         * probe, the content-addressed withhold marker, and the rule that a sidecar is withheld by its subject's
         * hold. That is correct for a companion whose <em>content</em> is evidence - a POM's licence must not be read
         * off a document the registry is withholding, or a verdict is reached on evidence no client can see.
         *
         * <p>It is the wrong question for a companion whose <em>existence and bytes</em> are part of the artifact's
         * own publish. A publisher's signature is the case: "does a signature exist for this artifact" must be
         * answerable while the artifact is held - it is how a held artifact is released when its signature lands -
         * and asking the serving question there is self-referential, because the hold being decided is what hides
         * the sidecar. The same confusion cost a deadlock on the release path before {@code heldContentOf} fixed it
         * there; this is the same question one path earlier.
         *
         * <p>It is also two store reads cheaper per call, which on a probe every publish pays for a sidecar that is
         * usually absent is the difference between a fixed cost and a noticeable one.
         *
         * <p>The default is {@link #fetchBounded}, so a lookup that cannot distinguish the two is simply answering
         * the stricter question - never the looser one.
         */
        default Optional<Bounded> fetchStored(String path, int limit) throws IOException {
            return fetchBounded(path, limit);
        }

        /**
         * Up to {@code limit} bytes of a document a format itself recorded under its own key space - what a proxy
         * leg learned from an index it relayed rather than kept: the digest a mirror's {@code Packages} declared for
         * a package, the suite's signed {@code InRelease}. Neither is published at any request path, so neither read
         * above can reach it; this one takes the key the format wrote it under and stays bounded by the caller's
         * limit. Empty by default: a lookup with no store behind it has nothing recorded.
         */
        default Optional<Bounded> fetchRecorded(String key, int limit) throws IOException {
            return Optional.empty();
        }

        /** A bounded read of a sibling: its {@code content} - the whole sibling, or the leading {@code limit} bytes of
         *  a <em>longer</em> one - and whether it was cut short. {@code truncated} is the caller's signal that a fact
         *  derived from the whole (a digest, a size, a parse) cannot be confirmed from what it holds, so it must
         *  degrade explicitly rather than assert something about a prefix. A sibling of exactly {@code limit} bytes is
         *  not truncated: every byte of it is here. */
        record Bounded(byte[] content, boolean truncated) {
        }
    }
}
