package build.jenesis.repository.gateway;

import build.jenesis.repository.compliance.ComplianceSettings;
import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.SignerTrustProvider;
import build.jenesis.repository.compliance.TrustAware;
import build.jenesis.repository.gate.InspectionMerge;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.MalformedArtifactException;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The proxy fetch firewall for any format's proxy leg, so a fetched artifact is screened rather than served unchecked -
 * on both a router-configured proxy repository and the deployment-wide default. It inspects the artifact for compliance
 * subjects (its coordinate and, for a POM, its transitive closure), assesses them against the {@link ComplianceGate},
 * holds a version the upstream published within the immaturity window, and records a quarantined or rejected artifact -
 * storing a quarantined one under {@code /quarantine} for review. {@link #wrap} adapts an upstream fetcher to screen
 * its result: a non-{@link Verdict#ALLOW} verdict becomes an empty result, so the pull-through treats it as a miss and
 * the artifact never reaches the build.
 */
public final class ProxyScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyScreen.class);

    private static final List<QualityInspector> INSPECTORS = QualityInspector.all();

    /** The deployment's signer trust, overlaid onto every {@link TrustAware} inspector before it runs - the proxy
     *  leg's half of the seam the publish screen applies. Without it a proxied artifact's signature would be checked
     *  against nothing while the publish path checked it properly, which is the kind of asymmetry that makes one leg
     *  a way around the other. */
    private static final boolean TRUST_INSTALLED = !SignerTrustProvider.installed().isEmpty();

    /** The most bytes screened from a claimed proxied artifact: a claimed artifact is a metadata document or a small
     *  archive by the inspectors' nature, whose declaration sits at the front, so a bounded prefix carries everything
     *  they read while a pathologically large jar can no longer be pulled whole into a {@code byte[]} to gate it on
     *  the proxy leg. Beyond the cap the remainder streams straight through to the client/store, never buffered.
     *  <p>It is the SPI's prefix tier itself, not a screen-local copy of the same number: the {@code byte[]} legs are
     *  contractually handed at most that much, and {@link #assessSubjects} decides whether the inspectors saw the
     *  artifact whole by comparing the body against this very bound - so a screen reading a different amount than the
     *  tier the inspectors are written against would make that completeness test answer about a different body. */
    static int inspectionLimit() {
        return QualityInspector.prefixInspectionLimit();
    }

    /** The ceiling on a whole-document {@link QualityInspector.Lookup#fetch} of an already-published sibling - a
     *  companion an inspector reads entire (an attestation referrer, a small index page). A sibling larger than this
     *  fails the read loudly rather than pulling a multi-gigabyte companion into a heap {@code byte[]} on the gateway
     *  thread. A bounded read is capped at its own requested limit by {@link SiblingLookup#fetchBounded}, streamed from
     *  the source, and is not subject to this ceiling at all.
     *  <p>It <em>is</em> the sibling cap rather than a copy of the same number. This constant used to hold
     *  its own {@code 8 * 1024 * 1024} and a comment saying it "mirrors" free {@code Publication.LARGEST_SIBLING},
     *  which was all the equality could ever be while the constant was private - two numbers that had to be kept
     *  equal by hand across two repositories. The free core now publishes it, so the publish leg (which reads through
     *  {@link build.jenesis.repository.store.PublishInterceptor.Content#sibling(String)}, capped there) and this proxy
     *  leg now cannot drift on what "too large to read whole" means. */
    static final int SIBLING_LIMIT = PublishInterceptor.Content.LARGEST_SIBLING;

    /** The reason line an incompletely screened artifact carries - one constant, so the durable {@link QuarantineLog}
     *  row, the log line and any read surface name the same thing rather than three near-identical sentences. */
    static final String INCOMPLETE_SCREEN_REASON = "Incompletely screened: an inspector's read stopped at a bound "
            + "before the whole artifact was screened, so this decision covers only the part that was read";

    /** Screens that reached an {@code ALLOW} over an artifact an inspector could not read to the end, since the
     *  gateway started - the {@code jenreg.gateway.screen.incomplete} counter (§9). Static so every per-request screen
     *  contributes to the one gateway-wide count {@link HardeningObservability} reports, exactly as the hardened leg's
     *  drift alarm does. It counts the served ones only: a withheld artifact already carries the fact in its durable
     *  quarantine row. */
    private static final AtomicLong INCOMPLETE_SCREENS = new AtomicLong();

    /** The number of artifacts served on an incomplete screen - a bound stopped some claiming inspector's read before
     *  the whole body was screened, and the gate found nothing to withhold in the part that WAS read. A rising count
     *  means the deployment routinely serves artifacts bigger than its inspection tiers, which is the signal for
     *  raising a tier rather than a defect in itself. Surfaced as a metric by {@link HardeningObservability}. */
    public static long incompleteScreens() {
        return INCOMPLETE_SCREENS.get();
    }

    private final ComplianceGate gate;
    private final ArtifactStore store;
    private final Publication publication;
    private final int holdDays;

    /** Whether to withhold on an incomplete screen rather than serve with the fact recorded. */
    private final boolean withholdIncomplete;
    private final QualityInspector.Lookup siblings = new SiblingLookup();

    /** What the pull-through fetched beside the artifact being screened, by the path it is kept at; empty on every
     *  leg that fetched none. Read by {@link SiblingLookup} ahead of the store. */
    private Map<String, byte[]> companions = Map.of();

    /** The reviewed-fail-open form: an incomplete screen serves and says so. Every caller that has no opinion gets
     *  this, which is the reviewed default rather than an oversight. */
    public ProxyScreen(ComplianceGate gate, ArtifactStore store, int holdDays) {
        this(gate, store, holdDays, false);
    }

    public ProxyScreen(ComplianceGate gate, ArtifactStore store, int holdDays, boolean withholdIncomplete) {
        this.gate = gate;
        this.store = store;
        this.publication = new Publication(store);
        this.holdDays = holdDays;
        this.withholdIncomplete = withholdIncomplete;
    }

    /** Wrap an upstream fetcher to screen its fetched artifact: a non-{@link Verdict#ALLOW} verdict becomes an empty
     *  result, so the pull-through treats it as a miss and neither caches nor serves it. The {@link
     *  ProxyFormat.Fetcher#download streaming download} is overridden too: an artifact no inspector claims has nothing
     *  to screen, so it streams straight from upstream to the store. A claimed artifact IS screened, but only over a
     *  bounded {@link #inspectionLimit()} prefix - the firewall reads the front of the stream, reaches its verdict, and
     *  then streams the un-buffered remainder straight to the client/store on {@code ALLOW}, so a large claimed proxy
     *  artifact is never materialised whole in heap (which routing a claimed download through the buffered {@link
     *  ProxyFormat.Fetcher#fetch fetch} would do). A withheld artifact is streamed into {@code /quarantine} for review
     *  and recorded in the durable log.
     *
     *  <p><b>The metadata leg ({@link ProxyFormat.Fetcher#head}).</b> The screen is a <em>decorator</em> over a real
     *  transport, so it is never a {@link ProxyFormat.Fetcher.Buffered}: all three legs are declared, and {@code head}
     *  delegates to the wrapped transport's real HTTP {@code HEAD}. It must - a derived {@code head} would run this
     *  screen over a body prefix (opening, and here reading, an artifact's body) to answer a question about metadata,
     *  and on a claimed path it would quarantine and log from a request that asked for no bytes at all.
     *
     *  <p>What a {@code HEAD} <em>can</em> be screened against is the body-free half of this screen: an upstream
     *  metadata answer carries no content for an inspector to turn into a subject, but it does carry the coordinate and
     *  the {@code Last-Modified} the operator deny-list and the immaturity hold are assessed from - the same
     *  path-derived assessment the UNCLAIMED download leg already applies without reading a body. So the delegated
     *  answer is held to that assessment and a non-{@link Verdict#ALLOW} verdict withholds it (an empty result, the
     *  miss shape the other two legs use), rather than letting a metadata probe become an existence-and-size oracle for
     *  a coordinate the operator denied - the {@code deny com.evil:*} bypass the download leg closes, closed on the
     *  metadata leg too. The content dimensions (license, embedded secrets) are not answerable without content and are
     *  not faked here; the {@code GET} that follows is screened in full.
     *
     *  <p>Nothing is quarantined or logged on this leg: the durable record accompanies a withheld <em>body</em> there is
     *  a copy of to review, and a bodiless probe withholds none - while recording per {@code HEAD} would let a client's
     *  probe loop write the durable log without ever fetching an artifact. */
    public ProxyFormat.Fetcher wrap(ProxyFormat.Fetcher delegate, String path) {
        return wrap(delegate, path, Map.of());
    }

    /** As {@link #wrap(ProxyFormat.Fetcher, String)}, with the companions the pull-through fetched beside the
     *  artifact - the upstream's own signature or bundle for it, keyed by the path each is kept at - so the
     *  inspectors' sibling reads are answered from them before the store, which cannot hold them yet: the fill
     *  links nothing before this screen has decided. */
    public ProxyFormat.Fetcher wrap(ProxyFormat.Fetcher delegate, String path, Map<String, byte[]> companions) {
        this.companions = Map.copyOf(companions);
        return new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) throws IOException {
                Optional<ProxyFormat.Fetched> fetched = delegate.fetch(url, headers);
                if (fetched.isPresent() && fetched.get().status() == 200
                        && screen(path, fetched.get().body(), lastModified(fetched.get().header("last-modified")))
                                != Verdict.ALLOW) {
                    return Optional.empty();
                }
                return fetched;
            }

            @Override
            public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) throws IOException {
                // Delegated to the transport's real HTTP HEAD, never derived: a derivation would open (and this screen
                // would then READ) an artifact body to answer a question about metadata, screening a prefix and even
                // quarantining/logging from a request that asked for no bytes (Principle 1: a HEAD answers from
                // metadata). A screen is a decorator, so it is never a Fetcher.Buffered.
                Optional<ProxyFormat.Head> head = delegate.head(url, headers);
                if (head.isEmpty() || head.get().status() != 200) {
                    return head;   // a transport failure or an upstream miss - nothing exists to screen or disclose
                }
                // The body-free half of the screen still applies: no content means no inspector subject, but the
                // coordinate and Last-Modified are right here, so the operator deny-list and the immaturity hold are
                // assessed exactly as the UNCLAIMED download leg assesses them without reading a body. A withheld
                // metadata answer is the same empty miss the other two legs return; a denied coordinate must not be
                // answerable as "exists, N bytes" just because the client asked without a body.
                Screening screening = assessUnclaimed(path, lastModified(head.get().header("last-modified")));
                return screening.verdict() == Verdict.ALLOW ? head : Optional.empty();
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) throws IOException {
                if (unclaimed(path)) {
                    // UNCLAIMED: no inspector can turn the body into a subject, but the operator deny-list must still
                    // bite a raw/un-inspected coordinate rather than streaming it through unscreened - the exact path an
                    // attacker uses to bypass "deny com.evil:*". Screen from a path-derived subject WITHOUT reading the
                    // body (the deny-list needs only the coordinate), and stream the un-buffered body through only on
                    // ALLOW; a withheld one is stored/discarded and logged exactly as a claimed withholding is.
                    Optional<ProxyFormat.Download> pulled = delegate.download(url, headers);
                    if (pulled.isEmpty() || pulled.get().status() != 200) {
                        return pulled;   // an upstream miss - nothing to screen or withhold
                    }
                    ProxyFormat.Download rawResponse = pulled.get();
                    Screening screening = assessUnclaimed(path, lastModified(rawResponse.header("last-modified")));
                    if (screening.verdict() == Verdict.ALLOW) {
                        return pulled;
                    }
                    try (rawResponse) {
                        if (screening.verdict() == Verdict.QUARANTINE) {
                            quarantine(path, rawResponse.body());
                        }
                        log(path, screening);
                    }
                    return Optional.empty();
                }
                Optional<ProxyFormat.Download> opened = delegate.download(url, headers);
                if (opened.isEmpty() || opened.get().status() != 200) {
                    return opened;
                }
                // A claimed artifact must be screened before it is served, but the firewall reads only a bounded
                // prefix: the rest streams straight through to the client/store, so a large claimed proxy artifact
                // is never materialised whole in heap the way the buffered fetch would.
                ProxyFormat.Download response = opened.get();
                InputStream body = response.body();
                byte[] prefix = body.readNBytes(inspectionLimit());
                // One byte past the prefix tells us whether the inspectors saw only a truncated head: if the artifact
                // runs beyond the inspection window, a claimed declaration sitting BEYOND it yields no subjects, which
                // must not be read as "nothing to screen" and streamed through un-gated. That lookahead byte was
                // consumed, so the continued stream must prepend it to stay byte-exact - no byte is lost from the body
                // served to the client or stored in /quarantine.
                int next = body.read();
                boolean truncated = next != -1;
                InputStream continued = truncated
                        ? new SequenceInputStream(new ByteArrayInputStream(new byte[]{(byte) next}), body)
                        : body;
                Screening screening = assess(path, prefix, lastModified(response.header("last-modified")), truncated);
                if (screening.verdict() == Verdict.ALLOW) {
                    return Optional.of(new ProxyFormat.Download(response.status(),
                            new SequenceInputStream(new ByteArrayInputStream(prefix), continued), response.headers()));
                }
                // Withheld: the pull-through sees an empty result and treats it as a miss. A held artifact is streamed
                // whole into the /quarantine store for review (still un-buffered), a rejected one is discarded; either
                // way the durable QuarantineLog row is written before the upstream body is closed.
                try (response) {
                    if (screening.verdict() == Verdict.QUARANTINE) {
                        quarantine(path, new SequenceInputStream(new ByteArrayInputStream(prefix), continued));
                    }
                    log(path, screening);
                }
                return Optional.empty();
            }
        };
    }

    static Instant lastModified(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
        } catch (RuntimeException _) {
            return null;
        }
    }

    Verdict screen(String path, byte[] body, Instant lastModified) throws IOException {
        // The buffered fetch body is the COMPLETE artifact, never a bounded prefix, so it is never truncated.
        Screening screening = assess(path, body, lastModified, false);
        if (screening.verdict() == Verdict.QUARANTINE) {
            quarantine(path, new ByteArrayInputStream(body));
        }
        log(path, screening);
        return screening.verdict();
    }

    /** Store a withheld body under {@code /quarantine} for review - the durable hold the {@link QuarantineLog} row
     *  points at. Shared by the buffered/streaming screen and the hardened proxy leg ({@link HardenedScreen}). */
    void quarantine(String path, InputStream body) throws IOException {
        // The durable path -> coordinate record goes first and the review pointer second, so no hold pointer
        // exists without the record that says what it is a hold on. A proxied hold is placed while the format that
        // claims the path is by construction installed - it is what the fetch was routed through - so the coordinate
        // is resolved now and every later path-keyed question (which kinds hold this, may this name be disclosed,
        // what does discarding it destroy) answers from the record once that module is gone.
        HeldSubjects.record(store, path);
        publication.link("/quarantine" + path, publication.storeBlob(body));
    }

    /** Inspect a bounded body and reach a verdict - the decision half of screening, without any store write, so the
     *  streaming {@code download} path can act on the verdict (stream through, quarantine, or reject) itself. */
    private Screening assess(String path, byte[] body, Instant lastModified, boolean truncated) throws IOException {
        List<ComplianceGate.Subject> subjects;
        try {
            subjects = inspect(path, body);
        } catch (MalformedArtifactException malformed) {
            // A proxied artifact an inspector claimed but could not parse (a corrupt .nupkg/.gem/.rpm/.deb pulled from
            // upstream) must never 500 the fetch nor be served silently (§5, §9, §10). Screen it from its
            // path-derived coordinate against the operator deny-list (so a deny-listed coordinate delivered as a corrupt
            // body is still withheld), with the immaturity hold on top, and log the failure - never a silent serve.
            // (The hardened proxy leg is stricter: it REFUSES an unparseable body rather than falling back - see
            // HardenedScreen, which inspects the body itself and lets this exception surface.)
            LOGGER.warn("Could not parse proxied artifact " + path
                    + "; screening it from its path coordinate rather than serving it unscreened", malformed);
            return assessUnclaimed(path, lastModified);
        }
        // On the byte[] tier every claiming inspector is handed the same bounded prefix and can read nothing else, so
        // the caller's one-byte lookahead past that prefix IS each inspector's own completeness - there is no report
        // to collect, and none to lose.
        return assessSubjects(path, new QualityInspector.Inspection(subjects, !truncated), lastModified);
    }

    /**
     * Reach the screening decision over an already-run {@link QualityInspector.Inspection} - the gate assessment plus
     * the incomplete-screen fallback and the immaturity hold, with no store write. Split out of {@link #assess} so the
     * hardened proxy leg ({@link HardenedScreen}) can inspect a spooled body itself - refusing an unparseable one
     * rather than falling back - and then reuse this identical policy decision rather than copying it.
     *
     * <p><b>Completeness is the inspection's, not the caller's guess.</b> The decision below turns on whether
     * the inspectors saw enough to stand behind an empty answer, and that is carried by
     * {@link QualityInspector.Inspection#complete()}: on the {@code byte[]} legs the caller builds it from its own
     * one-byte lookahead past the prefix (which is exactly what every bridged inspector saw), and on the fully-spooled
     * leg it is the AND of what each claiming inspector reported about its own read. The two are not the same claim:
     * the body's length says whether a <em>bridged</em> inspector could have seen the whole artifact, while a full-body
     * scanner's own budget, entry, finding and nesting ceilings can stop it over a body of any length - and a screen
     * that inferred completeness from the merged subject list being empty lost that report entirely the moment another
     * inspector produced a subject.
     */
    Screening assessSubjects(String path, QualityInspector.Inspection inspection, Instant lastModified) {
        List<ComplianceGate.Subject> subjects = inspection.subjects();
        if (InspectionMerge.noPackageSubject(subjects)) {
            // No package subject came back, so no parsed coordinate ever reached the gate, and the path's own
            // coordinate is screened instead. Whether an inspector CLAIMED the path is no part of that: a claim is a
            // declaration of interest, not an assessment, and an inspector that claims an artifact and derives
            // nothing has screened exactly as much as one that never looked - the publish edge admitted an advised
            // Ivy jar for precisely that reading. When a bound stopped the read the hole is wider still - a padded
            // archive with trailing metadata would proxy through un-screened, with nothing logged - and there the
            // fallback subject is APPENDED to the content findings rather than assessed beside them.
            if (inspection.complete()) {
                if (subjects.isEmpty()) {
                    return assessUnclaimed(path, lastModified);
                }
                // Content findings and no package subject - a bundle fetched beside a file nothing parsed as a
                // package. The file is still screened from its path, and what was found beside it is assessed with
                // it; the stronger verdict decides and both sets of reasons are recorded.
                Screening beside = assessUnclaimed(path, lastModified);
                ComplianceGate.Assessment scanned = gate.assess(subjects);
                List<String> reasons = new ArrayList<>(beside.reasons());
                for (ComplianceGate.Finding finding : scanned.findings()) {
                    reasons.add(finding.detail());
                }
                Verdict verdict = scanned.verdict().compareTo(beside.verdict()) > 0
                        ? scanned.verdict()
                        : beside.verdict();
                return new Screening(verdict, beside.coordinate(), reasons, true);
            } else {
                // A bound stopped some inspector's read and nothing licensable came back. The fallback subject is
                // APPENDED rather than substituted, so a content finding made over the truncated head is still
                // assessed beside it - substituting would drop a detected secret in order to add a coordinate.
                List<ComplianceGate.Subject> withFallback = new ArrayList<>(subjects);
                withFallback.add(pathDerivedSubject(path));
                subjects = InspectionMerge.order(withFallback);
            }
        }
        ComplianceGate.Assessment assessment = gate.assess(subjects);
        Verdict verdict = assessment.verdict();
        List<String> reasons = new ArrayList<>();
        for (ComplianceGate.Finding finding : assessment.findings()) {
            reasons.add(finding.detail());
        }
        if (verdict == Verdict.ALLOW && immature(lastModified)) {
            verdict = Verdict.QUARANTINE;
            reasons.add("Immature: upstream published within the " + holdDays + "-day hold");
        }
        if (!inspection.complete()) {
            // The screen reached a decision over less than the whole artifact. It is recorded on the outcome either
            // way, so a withholding names it among its reasons and an ALLOW is counted and logged rather than filed as
            // a clean whole-body screen (§9: a fact that would change what a reviewer concludes is never swallowed).
            // It deliberately does not change the verdict: an inspector whose bound was reached may only under-declare
            // (the CONTENT_FINDINGS reading), so withholding every artifact a scanner could not finish would hold the
            // repository closed on ordinary large ones.
            reasons.add(INCOMPLETE_SCREEN_REASON);
            if (verdict == Verdict.ALLOW) {
                if (withholdIncomplete) {
                    // The strict posture: an ALLOW the inspectors could not stand behind over the whole body
                    // is held for review rather than served with a note. Deliberately a QUARANTINE and not a REJECT -
                    // nothing was found wrong, only unread, so this is a decision waiting on a human rather than a
                    // verdict against the artifact, and a reviewer can release it.
                    verdict = Verdict.QUARANTINE;
                    LOGGER.warn("Withholding " + path + " on an INCOMPLETE screen: an inspector's read stopped at a "
                            + "bound before the whole artifact was screened, and withhold-incomplete-screens is on");
                } else {
                    INCOMPLETE_SCREENS.incrementAndGet();
                    LOGGER.warn("Serving " + path + " on an INCOMPLETE screen: an inspector's read stopped at a bound "
                            + "before the whole artifact was screened, so this ALLOW covers only the part that was "
                            + "read");
                }
            }
        }
        return new Screening(verdict, coordinate(subjects), reasons, inspection.complete());
    }

    /** Record a non-{@code ALLOW} verdict in the durable {@link QuarantineLog}; a clean artifact records nothing.
     *  Shared with the hardened proxy leg, which records both a policy withholding and a structural refusal here. */
    void log(String path, Screening screening) throws IOException {
        if (screening.verdict() != Verdict.ALLOW) {
            new QuarantineLog(store).record(
                    Instant.now(), path, screening.coordinate(), screening.verdict(), screening.reasons());
        }
    }

    /** A screening outcome: the verdict, the coordinate the log names, the reasons behind a withholding, and whether
     *  the decision was reached over the WHOLE artifact or over as much of it as a bound allowed. Reused by the
     *  hardened proxy leg to carry its own decision and its named structural refusals. */
    record Screening(Verdict verdict, String coordinate, List<String> reasons, boolean complete) {

        /** A screening reached over the whole artifact - a structural refusal or a drift alarm, which are decisions
         *  about the fetch rather than about how far an inspector read. */
        Screening(Verdict verdict, String coordinate, List<String> reasons) {
            this(verdict, coordinate, reasons, true);
        }
    }

    private boolean immature(Instant lastModified) {
        return holdDays > 0 && lastModified != null
                && lastModified.isAfter(Instant.now().minus(Duration.ofDays(holdDays)));
    }

    /** Whether NO installed inspector claims this path - the UNCLAIMED case: raw-format or un-inspected content pulled
     *  through the proxy, which must still be screened against the operator deny-list from a path-derived coordinate
     *  rather than served unscreened. Mirrors the claim test {@link #inspect} runs. */
    private boolean unclaimed(String path) {
        // claims, not handles: an inspector that only reads what sits beside an artifact handles a raw path without
        // claiming it unless something is there beside it - a companion the fill fetched, a sidecar in the store -
        // and the deny-list screening of unclaimed content must not switch off because it does.
        return INSPECTORS.stream().noneMatch(inspector -> inspector.claims(path, siblings));
    }

    /** Screen UNCLAIMED proxied content against the operator deny-list (and the harmless coordinate/feed dimensions)
     *  from a path-derived subject, WITHOUT reading the body - {@link ComplianceGate#assessUnclaimed} deliberately skips
     *  the license/discovered dimensions, so an ordinary raw fetch is NOT over-quarantined as unknown-license while a
     *  deny-listed coordinate delivered as raw content is still withheld. The immaturity hold still applies on top,
     *  exactly as it does for a claimed subject. */
    private Screening assessUnclaimed(String path, Instant lastModified) {
        ComplianceGate.Assessment assessment = gate.assessUnclaimed(pathDerivedSubject(path));
        Verdict verdict = assessment.verdict();
        List<String> reasons = new ArrayList<>();
        for (ComplianceGate.Finding finding : assessment.findings()) {
            reasons.add(finding.detail());
        }
        if (verdict == Verdict.ALLOW && immature(lastModified)) {
            verdict = Verdict.QUARANTINE;
            reasons.add("Immature: upstream published within the " + holdDays + "-day hold");
        }
        return new Screening(verdict, coordinate(List.of(pathDerivedSubject(path))), reasons);
    }

    /** The stand-in subject for an artifact screened from its path alone rather than from parsed content on the proxy
     *  leg: an inspector claimed it but could not read it (a truncated archive, or a body that is not the archive the
     *  path names), OR no inspector claimed it at all (raw / un-inspected content). The coordinate is what the owning
     *  format's layout reads off the path - the same {@code describe} the publish edge's fallback and the console use -
     *  so the deny-list and the advisory feeds key on the real coordinate: a known-malicious package whose upstream
     *  body is corrupt is still withheld by name, never waved through as a filename nobody advises. The filename
     *  stands in only where no installed layout maps the path. It declares no license: the truncated-claimed path
     *  runs the full {@link ComplianceGate#assess} so the unknown-license dimension holds it, while the unclaimed path
     *  runs {@link ComplianceGate#assessUnclaimed}, which skips that dimension - a raw fetch is screened for the
     *  deny-list without being over-quarantined as unknown-license. */
    private ComplianceGate.Subject pathDerivedSubject(String path) {
        Optional<ArtifactDescriptor> described = new StoreRepositoryInventory(store).describe(path);
        if (described.isEmpty() || described.get().coordinate() == null) {
            return new ComplianceGate.Subject("", fileName(path), "", List.of());
        }
        ArtifactDescriptor artifact = described.get();
        return new ComplianceGate.Subject(artifact.ecosystem() == null ? "" : artifact.ecosystem(),
                artifact.coordinate(), artifact.version() == null ? "" : artifact.version(), List.of());
    }

    /** The last path segment - the filename a truncated-artifact fallback subject is coordinated by, and the
     *  coordinate a hardened-leg refusal is recorded under. */
    static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String coordinate(List<ComplianceGate.Subject> subjects) {
        ComplianceGate.Subject subject = subjects.getFirst();
        return subject.coordinate() + ":" + subject.version();
    }

    /** The inspectors that claim (and so run over) an artifact at this path - the validators the hardened leg names in
     *  its digest-pinned verdict record. A {@link QualityInspector} carries no version today, so each validator
     *  is named by its inspector class; a validator that later supplies a version records it through
     *  {@link VerdictSection.Validator}. */
    List<VerdictSection.Validator> validators(String path) {
        return INSPECTORS.stream()
                .filter(inspector -> inspector.handles(path))
                .map(inspector -> VerdictSection.Validator.of(inspector.getClass().getSimpleName()))
                .toList();
    }

    /** This inspector with the deployment's signer trust bound to the store this screen is scoped to, or unchanged
     *  when it verifies nothing or no trust module is installed. */
    private QualityInspector trusting(QualityInspector inspector) {
        return inspector instanceof TrustAware aware && TRUST_INSTALLED
                ? aware.withTrust(SignerTrustProvider.trust(ComplianceSettings.lookup(), store))
                : inspector;
    }

    List<ComplianceGate.Subject> inspect(String path, byte[] body) throws IOException {
        // Every inspector that claims the path screens the fetched body, not just the first to match, so the content
        // scanner (the embedded-secret dimension) composes with the format inspector; InspectionMerge keeps the
        // package subject ahead of any content-scan subject so the quarantine log names the coordinate.
        List<ComplianceGate.Subject> subjects = new ArrayList<>();
        for (QualityInspector claimed : INSPECTORS) {
            if (claimed.handles(path)) {
                QualityInspector inspector = trusting(claimed);
                subjects.addAll(attributed(inspector, path, () -> inspector.inspectArtifact(path, body, siblings)));
            }
        }
        return InspectionMerge.order(subjects);
    }

    /**
     * Inspect an artifact from its FULLY-SPOOLED body (the hardened proxy leg's full-body tier): every claiming
     * inspector screens the whole body via {@link QualityInspector#inspectArtifact(String, QualityInspector.Content,
     * QualityInspector.Lookup)} - a full-body-tier inspector (the secret content scanner) reading past the bounded
     * prefix so a secret sitting beyond the 32 MiB window is still caught, a format inspector default-bridged to the
     * same front prefix it read before. Mirrors {@link #inspect(String, byte[])} but over a re-openable spool handle
     * rather than a heap {@code byte[]}; a {@link MalformedArtifactException} still surfaces for the hardened leg to
     * refuse (fail-closed), exactly as on the {@code byte[]} path.
     *
     * <p><b>Every claiming inspector takes this leg, whatever {@link QualityInspector#streams()} says, and that is
     * the one place the two screens answer differently on purpose.</b> The publish screen asks that question,
     * because it holds a bounded {@code byte[]} as well as a handle on the stored blob and an inspector that reads
     * no further than the prefix would only be given a different method to read the same bytes through. Here there
     * is no array: the body was spooled precisely so it could be read whole, the SPI's bridge is the designed path
     * down to the prefix for an inspector that wants no more, and there is nothing else to hand one. So the two
     * screens differ in what they have rather than in what they believe.
     *
     * <p>The merged answer is complete only if EVERY claiming inspector's own read was. One inspector's
     * bound-stopped read is not made good by another's subject: they are looking for different things, and the screen
     * has to know that the artifact was screened whole rather than that somebody found something in it. Which
     * inspectors stopped is named in the log line, because "the screen was partial" is a fact an operator can only act
     * on once they know which dimension went blind.
     */
    QualityInspector.Inspection inspectFullBody(String path, QualityInspector.Content body) throws IOException {
        List<ComplianceGate.Subject> subjects = new ArrayList<>();
        List<String> boundStopped = new ArrayList<>();
        for (QualityInspector claimed : INSPECTORS) {
            if (claimed.handles(path)) {
                QualityInspector inspector = trusting(claimed);
                QualityInspector.Inspection inspection = attributed(inspector, path,
                        () -> inspector.inspectArtifact(path, body, siblings));
                subjects.addAll(inspection.subjects());
                if (!inspection.complete()) {
                    boundStopped.add(inspector.getClass().getSimpleName());
                }
            }
        }
        if (!boundStopped.isEmpty()) {
            LOGGER.warn("Incomplete full-body screen of " + path + ": " + String.join(", ", boundStopped)
                    + " stopped at a bound before reading the whole artifact, so what lies past it was not screened");
        }
        return new QualityInspector.Inspection(InspectionMerge.order(subjects), boundStopped.isEmpty());
    }

    /** What an inspector call may raise, so the two legs below can share one attribution. */
    @FunctionalInterface
    private interface Inspecting<T> {
        T run() throws IOException;
    }

    /**
     * Run one inspector and name it in whatever it raises.
     *
     * <p>The publish leg puts all three of {@code QualityInspector}'s legal failure shapes on one fail-closed leg -
     * held, recorded, attributed. This leg caught nothing per inspector and its caller catches only
     * {@link MalformedArtifactException}, so an inspector's {@code RuntimeException} or plain {@code IOException}
     * left the fetch as a raw error with no quarantine row and no attribution, while the same inspector over the
     * same bytes on the publish leg was held with a legible reason. Which of the two an operator got depended on
     * how the artifact arrived.
     *
     * <p>All three shapes now leave here as a {@code MalformedArtifactException} naming the inspector, so the
     * caller's existing fail-closed path records and refuses identically whichever one occurred. The distinction
     * the publish leg draws between "could not parse" and "threw" is preserved in the message rather than in the
     * type, because on this leg both mean the same thing to the caller: these bytes were not screened, so they do
     * not serve. A deployment installs seventeen inspectors, so "an inspector threw" names none of them.
     */
    private static <T> T attributed(QualityInspector inspector, String path, Inspecting<T> call)
            throws MalformedArtifactException {
        String identity = inspector.getClass().getName();
        try {
            return call.run();
        } catch (MalformedArtifactException malformed) {
            throw new MalformedArtifactException(identity + " could not parse " + path + ": "
                    + malformed.getMessage(), malformed);
        } catch (IOException | RuntimeException failure) {
            throw new MalformedArtifactException(identity + " threw inspecting " + path + ": "
                    + failure, failure);
        }
    }

    /** The already-published-sibling lookup this screen hands its inspectors - both reads capped at the source.
     *  Exposed so the streaming/OOM guard test can drive the bounded read directly without a registered inspector. */
    public QualityInspector.Lookup siblingLookup() {
        return siblings;
    }

    /** The already-published-sibling lookup handed to every inspector. It caps both reads at the source so a tiny
     *  attestation referrer beside a multi-gigabyte artifact can never pull the whole companion into a heap
     *  {@code byte[]} on the gateway thread: {@link #fetch} reads at most {@link #SIBLING_LIMIT} and fails loud past it
     *  (a companion an inspector reads whole is a small metadata document), and {@link #fetchBounded} streams at most
     *  the caller's own {@code limit}, which is not subject to that ceiling - the OOM, and the leg divergence, the
     *  {@code AttestationInspector}'s bounded call is written to avoid. Both legs are implemented against the store;
     *  neither is derived from the other. */
    private final class SiblingLookup implements QualityInspector.Lookup {

        @Override
        public Optional<byte[]> fetch(String path) throws IOException {
            byte[] companion = companions.get(path);
            if (companion != null) {
                return Optional.of(companion);
            }
            Optional<String> key = publication.located(path);
            if (key.isEmpty()) {
                return Optional.empty();
            }
            try (InputStream in = store.open(key.get())) {
                byte[] bytes = in.readNBytes(SIBLING_LIMIT + 1);
                if (bytes.length > SIBLING_LIMIT) {
                    throw new IOException("Published sibling exceeds the " + SIBLING_LIMIT
                            + "-byte inspection cap, refusing to buffer it whole: " + path);
                }
                return Optional.of(bytes);
            }
        }

        @Override
        public Optional<Bounded> fetchBounded(String path, int limit) throws IOException {
            byte[] companion = companions.get(path);
            if (companion != null) {
                return Optional.of(companion.length > limit
                        ? new Bounded(Arrays.copyOf(companion, limit), true)
                        : new Bounded(companion, false));
            }
            Optional<String> key = publication.located(path);
            if (key.isEmpty()) {
                return Optional.empty();
            }
            try (InputStream in = store.open(key.get())) {
                // One byte past the limit, so a sibling of EXACTLY limit bytes is reported whole rather than
                // pessimistically flagged: the caller holds every byte of it, and a digest over it really is the
                // companion's digest. The boundary is `>`, never `>=`.
                byte[] prefix = in.readNBytes(limit + 1);
                boolean truncated = prefix.length > limit;
                return Optional.of(new Bounded(truncated ? Arrays.copyOf(prefix, limit) : prefix, truncated));
            }
        }
    }
}
