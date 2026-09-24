package build.jenesis.repository.compliance;

import module java.base;

import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;

/**
 * The ingestion gate. A subject is checked against the core dimensions over a single
 * {@link AdvisorySource} lookup - the {@link VulnerabilityPolicy} (a CVSS threshold), the
 * {@link MaliciousPackagePolicy} (the feed's malicious flag, which carries no usable score) and the operator
 * {@link DenyListPolicy} - plus every discovered {@link GatePolicy} dimension the deployment's modules contribute
 * (the license policy, the known-exploited check), sharing that same lookup. The {@link Assessment} carries the
 * strongest {@link Verdict} across every finding, so the repository can allow, quarantine, or reject, with the
 * findings explaining why. The same dimensions run on both the publish path and the proxy fetch path; a discovered
 * policy may soften itself for the proxy (see {@link GatePolicyProvider.Path}). The gate holds no state and does no
 * I/O of its own beyond reading the supplied jar; the advisory lookup is the source's concern. The malicious
 * dimension defaults on (quarantine); the deny-list and the discovered dimensions default empty.
 */
public final class ComplianceGate {

    private final VulnerabilityPolicy vulnerabilityPolicy;
    private final MaliciousPackagePolicy maliciousPolicy;
    private final DenyListPolicy denyListPolicy;
    private final List<GatePolicy> policies;
    private final AdvisorySource advisories;
    private final Vex vex;
    private final Waivers waivers;

    public ComplianceGate(VulnerabilityPolicy vulnerabilityPolicy, AdvisorySource advisories) {
        this(vulnerabilityPolicy, new MaliciousPackagePolicy(), new DenyListPolicy(List.of()), List.of(),
                advisories, Vex.NONE, Waivers.NONE);
    }

    private ComplianceGate(VulnerabilityPolicy vulnerabilityPolicy,
                           MaliciousPackagePolicy maliciousPolicy,
                           DenyListPolicy denyListPolicy,
                           List<GatePolicy> policies,
                           AdvisorySource advisories,
                           Vex vex,
                           Waivers waivers) {
        this.vulnerabilityPolicy = vulnerabilityPolicy;
        this.maliciousPolicy = maliciousPolicy;
        this.denyListPolicy = denyListPolicy;
        this.policies = policies;
        this.advisories = advisories;
        this.vex = vex;
        this.waivers = waivers;
    }

    public ComplianceGate malicious(MaliciousPackagePolicy maliciousPolicy) {
        return new ComplianceGate(vulnerabilityPolicy, maliciousPolicy, denyListPolicy, policies, advisories, vex,
                waivers);
    }

    public ComplianceGate denyList(DenyListPolicy denyListPolicy) {
        return new ComplianceGate(vulnerabilityPolicy, maliciousPolicy, denyListPolicy, policies, advisories, vex,
                waivers);
    }

    /** This gate asking {@code advisories} instead of the feeds it was built over, every dimension and overlay
     *  unchanged: how a finding reported from outside - a scanner run in CI, attributed to that scanner - is decided
     *  by exactly the threshold, action, VEX and waivers a feed's advisory would be. */
    public ComplianceGate advisories(AdvisorySource advisories) {
        return new ComplianceGate(vulnerabilityPolicy, maliciousPolicy, denyListPolicy, policies, advisories, vex,
                waivers);
    }

    /** The discovered gate dimensions (see {@link GatePolicyProvider}), run alongside the core ones. */
    public ComplianceGate policies(List<GatePolicy> policies) {
        return new ComplianceGate(vulnerabilityPolicy, maliciousPolicy, denyListPolicy, List.copyOf(policies),
                advisories, vex, waivers);
    }

    /** This gate reading maintainer-health from {@code health} rather than each health-aware dimension's own source:
     *  the deployment overlays the durable health ledger here at screen time (the screen has the request's scoped store,
     *  the boot-built policy does not), so the health dimension scores off the persisted answer instead of a live
     *  deps.dev probe (Principle 10: a read renders what is durably there; a never-scored coordinate resolves to the
     *  same safe default - no finding - the live probe produces for one it cannot resolve). Every discovered
     *  {@link HealthAware} dimension is rebound to {@code health}; a non-health dimension is untouched. Order-independent
     *  and idempotent, so it composes with {@link #vex}/{@link #waivers} in any order; {@link HealthSource#none()} (or a
     *  gate with no health dimension) is a no-op. */
    public ComplianceGate health(HealthSource health) {
        List<GatePolicy> rebound = new ArrayList<>(policies.size());
        for (GatePolicy policy : policies) {
            rebound.add(policy instanceof HealthAware aware ? aware.withHealth(health) : policy);
        }
        return new ComplianceGate(vulnerabilityPolicy, maliciousPolicy, denyListPolicy, List.copyOf(rebound),
                advisories, vex, waivers);
    }

    /** This gate reading the tenant's ingested VEX statements: an advisory a statement marks non-applicable to the
     *  subject ({@code not_affected} / {@code fixed}) is not handed to any dimension and is recorded as an
     *  informational allow instead, so an operator's attested-not-applicable vulnerability is downgraded rather than
     *  quarantining the upload. {@link Vex#NONE} (the default) suppresses nothing. */
    public ComplianceGate vex(Vex vex) {
        return new ComplianceGate(vulnerabilityPolicy, maliciousPolicy, denyListPolicy, policies, advisories, vex,
                waivers);
    }

    /** This gate reading the tenant's active accept-risk waivers: an advisory an operator has recorded a still-standing
     *  waiver for on the subject is not handed to any dimension and is recorded as an informational allow naming the
     *  waiver and its expiry, so an explicitly and temporarily accepted risk is downgraded rather than holding or
     *  rejecting the upload. Consulted after {@link #vex} (an attested-not-applicable claim clears a flaw outright; a
     *  waiver only defers a flaw that does apply). {@link Waivers#NONE} (the default) accepts nothing. */
    public ComplianceGate waivers(Waivers waivers) {
        return new ComplianceGate(vulnerabilityPolicy, maliciousPolicy, denyListPolicy, policies, advisories, vex,
                waivers);
    }

    /** A license an artifact declares: a name and/or URL from its POM, or its jar Bundle-License header. */
    public record DeclaredLicense(String name, String url) {
    }

    /**
     * A credential a content inspector found embedded in an artifact's bytes: which ruleset {@code rule} matched, a
     * human {@code description}, a {@code redaction} (a masked sample that never reveals the secret itself) and the
     * {@code location} inside the artifact it sat at (an archive entry, or the raw body). Carried on a {@link Subject}
     * so a discovered secret-scan {@link GatePolicy} can raise a finding, exactly as a {@link DeclaredLicense} feeds
     * the license policy - the inspector reads the bytes, the policy decides the verdict. A subject that carries these
     * but declares no package license is a content-scan subject, not a licensable coordinate.
     */
    public record DetectedSecret(String rule, String description, String redaction, String location) {
    }

    /**
     * An inbound provenance attestation an inspector found co-located with an artifact - a DSSE-wrapped in-toto /
     * SLSA statement uploaded beside the artifact as its {@code .intoto.jsonl} / {@code .att} referrer. It carries the
     * raw {@code envelope} (verified against the tenant's configured trust anchor by the admission {@link GatePolicy},
     * not here - the inspector reads the bytes, the policy holds the key), the {@code artifactDigest} the inspector
     * hashed off the artifact for the statement-subject binding check ({@code null} when the artifact was larger than
     * the inspection window or was not present, so binding cannot be confirmed), {@code artifactPresent} - whether the
     * artifact's bytes were available at this inspection at all, and the {@code location} the referrer sat at.
     * {@code artifactPresent} tells the two null-digest cases apart, which the admission policy must gate differently:
     * a null digest with {@code artifactPresent == false} is a sidecar admitted <em>before</em> its artifact lands
     * (binding is deferred, and re-checked when the artifact publishes with its real digest), whereas a null digest
     * with {@code artifactPresent == true} is an artifact that <em>is</em> present but too large to hash whole - its
     * binding genuinely cannot be confirmed, so it must be held rather than admitted as verified provenance (a foreign
     * attestation beside a large jar would otherwise be a replay token). Carried on a {@link Subject} exactly as a
     * {@link DetectedSecret} is, so a discovered attestation-admission dimension can verify it and gate admission; a
     * subject that carries one but declares no package license is a content-scan subject
     * ({@link Subject#contentScan()}), not a licensable coordinate.
     */
    public record Attestation(String envelope, String artifactDigest, boolean artifactPresent, String location) {
    }

    /**
     * One inbound signature an inspector found for an artifact, and what verifying it produced. The format said where
     * the material was and what it covered ({@code ArtifactSignatures}); the inspector verified it against the trust
     * material the screen overlaid; this records the result for a discovered signature dimension to gate on, exactly
     * as {@link DetectedSecret} records a detection for the secret dimension and {@link Attestation} an envelope for
     * the admission one.
     *
     * <p>{@code signer} is present for every outcome that got far enough to read the issuer out of the signature
     * packet - which is every outcome but {@link Outcome#ABSENT} and most {@link Outcome#UNREADABLE} ones - because an
     * operator asked to trust a key needs to be told <em>which</em> key, and because the signer index is built from
     * what was ingested rather than from what was trusted.
     */
    public record Signature(String coveredPath, String scheme, Outcome outcome, SignerIdentity signer,
                            String keyAlgorithm, int keyBits, String hashAlgorithm,
                            Instant created, Instant signerExpiry, SignatureQuality quality, String location,
                            SignerTrust.Expectation expected, String keySource, Map<String, String> details) {

        /** The detail a keyless signer's OIDC issuer is recorded under. */
        public static final String ISSUER = "issuer";

        /** The detail a keyless signer's certified subject - a workflow, an account - is recorded under. */
        public static final String SUBJECT = "subject";

        /** The detail the transparency log's index for the entry is recorded under. */
        public static final String LOG_INDEX = "log-index";

        /** The detail the instant the transparency log recorded the signing is recorded under, ISO-8601. */
        public static final String INTEGRATED_TIME = "integrated-time";

        /**
         * The fourteen-argument form beside the thirteen: {@code details} is what else the scheme's material states
         * that an operator reads apart from the identity - a keyless signer's issuer and subject, its log entry -
         * keyed by the constants above, recorded on the version's summary. Empty for a scheme whose identity says
         * everything.
         */
        public Signature(String coveredPath, String scheme, Outcome outcome, SignerIdentity signer,
                         String keyAlgorithm, int keyBits, String hashAlgorithm,
                         Instant created, Instant signerExpiry, SignatureQuality quality, String location,
                         SignerTrust.Expectation expected, String keySource) {
            this(coveredPath, scheme, outcome, signer, keyAlgorithm, keyBits, hashAlgorithm, created, signerExpiry,
                    quality, location, expected, keySource, null);
        }

        /**
         * A signature with no expectation to measure it against and no source named for its key: the coordinate
         * has no signing history and no operator pinned a signer to it, or the signature is not one a history
         * would speak to, and no trust source held its key. The thirteen-argument form carries {@code expected},
         * the identity the coordinate's earlier versions or an operator's pin said would sign it, set only when
         * this signature verified by <em>another</em> trusted signer - the continuity finding the
         * {@code signature-signer-changed} dial decides - and {@code keySource}, {@link SignerTrust#source() where
         * the key that identified the signer came from}, so a finding can say that a key was discovered rather
         * than configured.
         */
        public Signature(String coveredPath, String scheme, Outcome outcome, SignerIdentity signer,
                         String keyAlgorithm, int keyBits, String hashAlgorithm,
                         Instant created, Instant signerExpiry, SignatureQuality quality, String location) {
            this(coveredPath, scheme, outcome, signer, keyAlgorithm, keyBits, hashAlgorithm, created, signerExpiry,
                    quality, location, null, null);
        }

        /** This signature measured against what the coordinate's history expected: the same, when the expectation
         *  names this very signer or this signature did not verify; otherwise the expectation is carried. */
        public Signature expecting(SignerTrust.Expectation expectation) {
            if (expectation == null || outcome != Outcome.VALID || signer == null
                    || expectation.signer().equals(signer)) {
                return this;
            }
            return new Signature(coveredPath, scheme, outcome, signer, keyAlgorithm, keyBits, hashAlgorithm, created,
                    signerExpiry, quality, location, expectation, keySource, details);
        }

        /** This signature naming the trust source whose key identified its signer; {@code null} when none did. */
        public Signature sourced(String keySource) {
            return Objects.equals(keySource, this.keySource) ? this
                    : new Signature(coveredPath, scheme, outcome, signer, keyAlgorithm, keyBits, hashAlgorithm,
                            created, signerExpiry, quality, location, expected, keySource, details);
        }

        /**
         * What verifying one signature produced.
         *
         * <p>{@link #INVALID} and {@link #UNTRUSTED} are deliberately separate: the first is a signature that does not
         * verify over the bytes - tampering, corruption, or a signature for different content - and the second is a
         * perfectly good signature by a signer this deployment has no reason to believe. They warrant different
         * verdicts, and collapsing them would either wave tampering through or refuse every artifact whose maintainer
         * is simply unknown to us.
         *
         * <p>{@link #UNREADABLE} exists for the same reason {@code ProxyRelay.Declared} carries an unreadable state:
         * "we could not read the signature" must never silently become "there is none", or a truncated or malformed
         * signature reads as a merely unsigned artifact.
         */
        public enum Outcome {

            /** Verifies over the bytes, by a signer the deployment trusts. */
            VALID,

            /** Does not verify over the bytes - tampered, corrupt, or made for different content. */
            INVALID,

            /** Verifies over the bytes, but by a signer the deployment has no reason to trust. */
            UNTRUSTED,

            /** The artifact carries no signature material at all. */
            ABSENT,

            /** Material is present but could not be read or parsed, so nothing can be concluded from it. */
            UNREADABLE
        }

        public Signature {
            Objects.requireNonNull(scheme, "scheme");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(coveredPath, "coveredPath");
            quality = quality == null ? SignatureQuality.unassessed() : quality;
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        /** An artifact that carries nothing, for a path whose format expected a signature. */
        public static Signature absent(String coveredPath, String scheme) {
            return new Signature(coveredPath, scheme, Outcome.ABSENT, null, null, 0, null, null, null, null, "", null,
                    null);
        }

        /** Material that is present but could not be read, naming where it sat and why it could not be used. */
        public static Signature unreadable(String coveredPath, String scheme, String location, String reason) {
            return new Signature(coveredPath, scheme, Outcome.UNREADABLE, null, null, 0, null, null, null,
                    SignatureQuality.unassessed(), location == null ? "" : location + ": " + reason, null, null);
        }

        /** Whether this signature is one a deployment may rely on - it verified, and by a trusted signer. */
        public boolean trusted() {
            return outcome == Outcome.VALID;
        }

        /**
         * The outcome a whole version's summary carries when its files disagree - a Maven release is a jar, a POM and
         * often sources, each signed separately, and they need not agree.
         *
         * <p>The order is stated here once rather than re-derived per caller, because it is a judgement and callers
         * that each made their own would drift: {@link Outcome#INVALID} outranks everything, because bytes that do not
         * match the signature made for them is corruption or tampering and must never be summarised away by a sibling
         * file that happened to verify. Then {@link Outcome#ABSENT} - a file nobody vouched for is a real gap, and a
         * signed jar beside an unsigned POM is the interesting case, since the POM is what carries the dependency
         * graph. Then {@link Outcome#UNREADABLE}, then {@link Outcome#UNTRUSTED}, and {@link Outcome#VALID} only when
         * every file reached it.
         */
        public static Outcome worst(Collection<Signature> signatures) {
            List<Outcome> precedence = List.of(Outcome.INVALID, Outcome.ABSENT, Outcome.UNREADABLE,
                    Outcome.UNTRUSTED, Outcome.VALID);
            Outcome worst = null;
            for (Signature signature : signatures) {
                if (worst == null || precedence.indexOf(signature.outcome()) < precedence.indexOf(worst)) {
                    worst = signature.outcome();
                }
            }
            return worst;
        }

        /** The signature a version's summary is written from: the one carrying {@link #worst} outcome, so the record
         *  names the signer of the thing that went wrong rather than of whichever file was inspected first. */
        public static Optional<Signature> summarising(Collection<Signature> signatures) {
            Outcome worst = worst(signatures);
            return signatures.stream().filter(signature -> signature.outcome() == worst).findFirst();
        }
    }

    /**
     * Where a subject sits on the build's resolved dependency graph - the "reachable on the build graph" signal
     * that lets a finding about a component actually pulled into the build be told apart from a coordinate that is
     * only scored in the abstract. A subject reached through the artifact's transitive dependency closure carries
     * the shortest dependency {@code path} from the artifact to it (so a CVE can be marked with how - and how
     * directly - the vulnerable component enters the build); the artifact itself is a {@link Kind#ROOT}; a subject
     * whose position on the graph is not known (a non-Maven ecosystem that does not resolve a graph, or an
     * unresolvable tree) is {@link #UNKNOWN}. Reachability is a marking, not a verdict: it enriches a finding and
     * lets a reachable one be ranked above a merely-scored one, without itself allowing or blocking anything.
     */
    public record Reachability(Kind kind, int depth, List<String> path) {

        /** Whether - and how directly - a subject is reachable on the resolved build graph. */
        public enum Kind {

            /** Not confirmed to sit on the build graph (a non-graph ecosystem, or an unresolvable tree). */
            UNKNOWN,

            /** The artifact under assessment itself - the root of the build graph. */
            ROOT,

            /** A direct dependency of the artifact (one hop from the root). */
            DIRECT,

            /** A dependency reached only through one or more intermediate dependencies. */
            TRANSITIVE
        }

        /** A subject whose position on the build graph is not known - the default for every non-graph inspector. */
        public static final Reachability UNKNOWN = new Reachability(Kind.UNKNOWN, -1, List.of());

        public Reachability {
            path = path == null ? List.of() : List.copyOf(path);
        }

        /** The artifact under assessment: the root of its own build graph. */
        public static Reachability root(String coordinate) {
            return new Reachability(Kind.ROOT, 0, List.of(coordinate));
        }

        /**
         * A dependency reachable on the build graph {@code depth} hops from the artifact, along {@code path} (the
         * shortest chain of coordinates from a direct dependency down to it). A single hop is a direct dependency.
         */
        public static Reachability onBuildGraph(int depth, List<String> path) {
            return new Reachability(depth <= 1 ? Kind.DIRECT : Kind.TRANSITIVE, depth, path);
        }

        /** Whether this subject was confirmed to sit on the resolved build graph. */
        public boolean onBuildGraph() {
            return kind != Kind.UNKNOWN;
        }

        /**
         * The sort weight of this reachability - higher is more reachable, so a finding about a component actually
         * pulled into the build sorts above one only scored in the abstract ("reachable sorts above merely-scored").
         * {@link Kind#UNKNOWN} sorts last (0); the artifact root and a direct dependency outrank a deeper transitive
         * one. This is the single structured ordering key the gate and the vulnerability report share.
         */
        public int rank() {
            return switch (kind) {
                case UNKNOWN -> 0;
                case TRANSITIVE -> 1;
                case DIRECT -> 2;
                case ROOT -> 3;
            };
        }

        /** A human-readable suffix marking a finding with its build-graph reachability, or empty when unknown. */
        public String marker() {
            return switch (kind) {
                case UNKNOWN -> "";
                case ROOT -> " [reachable on the build graph: the published artifact]";
                case DIRECT, TRANSITIVE -> " [reachable on the build graph via " + String.join(" -> ", path)
                        + " (" + kind.name().toLowerCase(Locale.ROOT) + ", depth " + depth + ")]";
            };
        }
    }

    /**
     * The artifact under assessment: its ecosystem, its ecosystem-neutral coordinate, its version, the licenses
     * it declares and where it sits on the build graph. The {@code coordinate} is the ecosystem's canonical package
     * name - Maven {@code group:artifact}, an npm or PyPI package name, a Go module path - so it maps straight onto
     * an advisory feed's package lookup and an operator deny-list entry, and {@code ecosystem} selects the feed's
     * namespace (OSV's {@code Maven}/{@code npm}/{@code PyPI}/...). {@code reachability} is {@link Reachability#UNKNOWN}
     * unless an inspector that resolves a dependency graph (the Maven inspector) places the subject on it.
     * {@code maintainers} is whom the artifact's own metadata names as responsible for it ({@link Maintainer}),
     * read by the ecosystem inspector out of the document it parses for the licence, and empty where it names
     * nobody or the inspector reads no such document.
     */
    public record Subject(String ecosystem, String coordinate, String version, List<DeclaredLicense> licenses,
                          Reachability reachability, List<DetectedSecret> secrets, Attestation attestation,
                          List<Signature> signatures, List<Maintainer> maintainers) {

        public Subject {
            secrets = secrets == null ? List.of() : List.copyOf(secrets);
            signatures = signatures == null ? List.of() : List.copyOf(signatures);
            maintainers = maintainers == null ? List.of() : List.copyOf(maintainers);
        }

        /** The shape before maintainers were a subject fact, kept so the callers that build a subject without one
         *  need not restate an empty list. */
        public Subject(String ecosystem, String coordinate, String version, List<DeclaredLicense> licenses,
                       Reachability reachability, List<DetectedSecret> secrets, Attestation attestation,
                       List<Signature> signatures) {
            this(ecosystem, coordinate, version, licenses, reachability, secrets, attestation, signatures, List.of());
        }

        public Subject(String ecosystem, String coordinate, String version, List<DeclaredLicense> licenses) {
            this(ecosystem, coordinate, version, licenses, Reachability.UNKNOWN, List.of(), null, List.of());
        }

        public Subject(String ecosystem, String coordinate, String version, List<DeclaredLicense> licenses,
                       Reachability reachability) {
            this(ecosystem, coordinate, version, licenses, reachability, List.of(), null, List.of());
        }

        /** The shape before inbound signatures were a subject fact, kept so the eighty-odd callers that build a
         *  subject without one need not restate an empty list. */
        public Subject(String ecosystem, String coordinate, String version, List<DeclaredLicense> licenses,
                       Reachability reachability, List<DetectedSecret> secrets, Attestation attestation) {
            this(ecosystem, coordinate, version, licenses, reachability, secrets, attestation, List.of());
        }

        /** This subject re-stamped with the content secrets an inspector found in its bytes - the seam a content
         *  inspector uses to hand its detections to the discovered secret-scan gate dimension. */
        public Subject withSecrets(List<DetectedSecret> secrets) {
            return new Subject(ecosystem, coordinate, version, licenses, reachability, secrets, attestation,
                    signatures, maintainers);
        }

        /** This subject re-stamped with the inbound attestation an inspector read from the artifact's co-located
         *  referrer - the seam the attestation inspector uses to hand the discovered admission gate dimension a
         *  verified attestation, exactly as {@link #withSecrets} hands the secret-scan dimension its detections. */
        public Subject withAttestation(Attestation attestation) {
            return new Subject(ecosystem, coordinate, version, licenses, reachability, secrets, attestation,
                    signatures, maintainers);
        }

        /** This subject re-stamped with the inbound signatures the signature inspector verified for it - the seam that
         *  hands the discovered signature dimension its facts, exactly as {@link #withSecrets} and
         *  {@link #withAttestation} hand theirs to the secret-scan and admission dimensions. */
        public Subject withSignatures(List<Signature> signatures) {
            return new Subject(ecosystem, coordinate, version, licenses, reachability, secrets, attestation,
                    signatures, maintainers);
        }

        /** This subject re-stamped with whom its metadata names as maintainers - the seam an ecosystem inspector
         *  uses to hand the trust what a key-discovery source that looks keys up by their owner needs. */
        public Subject withMaintainers(List<Maintainer> maintainers) {
            return new Subject(ecosystem, coordinate, version, licenses, reachability, secrets, attestation,
                    signatures, maintainers);
        }

        /** Whether this is a <em>content-scan</em> subject - one an inspector derived from an artifact's bytes (an
         *  embedded-secret detection, an inbound attestation, a publisher's signature) rather than from a package coordinate, so it declares no
         *  licensable identity and carries only content findings. The screens sort it after the package subject
         *  ({@code InspectionMerge}) and the license dimension skips it, so a content finding is not doubled with a
         *  bogus "No license declared". A real package that merely declares no license carries neither, so it still
         *  reaches the unknown-license branch. */
        public boolean contentScan() {
            return licenses.isEmpty() && (!secrets.isEmpty() || attestation != null || !signatures.isEmpty());
        }
    }

    /**
     * One reason the gate did not simply allow: the verdict it warrants, a human-readable explanation, where the
     * component it concerns sits on the build graph (so a reachable finding can be ranked above a merely-scored one),
     * and - for a dimension whose retroactive sweep also holds - the {@link Hold hold kind} the finding warrants and
     * the subjects it names, so a publish-time hold leaves the same {@code holds/<kind>} record the sweep would.
     */
    public record Finding(Verdict verdict, String detail, Reachability reachability, Hold hold) {

        public Finding(Verdict verdict, String detail) {
            this(verdict, detail, Reachability.UNKNOWN, null);
        }

        public Finding(Verdict verdict, String detail, Reachability reachability) {
            this(verdict, detail, reachability, null);
        }

        /** A finding that also names the retroactive hold kind it stands for, and what it holds on. */
        public Finding(Verdict verdict, String detail, Hold hold) {
            this(verdict, detail, Reachability.UNKNOWN, hold);
        }

        /** This finding re-stamped with a subject's build-graph reachability, marking its detail when it is on it. */
        Finding markedWith(Reachability reachability) {
            return new Finding(verdict, reachability.onBuildGraph() ? detail + reachability.marker() : detail,
                    reachability, hold);
        }
    }

    /**
     * The hold a finding stands for, in the vocabulary the retroactive sweeps keep their records in: the
     * {@code kind} is the {@code HoldReleaseObserver.kind()} token ({@code kev}, {@code license}, ...) and the
     * {@code subjects} are the space-free tokens that kind records a hold under - the CVEs a known-exploited catalogue
     * names, the SPDX ids a licence policy denies. A gate that quarantines writes one {@code holds/<kind>} record per
     * kind from these, so an operator's later release promotes them into the sticky override exactly as it would a
     * sweep's hold, and the sweep never re-holds a version a human has cleared. A finding with no hold (a CVSS
     * threshold, a deny-list rule, a VEX note) leaves the field {@code null}; those verdicts are reviewed through the
     * quarantine alone.
     */
    public record Hold(String kind, Set<String> subjects) {

        public Hold {
            Objects.requireNonNull(kind, "kind");
            subjects = Set.copyOf(subjects);
        }

        public static Hold of(String kind, String subject) {
            return new Hold(kind, Set.of(subject));
        }

        public static Hold of(String kind, Collection<String> subjects) {
            return new Hold(kind, new LinkedHashSet<>(subjects));
        }
    }

    /** The gate's decision: the strongest verdict across all findings ({@link Verdict#ALLOW} when there are none). */
    public record Assessment(Verdict verdict, List<Finding> findings) {

        public boolean allowed() {
            return verdict == Verdict.ALLOW;
        }
    }

    public Assessment assess(Subject subject) {
        return assess(subject, true);
    }

    /**
     * Assess <em>unclaimed</em> content - an upload or proxied fetch that no {@link QualityInspector} could turn into a
     * package subject (a {@code raw}-format artifact, an un-inspected format), screened from a path-derived coordinate
     * alone. It runs ONLY the core coordinate/feed dimensions - the operator {@link DenyListPolicy} (the essential one,
     * so an operator's {@code deny com.evil:*} still bites a raw/un-inspected coordinate instead of that being the exact
     * path an attacker uses to bypass it), plus the vulnerability and malicious feed dimensions for consistency (they
     * simply match nothing on a non-package coordinate whose feed lookup is empty) - and DELIBERATELY SKIPS the
     * discovered {@link #policies} (the license, attestation, known-exploited and secret-scan dimensions). This skip is
     * load-bearing: a path-derived subject declares no license and carries no content, so per {@link Subject#contentScan()}
     * it reaches the license policy's unknown-license branch and would quarantine EVERY raw upload - over-quarantining
     * ordinary unclaimed content - and none of the other discovered dimensions can meaningfully fire on a bare coordinate
     * with no read content anyway. The advisory lookup and its VEX/waiver downgrade run exactly as in {@link #assess(Subject)}.
     * Used by the publish and proxy screens to screen unclaimed content against the deny-list rather than admit it unscreened.
     */
    public Assessment assessUnclaimed(Subject subject) {
        return assess(subject, false);
    }

    private Assessment assess(Subject subject, boolean discoveredPolicies) {
        List<Finding> findings = new ArrayList<>();
        List<AdvisorySource.Advisory> found =
                advisories.advisories(subject.ecosystem(), subject.coordinate(), subject.version());
        // A VEX statement that marks an advisory not-applicable to this subject downgrades it to a recorded allow and
        // keeps it out of every dimension (vulnerability, malicious, known-exploited), so one attested claim clears the
        // flaw uniformly rather than each dimension re-flagging it. The remaining advisories screen as usual.
        List<AdvisorySource.Advisory> applicable = new ArrayList<>(found.size());
        for (AdvisorySource.Advisory advisory : found) {
            Optional<VexStatement> suppressed = vex.notApplicable(subject.ecosystem(), subject.coordinate(),
                    subject.version(), advisory.id(), advisory.cves());
            if (suppressed.isPresent()) {
                findings.add(new Finding(Verdict.ALLOW, vexDetail(advisory, suppressed.get())));
                continue;
            }
            Optional<Waiver> waived = waivers.waived(subject.ecosystem(), subject.coordinate(), subject.version(),
                    advisory.id(), advisory.cves());
            if (waived.isPresent()) {
                findings.add(new Finding(Verdict.ALLOW, waiverDetail(advisory, waived.get())));
            } else {
                applicable.add(advisory);
            }
        }
        findings.addAll(vulnerabilityPolicy.assess(applicable));
        findings.addAll(maliciousPolicy.assess(applicable));
        findings.addAll(denyListPolicy.assess(subject));
        // The discovered dimensions (license, attestation, known-exploited, secret-scan) run only for a claimed
        // subject; unclaimed content (assessUnclaimed) screens against the core coordinate/feed dimensions above and
        // deliberately skips these, so a raw upload with no declared license is not over-quarantined as unknown-license.
        if (discoveredPolicies) {
            for (GatePolicy policy : policies) {
                findings.addAll(policy.assess(subject, applicable));
            }
        }
        List<Finding> marked = new ArrayList<>(findings.size());
        for (Finding finding : findings) {
            marked.add(finding.markedWith(subject.reachability()));
        }
        return new Assessment(strongest(marked), List.copyOf(marked));
    }

    /** Assess an artifact together with its transitive dependencies; the verdict is the strongest across them all.
     *  The findings are ordered reachable-first (a finding about a component actually pulled into the build graph
     *  before one only scored in the abstract - {@link Reachability#rank()}), a stable sort that preserves each
     *  subject's own finding order within an equal reachability, so a reviewer reading the quarantine reasons and
     *  the {@code /api/vulnerabilities} ranking both meet what the build actually reaches first. */
    public Assessment assess(Collection<Subject> subjects) {
        List<Finding> findings = new ArrayList<>();
        for (Subject subject : subjects) {
            findings.addAll(assess(subject).findings());
        }
        findings.sort(Comparator.comparingInt((Finding finding) -> finding.reachability().rank()).reversed());
        return new Assessment(strongest(findings), List.copyOf(findings));
    }

    /** The recorded reason for a VEX-downgraded advisory: the advisory (with its severity), the applicability the
     *  statement asserted and its machine justification where it carries one, and the source document that made the
     *  claim - so a reviewer reading the assessment sees which vulnerability was cleared, why, and by which statement. */
    private static String vexDetail(AdvisorySource.Advisory advisory, VexStatement statement) {
        StringBuilder detail = new StringBuilder(advisory.id());
        if (advisory.severity() != null) {
            detail.append(" (").append(advisory.severity()).append(')');
        }
        detail.append(" - not applicable per VEX: ")
                .append(statement.status().name().toLowerCase(Locale.ROOT));
        if (statement.justification() != null && !statement.justification().isBlank()) {
            detail.append(" (").append(statement.justification().strip()).append(')');
        }
        if (statement.document() != null && !statement.document().isBlank()) {
            detail.append(", statement ").append(statement.document().strip());
        }
        return detail.toString();
    }

    /** The recorded reason for a waiver-downgraded advisory: the advisory (with its severity), the expiry the risk is
     *  accepted until and the operator's reason where one was given - so a reviewer reading the assessment sees which
     *  vulnerability was let through, until when, and why. */
    private static String waiverDetail(AdvisorySource.Advisory advisory, Waiver waiver) {
        StringBuilder detail = new StringBuilder(advisory.id());
        if (advisory.severity() != null) {
            detail.append(" (").append(advisory.severity()).append(')');
        }
        detail.append(" - risk accepted per waiver");
        if (waiver.expires() != null) {
            detail.append(" until ").append(waiver.expires());
        }
        if (waiver.reason() != null && !waiver.reason().isBlank()) {
            detail.append(" (").append(waiver.reason().strip()).append(')');
        }
        return detail.toString();
    }

    /**
     * The verdict fold every {@link Assessment} carries: the strongest verdict across a set of findings,
     * {@link Verdict#ALLOW} when there are none. Order-independent by construction, which is what lets the discovered
     * {@link GatePolicy} dimensions compose without knowing of each other. Public because it is the gate's own
     * definition of what a set of findings amounts to - a caller that ranks or re-screens findings outside an
     * {@link Assessment} (the contract suite that holds each dimension to the verdict it must reach) must fold them
     * the way the gate does rather than keep a second copy of this loop.
     */
    public static Verdict strongest(List<Finding> findings) {
        Verdict verdict = Verdict.ALLOW;
        for (Finding finding : findings) {
            if (finding.verdict().compareTo(verdict) > 0) {
                verdict = finding.verdict();
            }
        }
        return verdict;
    }

    /**
     * The jar's {@code Bundle-License} header as a declared license, or {@code null} if absent - the fallback a
     * caller adds to {@link Subject#licenses()} when a POM declares none.
     *
     * <p>The manifest is walked to under {@link ArchiveInflation}'s sibling bound
     * {@link ArchiveWalk#largestWalk()} and inflated at the shared manifest tier
     * ({@link ArchiveInflation#entry(InputStream)}), so a {@code META-INF/MANIFEST.MF} that is a deflate bomb
     * - a few compressed KiB claiming gigabytes of headers - is refused within the cap instead of inflated into a
     * {@link Manifest} on the publish thread. (A plain {@code JarInputStream.getManifest()} would inflate it whole:
     * the jar {@code byte[]} handed in is a bounded prefix, but nothing bounds what one entry inflates TO.) The
     * manifest must still be the archive's first member, exactly as {@code JarInputStream} requires, so which jars
     * carry a readable {@code Bundle-License} is unchanged; only the bomb is now stopped. A jar that is not a
     * readable archive, or whose manifest exceeds the tier, declares nothing here - the caller's coordinate comes
     * from the request path, so this is an optional declaration and losing it can only under-declare a licence.
     */
    public static DeclaredLicense bundleLicense(byte[] jar) {
        return bundleLicense(new ByteArrayInputStream(jar), ArchiveWalk.largestWalk());
    }

    /**
     * The same header off an already-open jar stream, under the caller's own ceiling - the form an inspector takes
     * when a screen hands it the artifact rather than a bounded prefix of it.
     *
     * <p>The ceiling is the caller's because the two legs read at different tiers by design: a bounded prefix is
     * walked at the flat archive-walk tier, and a streamed body at the full-body tier, which is what makes the
     * streamed leg reach further than the bounded one rather than merely cost more. The stream is consumed and
     * closed by the walk; whether it was the whole artifact is the caller's business, since only the caller knows
     * which leg it is on.
     */
    public static DeclaredLicense bundleLicense(InputStream jar, long ceiling) {
        try {
            return ArchiveWalk.walk(jar, ceiling, ComplianceGate::declaredBundleLicense).orNull();
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /** The {@code Bundle-License} of an already-bounded jar stream, or null when it declares none. */
    private static DeclaredLicense declaredBundleLicense(InputStream jar) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(jar)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;                              // the META-INF/ directory entry precedes the manifest
                }
                if (!JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.getName())) {
                    return null;                           // a non-first manifest is not one a jar reader would read
                }
                // An OPTIONAL declaration: the coordinate comes from the request path either way, so a manifest the
                // inflation ceiling stopped degrades to "declares no licence" through orNull() rather than failing
                // the publish - never to the prefix that was read before the ceiling.
                byte[] bytes = ArchiveInflation.entry(zip).orNull();
                if (bytes == null) {
                    return null;
                }
                Manifest manifest = new Manifest(new ByteArrayInputStream(bytes));
                String value = manifest.getMainAttributes().getValue("Bundle-License");
                if (value == null || value.isBlank()) {
                    return null;
                }
                int semicolon = value.indexOf(';');
                return new DeclaredLicense((semicolon < 0 ? value : value.substring(0, semicolon)).trim(), null);
            }
            return null;
        }
    }
}
