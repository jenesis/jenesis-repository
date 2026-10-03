package build.jenesis.repository.gate.store;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ComplianceSettings;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.Maintainer;
import build.jenesis.repository.compliance.Maintainers;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.compliance.SignerTrustProvider;
import build.jenesis.repository.findings.AdvisoryFindings;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.inventory.AboutSection;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.Recording;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.gate.QuarantineLog;

/**
 * What the publish gate writes down about a screened upload once its outcome is routed: an accepted publish's
 * {@link Recording} in the inventory, the publish-time advisory findings and maintainer-health that close the window
 * before the next sweep, the maintainers the metadata names and the signers the inspection met, and the gate and
 * inspection findings of an upload that was held or could not be read. The {@link QuarantineLog} line and the hold
 * records are not here; they are the hold's, and {@link PublishHolds} writes them.
 *
 * <p>Every write but the inventory recording is best-effort, as each method says: the decision is already durable,
 * so a lost derived row is caught up by the next sweep or the next screen of the path, and never fails a publish.
 *
 * <p>The {@link ComplianceScreen} builds one per commit from what the deployment wired, and hands it everything
 * explicitly - the discovered findings and health-ledger modules it holds, and the advisory feeds, the feed-miss
 * sink and the live health source as they are wired at that moment - so the wiring keeps one owner and nothing here
 * reads a JVM-wide reference of its own.
 */
final class PublishRecorder {

    /** The screen's own category, so an operator's logging level for the gate covers every line it writes. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ComplianceScreen.class);

    /** The findings ledger, when a persistence module is installed; empty, nothing here writes a finding. */
    private final Optional<FindingsProvider> findings;

    /** The durable maintainer-health ledger, when a persistence module is installed; empty, no health is persisted. */
    private final Optional<HealthLedgerProvider> healthLedger;

    /** The deployment's named advisory feeds, or {@code null} while none are wired. */
    private final Supplier<SequencedMap<String, AdvisorySource>> advisoryFeeds;

    /** Where a feed the warm cache could not answer is counted, or {@code null} while no sink is wired. */
    private final ComplianceScreen.FeedMissListener misses;

    /** The deployment's live maintainer-health source, or {@code null} while none is wired. */
    private final Supplier<HealthSource> healthSource;

    PublishRecorder(Optional<FindingsProvider> findings, Optional<HealthLedgerProvider> healthLedger,
                    Supplier<SequencedMap<String, AdvisorySource>> advisoryFeeds,
                    ComplianceScreen.FeedMissListener misses, Supplier<HealthSource> healthSource) {
        this.findings = findings;
        this.healthLedger = healthLedger;
        this.advisoryFeeds = advisoryFeeds;
        this.misses = misses;
        this.healthSource = healthSource;
    }

    /**
     * Record an accepted publish, in the order the facts depend on one another: the inventory recording first, then
     * the advisory findings and the health it is re-queried for, then the maintainers before the signers, since
     * whom the metadata names is what a key found through a maintainer is judged against.
     */
    void accepted(ArtifactStore store, StoreRepositoryInventory inventory, ArtifactDescriptor artifact,
                  List<ComplianceGate.Subject> inspected) throws IOException {
        // One recording per publish: the published facts, the local-upload origin (the content hash the store
        // computed on write - origin never re-reads the body), the declared licences and the provenance
        // summary land in one write of the version's document, and the identity index folds once with the
        // licences it ends up holding. Keyed by the coordinate a format describes the path to, else by the
        // inspected subject's: npm, PyPI, NuGet and RubyGems publish to a versionless envelope endpoint whose
        // path carries no version, and the inspector parsed the real per-version coordinate.
        recordPublish(inventory, artifact, inspected);
        // Persist the publish-time advisory answer for the just-accepted coordinate, keyed by the real
        // (feed, advisory-id) the scheduled sweep also writes - so a coordinate published after the last
        // sweep already carries its advisory findings instead of rendering clean until the next pass.
        recordAdvisoryFindings(store, artifact, inspected);
        // Persist the publish-time maintainer-health for the just-accepted coordinate, the health sibling of
        // the advisory persistence above - so a coordinate published after the last health sweep already
        // carries its health in the durable ledger the gate now reads, and admission of a later version of the
        // same coordinate scores off a populated ledger rather than the not-yet-swept fallback.
        recordHealth(store, artifact, inspected);
        // Continuity is learned from what actually landed: every signature that verified by a trusted signer
        // on an accepted publish is observed, so the next version by another signer is measured against it.
        recordMaintainers(store, inspected, artifact.path());
        reportSigners(store, inspected, artifact.path(), true);
    }

    /**
     * Persist a quarantining assessment's reasons as structured rows in the findings ledger - one attributed
     * {@link Finding.Kind#GATE} row per gate finding, keyed by the coordinate the layout descriptor maps the path
     * to (the same coordinate the {@code published} record uses, so a later release's eviction reclaims the
     * rows) - beside the {@link QuarantineLog}'s flat audit line. Held to quarantines only: a rejected upload stores
     * no artifact whose lifecycle could ever reclaim its rows, so its trail stays the retention-pruned log. Best
     * effort like every derived write here - the hold and the log line are already durable, so a failed ledger write
     * must not fail the publish choreography - and a no-op when no findings module is installed.
     */
    void recordGateFindings(ArtifactStore store, ArtifactDescriptor artifact,
                            ComplianceGate.Assessment assessment) {
        if (findings.isEmpty() || assessment == null || assessment.findings().isEmpty()) {
            return;
        }
        try {
            Optional<ArtifactDescriptor> described = new StoreRepositoryInventory(store).describe(artifact.path());
            if (described.isEmpty() || described.get().coordinate() == null || described.get().version() == null) {
                return;
            }
            Findings ledger = findings.get().over(store);
            Instant now = Clocks.now();
            // One batched commit for the whole assessment's gate rows, not one CAS per reason.
            List<Finding> rows = new ArrayList<>();
            for (ComplianceGate.Finding finding : assessment.findings()) {
                // The id is a stable digest of the reason text, so a re-screen of the same body refreshes the row.
                rows.add(Finding.of("gate-" + Integer.toHexString(finding.detail().hashCode()),
                                ComplianceScreen.GATE_SOURCE, Finding.Kind.GATE, finding.verdict().name(),
                                Severity.NONE, finding.detail(), now)
                        .withProvenance(artifact.path()));
            }
            ledger.recordAll(described.get().ecosystem(), described.get().coordinate(), described.get().version(),
                    rows);
        } catch (IOException | RuntimeException _) {
            // best-effort: the hold pointer and the quarantine log line carry the decision; the ledger catches up
            // on the next screen of the path
        }
    }

    /**
     * Persist the distinct inspection-failed finding for an artifact an inspector could not parse - one
     * {@link Finding.Kind#INSPECTION} row on the coordinate, so a reader sees "could not derive - not fully screened"
     * rather than a silent clean. Keyed by the layout descriptor's coordinate where the path maps to one, else the
     * path-derived subject's own coordinate (a versionless envelope publish, or a filename fallback), so the row is
     * always recorded rather than lost when the path does not describe. Best-effort like the sibling derived writes -
     * the WARNING log (and, when held, the quarantine log) already carry the failure - and a no-op when no findings
     * module is installed.
     */
    void recordUnparseableFinding(ArtifactStore store, ArtifactDescriptor artifact,
                                  List<ComplianceGate.Subject> inspected, String reason, String code) {
        if (findings.isEmpty()) {
            return;
        }
        try {
            String ecosystem;
            String coordinate;
            String version;
            Optional<ArtifactDescriptor> described = new StoreRepositoryInventory(store).describe(artifact.path());
            if (described.isPresent() && described.get().coordinate() != null && described.get().version() != null) {
                ecosystem = described.get().ecosystem();
                coordinate = described.get().coordinate();
                version = described.get().version();
            } else if (inspected != null && !inspected.isEmpty()) {
                ComplianceGate.Subject subject = inspected.getFirst();
                ecosystem = subject.ecosystem();
                coordinate = subject.coordinate();
                version = subject.version();
            } else {
                return;
            }
            if (coordinate == null || coordinate.isEmpty()) {
                return;
            }
            findings.get().over(store).record(ecosystem == null ? "" : ecosystem, coordinate,
                    version == null ? "" : version,
                    Finding.of("inspection-" + Integer.toHexString(artifact.path().hashCode()),
                                    ComplianceScreen.INSPECTION_SOURCE, Finding.Kind.INSPECTION, code, Severity.NONE, reason, Clocks.now())
                            .withProvenance(artifact.path()));
        } catch (IOException | RuntimeException _) {
            // best-effort: the WARNING log and (when held) the quarantine log carry the failure; the ledger row is a
            // bonus that a later re-screen of the path refreshes
        }
    }

    /**
     * Persist the publish-time advisory answer for a just-accepted coordinate: re-query the deployment's named
     * advisory feeds - the same instances the gate assessed through, so the gate's own lookup just warmed each feed's
     * {@code FeedCache} and this is a cache read, not a fresh network pass - and record each hit as a structured
     * finding keyed by the real {@code (feed, advisory-id)} pair, exactly as the scheduled
     * {@code VulnerabilityScanTask} writes them ({@link AdvisoryFindings#of}). This closes the between-sweeps window: a
     * coordinate published AFTER the last sweep already carries its advisory findings rather than rendering clean until
     * the next pass, and because the rows key by {@code (source, id)} the later sweep converges on the identical rows
     * instead of doubling them. Held to ACCEPT, where the coordinate is recorded as published and the sweep will
     * revisit it - a quarantined or rejected upload has no such record, so persisting its advisory rows would strand
     * them. Best-effort like every derived write here, and fail-soft <em>per feed</em>: a feed the warm cache cannot
     * answer (a feed failing closed with nothing cached) is logged and metered, never a reason to fail an
     * already-accepted publish. A no-op when no findings module is installed or no feeds are wired.
     */
    void recordAdvisoryFindings(ArtifactStore store, ArtifactDescriptor artifact,
                                List<ComplianceGate.Subject> inspected) {
        if (findings.isEmpty()) {
            return;
        }
        Supplier<SequencedMap<String, AdvisorySource>> supplier = advisoryFeeds;
        if (supplier == null) {
            return;
        }
        SequencedMap<String, AdvisorySource> feeds = supplier.get();
        if (feeds == null || feeds.isEmpty()) {
            return;
        }
        Optional<StoreRepositoryInventory.Coordinate> published = publishedCoordinate(store, artifact, inspected);
        if (published.isEmpty()) {
            return;
        }
        StoreRepositoryInventory.Coordinate coordinate = published.get();
        Findings ledger = findings.get().over(store);
        Instant now = Clocks.now();
        // Accumulate every feed's rows, then commit them in ONE section mutate; the per-feed query stays
        // fail-soft (a warm-cache miss defers to the sweep) but the persist is a single batched write per coordinate.
        List<Finding> rows = new ArrayList<>();
        for (Map.Entry<String, AdvisorySource> feed : feeds.entrySet()) {
            List<AdvisorySource.Advisory> found;
            try {
                found = feed.getValue().advisories(coordinate.ecosystem(), coordinate.coordinate(),
                        coordinate.version());
            } catch (RuntimeException failure) {
                // Fail-soft: a warm-cache miss whose refresh failed with nothing cached rethrows here (the feed fails
                // closed for the gate, but the publish is already accepted and stored). Log and meter the miss and move
                // on; the scheduled sweep records this feed's rows on its next pass.
                LOGGER.warn("Could not re-query advisory feed " + feed.getKey()
                        + " for " + coordinate.coordinate() + ":" + coordinate.version()
                        + " at publish; its rows wait for the next sweep", failure);
                ComplianceScreen.FeedMissListener miss = misses;
                if (miss != null) {
                    miss.missed(feed.getKey());
                }
                continue;
            }
            for (AdvisorySource.Advisory advisory : found) {
                rows.add(AdvisoryFindings.of(advisory, feed.getKey(), artifact.path(), now));
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        try {
            ledger.recordAll(coordinate.ecosystem(), coordinate.coordinate(), coordinate.version(), rows);
        } catch (IOException | RuntimeException failure) {
            // best-effort: the artifact is stored and the sweep converges on these rows; a lost write only defers
            LOGGER.warn("Could not persist publish-time advisory findings for "
                    + coordinate.coordinate() + ":" + coordinate.version(), failure);
        }
    }

    /**
     * Record on the coordinate whom the artifact's metadata names as its maintainers ({@link Maintainers}) - for an
     * accepted or a held upload, never a refused one - before the signature question is asked, since Maven's
     * signature arrives one request after the POM and its re-assessment reads this record for whom to ask. It is
     * what a key found through a maintainer is judged against: such a key admits only a coordinate whose record
     * names that person. Best-effort like every derived write: a lost record delays a binding being satisfied and
     * can never admit a signer.
     */
    void recordMaintainers(ArtifactStore store, List<ComplianceGate.Subject> inspected, String path) {
        Set<String> maintainers = maintainers(inspected);
        if (maintainers.isEmpty()) {
            return;
        }
        try {
            for (ComplianceGate.Subject subject : inspected) {
                if (!subject.contentScan() && subject.ecosystem() != null && subject.coordinate() != null) {
                    Maintainers.record(store, subject.ecosystem(), subject.coordinate(), maintainers);
                    return;
                }
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not record the maintainers of {}; the next version records them again", path, failure);
        }
    }

    /** The identities every inspected subject names as maintainers - gathered across subjects, since the package
     *  subject carries the metadata and the signature subject the signatures. */
    private static Set<String> maintainers(List<ComplianceGate.Subject> inspected) {
        Set<String> maintainers = new LinkedHashSet<>();
        for (ComplianceGate.Subject subject : inspected == null ? List.<ComplianceGate.Subject>of() : inspected) {
            maintainers.addAll(Maintainer.ids(subject.maintainers()));
        }
        return maintainers;
    }

    /**
     * Tell the trust what the inspection found about signers: every signature that verified by a trusted signer on
     * a version the inspection could place on a coordinate is observed, so continuity is learned from what landed
     * (only when {@code accepted}), and every signature by a signer no source held a key for is reported wanted,
     * so a discovery source knows what to fetch. Best-effort by the seam's own contract: a lost observation delays
     * an expectation, a lost want delays a fetch, and neither can admit a signer. Every caller says whether the
     * publish was accepted, since a held or refused upload's bytes never landed and its signer is no continuity.
     */
    void reportSigners(ArtifactStore store, List<ComplianceGate.Subject> inspected, String path,
                       boolean accepted) {
        if (inspected == null || inspected.stream().noneMatch(subject -> !subject.signatures().isEmpty())) {
            return;
        }
        // Whom the artifact names travels with every want, so a discovery source that looks a key up by its owner
        // knows whom to ask and what the key it finds is bound to.
        Set<String> maintainers = maintainers(inspected);
        SignerTrust trust = SignerTrustProvider.trust(ComplianceSettings.lookup(store), store);
        for (ComplianceGate.Subject subject : inspected) {
            for (ComplianceGate.Signature signature : subject.signatures()) {
                if (signature.signer() == null) {
                    continue;
                }
                try {
                    if (signature.trusted() && accepted && subject.ecosystem() != null && subject.coordinate() != null
                            && subject.version() != null && !subject.version().isEmpty()) {
                        trust.observed(subject.ecosystem(), subject.coordinate(), subject.version(),
                                signature.signer(), Clocks.now());
                    } else if (signature.outcome() == ComplianceGate.Signature.Outcome.UNTRUSTED
                            && signature.keySource() == null) {
                        trust.wanted(signature.signer(), path, maintainers, Clocks.now());
                    }
                } catch (IOException | RuntimeException failure) {
                    LOGGER.warn("Could not report the signer of {}; the next version reports it again", path, failure);
                }
            }
        }
    }

    /**
     * Persist the publish-time maintainer-health for a just-accepted coordinate: probe the deployment's live health
     * source - the same instance the scheduled sweep probes, so its {@code FeedCache} may already carry the answer -
     * and record it into the durable {@link HealthLedger} keyed by the coordinate (version-independent), exactly as the
     * {@code HealthScanTask} writes it. This closes the between-sweeps window: a coordinate published AFTER the last
     * health sweep already carries its health in the ledger the gate now reads, so admission of a LATER version of the
     * same coordinate scores off a populated ledger rather than the not-yet-swept fallback. Held to ACCEPT, where the
     * coordinate is recorded as published and the sweep will revisit it. A coordinate the source scores nothing is
     * left unrecorded (unknown, not healthy), and one whose ledger already holds the health the probe answers is left
     * as it is: rewriting it would cost every version's publish a write of the coordinate's one document, which
     * concurrent publishes of one coordinate contend for, and the sweep refreshes it. The ledger's answer is the one
     * the gate read for the same publish. Best-effort like every derived write here - the artifact is already
     * stored, so a failed probe or write must not fail an accepted publish - and a no-op when no health-ledger module
     * is installed or no live source is wired; the sweep then records the coordinate on its next pass. The commit does
     * NOT stamp {@link build.jenesis.repository.health.HealthLedger#scanned health stamp}: a single-coordinate
     * persistence is not a full scan, so the staleness stamp stays the last full sweep's, never masking that a fresh
     * publish outran the sweep.
     */
    void recordHealth(ArtifactStore store, ArtifactDescriptor artifact, List<ComplianceGate.Subject> inspected) {
        if (healthLedger.isEmpty()) {
            return;
        }
        Supplier<HealthSource> supplier = healthSource;
        if (supplier == null) {
            return;
        }
        HealthSource source = supplier.get();
        if (source == null || source == HealthSource.none()) {
            return;
        }
        Optional<StoreRepositoryInventory.Coordinate> published = publishedCoordinate(store, artifact, inspected);
        if (published.isEmpty()) {
            return;
        }
        StoreRepositoryInventory.Coordinate coordinate = published.get();
        Optional<HealthSource.Health> health;
        try {
            health = source.health(coordinate.ecosystem(), coordinate.coordinate());
        } catch (RuntimeException failure) {
            // The live source degrades to empty on its own (a ranking signal, not a hard gate); a probe that instead
            // throws is caught here so an accepted publish is never failed for it. The sweep records the coordinate
            // next pass.
            LOGGER.warn("Could not probe maintainer-health for "
                    + coordinate.coordinate() + " at publish; its health waits for the next sweep", failure);
            return;
        }
        if (health.isEmpty()) {
            return;                                             // unscored: left unrecorded (unknown, not healthy)
        }
        try {
            HealthLedger ledger = healthLedger.get().over(store);
            if (ledger.health(coordinate.ecosystem(), coordinate.coordinate()).equals(health)) {
                return;
            }
            ledger.record(coordinate.ecosystem(), coordinate.coordinate(), health.get(), Clocks.now());
        } catch (IOException | RuntimeException failure) {
            // best-effort: the artifact is stored and the sweep converges on this record; a lost write only defers
            LOGGER.warn("Could not persist publish-time maintainer-health for "
                    + coordinate.coordinate(), failure);
        }
    }

    /** The {@code (ecosystem, coordinate, version)} this repository recorded as published for the
     *  accepted upload - exactly what the scheduled sweep reads back and re-queries, so the publish-time advisory rows
     *  key identically and converge. It is the path descriptor's coordinate where the layout maps the path to one (a
     *  Maven publish), else the inspected subject's own coordinate for a versionless envelope publish
     *  (npm/PyPI/NuGet/RubyGems) - the same two sources {@link #recordPublish} keys its recording by. Empty when
     *  neither yields a coordinate (a checksum, generated metadata). */
    static Optional<StoreRepositoryInventory.Coordinate> publishedCoordinate(
            ArtifactStore store, ArtifactDescriptor artifact, List<ComplianceGate.Subject> inspected) {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Optional<ArtifactDescriptor> described = inventory.describe(artifact.path());
        if (described.isPresent() && described.get().coordinate() != null && described.get().version() != null) {
            return Optional.of(new StoreRepositoryInventory.Coordinate(
                    described.get().ecosystem(), described.get().coordinate(), described.get().version()));
        }
        return subjectCoordinate(inventory, inspected == null || inspected.isEmpty() ? null : inspected.getFirst());
    }

    /**
     * The coordinate an inspected subject names, spelled as the layout owning its ecosystem keys it. The subject reads
     * it out of the artifact as the artifact writes it - a NuGet id in its declared case - and the layout serves the
     * version under its own normal form, so a record keyed by the subject's spelling would be one no read resolves
     * to: a pushed package's signature, licences and hold records would land in a document beside the one its reads
     * find. The subject's own spelling stands only where no installed layout places the ecosystem.
     */
    private static Optional<StoreRepositoryInventory.Coordinate> subjectCoordinate(
            StoreRepositoryInventory inventory, ComplianceGate.Subject subject) {
        if (subject == null || subject.coordinate() == null || subject.coordinate().isEmpty()
                || subject.version() == null || subject.version().isEmpty()) {
            return Optional.empty();
        }
        return inventory.canonical(subject.ecosystem(), subject.coordinate(), subject.version())
                .or(() -> Optional.of(new StoreRepositoryInventory.Coordinate(
                        subject.ecosystem(), subject.coordinate(), subject.version())));
    }

    /**
     * One {@link Recording} per accepted publish. The path's coordinate where an installed format describes it, else
     * the inspected subject's; the origin is the accepted body's hash; the licences and the provenance summary are
     * the first inspected subject's, when an inspector ran. A publish that resolves to no coordinate at all - a path
     * nothing describes and no subject - records nothing.
     *
     * <p>The first subject is the artifact itself - the root of the inspected set, ahead of any transitive dependency
     * - so its licences are the artifact's own; an empty list is still recorded, marking the artifact as
     * inspected-but-licence-free so the search sweep indexes it as unknown rather than re-parsing it.
     */
    void recordPublish(StoreRepositoryInventory inventory, ArtifactDescriptor artifact,
                       List<ComplianceGate.Subject> inspected) throws IOException {
        ComplianceGate.Subject subject = inspected == null || inspected.isEmpty() ? null : inspected.getFirst();
        Instant now = Clocks.now();
        Recording recording = inventory.recording(artifact.path(), now).orElse(null);
        if (recording == null) {
            Optional<StoreRepositoryInventory.Coordinate> named = subjectCoordinate(inventory, subject);
            if (named.isEmpty()) {
                return;
            }
            recording = inventory.recording(named.get().ecosystem(), named.get().coordinate(), named.get().version(),
                    false, now).file(artifact.path());
        }
        recording.origin(artifact.hash());
        if (subject != null) {
            List<LicenseInventory.Declared> declared = new ArrayList<>();
            for (ComplianceGate.DeclaredLicense license : subject.licenses()) {
                declared.add(new LicenseInventory.Declared(license.name(), license.url()));
            }
            recording.licenses(declared);
            if (subject.dependencies() != null) {
                // Recorded only where the inspector read the manifest for them: an empty list is a manifest that
                // declares none, and a subject no inspector read for them leaves the section as it was.
                recording.dependencies(subject.dependencies().stream()
                        .map(dependency -> new DependencySection.Declared(dependency.coordinate(),
                                dependency.requirement()))
                        .toList());
            }
            // What the manifest says the package is for, and whom it credits by name, for a full-text index to find
            // it by: in the same write as the rest, so it costs no store operation of its own.
            ComplianceGate.About about = subject.about();
            List<String> authors = new ArrayList<>(about == null ? List.of() : about.authors());
            subject.maintainers().stream().map(Maintainer::name).filter(Objects::nonNull).forEach(authors::add);
            recording.about(new AboutSection.About(about == null ? null : about.description(),
                    about == null ? List.of() : about.keywords(), authors));
            ComplianceGate.Attestation attestation = subject.attestation();
            if (attestation != null) {
                recording.provenance(attestation.artifactPresent() && attestation.artifactDigest() != null,
                        attestation.artifactDigest());
            }
        }
        // Signatures are read from EVERY subject, not from the first. A signature subject carries no licensable
        // identity, so InspectionMerge sorts it last - reading only the head would find the format's package subject
        // and silently record no signature for every artifact whose format also has an inspector, which is all of
        // them that matter.
        List<ComplianceGate.Signature> signatures = inspected == null ? List.of() : inspected.stream()
                .flatMap(each -> each.signatures().stream())
                .toList();
        Optional<ComplianceGate.Signature> summary = ComplianceGate.Signature.summarising(signatures);
        if (summary.isPresent()) {
            recording.signature(summary.get().outcome().name(),
                    summary.get().signer() == null ? null : summary.get().signer().wire(),
                    summary.get().quality() == null ? null : summary.get().quality().grade().name(),
                    summary.get().location(), summary.get().keySource(), summary.get().details());
        }
        recording.commit();
    }
}
