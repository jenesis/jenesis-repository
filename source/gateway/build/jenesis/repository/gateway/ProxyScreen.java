package build.jenesis.repository.gateway;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.compliance.AdvisorySource;
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
import build.jenesis.repository.compliance.ScreeningMode;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.format.FormatExchange;
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
     *  they read while a pathologically large jar is never pulled whole into a {@code byte[]} to gate it on
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
     *  <p>It <em>is</em> the sibling cap rather than a copy of the same number, so the publish leg (which reads
     *  through {@link build.jenesis.repository.store.PublishInterceptor.Content#sibling(String)}, capped there) and
     *  this proxy leg cannot drift on what "too large to read whole" means. */
    static final int SIBLING_LIMIT = PublishInterceptor.Content.LARGEST_SIBLING;

    /** The reason line an incompletely screened artifact carries - one constant, so the durable {@link QuarantineLog}
     *  row, the log line and any read surface name the same thing rather than three near-identical sentences. */
    static final String INCOMPLETE_SCREEN_REASON = "Incompletely screened: an inspector's read stopped at a bound "
            + "before the whole artifact was screened, so this decision covers only the part that was read";

    /** Screens that reached an {@code ALLOW} over an artifact an inspector could not read to the end, since the
     *  gateway started - the {@code jenrepo.gateway.screen.incomplete} counter. Static so every per-request screen
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

    /** The response header a fill served while a feed could not answer carries, valued {@value #SCREEN_PENDING}: the
     *  client is told the copy it was handed has not been asked of every feed yet. */
    public static final String SCREEN_HEADER = "Jenesis-Screen";

    /** The {@link #SCREEN_HEADER} value of a fill served while a feed could not answer. */
    public static final String SCREEN_PENDING = "pending";

    private final ComplianceGate gate;
    private final ArtifactStore store;
    private final Publication publication;
    private final int holdDays;

    /** What this screen does with what it could not find out and with what it found. */
    private final ScreeningMode mode;

    /** Told when a copy is served while a feed could not answer - the request's response, where there is one. */
    private final Runnable pendingNotice;

    /** Whether to withhold on an incomplete screen rather than serve with the fact recorded. */
    private final boolean withholdIncomplete;
    private final QualityInspector.Lookup siblings = new SiblingLookup(null);

    /** What the pull-through fetched beside the artifact being screened, by the path it is kept at; empty on every
     *  leg that fetched none. Read by {@link SiblingLookup} ahead of the store. */
    private Map<String, byte[]> companions = Map.of();

    /** The reviewed-fail-open form: an incomplete screen serves and says so. Every caller that has no opinion gets
     *  this, which is the reviewed default rather than an oversight. */
    public ProxyScreen(ComplianceGate gate, ArtifactStore store, int holdDays) {
        this(gate, store, holdDays, false);
    }

    public ProxyScreen(ComplianceGate gate, ArtifactStore store, int holdDays, boolean withholdIncomplete) {
        this(gate, store, holdDays, withholdIncomplete, ScreeningMode.of(null), () -> {
        });
    }

    private ProxyScreen(ComplianceGate gate, ArtifactStore store, int holdDays, boolean withholdIncomplete,
                        ScreeningMode mode, Runnable pendingNotice) {
        // The feeds the repository selects among the deployment's, read through the effective settings the request,
        // or the pass, binds for it.
        this.gate = gate == null ? null : gate.screening(store);
        this.store = store;
        this.publication = new Publication(store);
        this.holdDays = holdDays;
        this.withholdIncomplete = withholdIncomplete;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.pendingNotice = Objects.requireNonNull(pendingNotice, "pendingNotice");
    }

    /** This screen deciding under {@code mode}, the screened repository's {@link ScreeningMode}. */
    public ProxyScreen screening(ScreeningMode mode) {
        return new ProxyScreen(gate, store, holdDays, withholdIncomplete, mode, pendingNotice);
    }

    /** This screen telling {@code exchange}'s response when it serves a copy a feed could not answer for, through
     *  {@link #SCREEN_HEADER} - set before the fill's response commits, so it reaches the client with the copy. */
    public ProxyScreen noticing(FormatExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        return new ProxyScreen(gate, store, holdDays, withholdIncomplete, mode,
                () -> exchange.setResponseHeader(SCREEN_HEADER, SCREEN_PENDING));
    }

    /** Wrap an upstream fetcher to screen its fetched artifact: a non-{@link Verdict#ALLOW} verdict becomes an empty
     *  result, so the pull-through treats it as a miss and neither caches nor serves it. The
     *  {@link ProxyFormat.Fetcher#download streaming download} is overridden too: an artifact no inspector claims has
     *  nothing to screen, so it streams straight from upstream to the store. A claimed artifact IS screened, but only
     *  over a bounded {@link #inspectionLimit()} prefix - the firewall reads the front of the stream, reaches its
     *  verdict, and then streams the un-buffered remainder straight to the client/store on {@code ALLOW}, so a large
     *  claimed proxy artifact is never materialised whole in heap (which routing a claimed download through the
     *  buffered {@link ProxyFormat.Fetcher#fetch fetch} would do). A withheld artifact is streamed into
     *  {@code /quarantine} for review and recorded in the durable log.
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
     *  <p>Nothing is quarantined or logged on this leg: the durable record accompanies a withheld <em>body</em> there
     *  is a copy of to review, and a bodiless probe withholds none - while recording per {@code HEAD} would let a
     *  client's probe loop write the durable log without ever fetching an artifact. */
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
                        && screen(path, fetched.get().body(), lastModified(fetched.get().header("last-modified")),
                                url.toString())
                                != Verdict.ALLOW) {
                    return Optional.empty();
                }
                return fetched;
            }

            /** Unscreened: a document beside the artifact is not the artifact this screen judges (see
             *  {@link ProxyFormat.Fetcher#beside}). */
            @Override
            public ProxyFormat.Fetcher beside() {
                return delegate.beside();
            }

            @Override
            public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) throws IOException {
                // Delegated to the transport's real HTTP HEAD, never derived: a derivation would open (and this screen
                // would then READ) an artifact body to answer a question about metadata, screening a prefix and even
                // quarantining/logging from a request that asked for no bytes (a HEAD answers from metadata). A
                // screen is a decorator, so it is never a Fetcher.Buffered.
                Optional<ProxyFormat.Head> head = delegate.head(url, headers);
                if (head.isEmpty() || head.get().status() != 200) {
                    return head;   // a transport failure or an upstream miss - nothing exists to screen or disclose
                }
                // The body-free half of the screen still applies: no content means no inspector subject, but the
                // coordinate and Last-Modified are right here, so the operator deny-list and the immaturity hold are
                // assessed exactly as the UNCLAIMED download leg assesses them without reading a body. A withheld
                // metadata answer is the same empty miss the other two legs return; a denied coordinate must not be
                // answerable as "exists, N bytes" just because the client asked without a body.
                Screening screening = withVersion(path,
                        assessUnclaimed(path, released(path, head.get().header("last-modified"))));
                return screening.verdict() == Verdict.ALLOW ? head : Optional.empty();
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) throws IOException {
                if (unclaimed(path)) {
                    // UNCLAIMED: no inspector can turn the body into a subject, but the operator deny-list must still
                    // bite a raw/un-inspected coordinate rather than streaming it through unscreened - the exact path
                    // an attacker uses to bypass "deny com.evil:*". Screen from a path-derived subject WITHOUT reading
                    // the body (the deny-list needs only the coordinate), and stream the un-buffered body through only
                    // on ALLOW; a withheld one is stored/discarded and logged exactly as a claimed withholding is.
                    Optional<ProxyFormat.Download> pulled = delegate.download(url, headers);
                    if (pulled.isEmpty() || pulled.get().status() != 200) {
                        return pulled;   // an upstream miss - nothing to screen or withhold
                    }
                    ProxyFormat.Download rawResponse = pulled.get();
                    Screening screening = unversioned(path, withVersion(path,
                            assessUnclaimed(path, released(path, rawResponse.header("last-modified")))));
                    if (screening.verdict() == Verdict.ALLOW) {
                        log(path, screening);
                        return pulled;
                    }
                    try (rawResponse) {
                        if (screening.verdict() == Verdict.QUARANTINE) {
                            quarantine(path, rawResponse.body(), url.toString());
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
                Screening screening = unversioned(path, withVersion(path,
                        assess(path, prefix, released(path, response.header("last-modified")), truncated, url)));
                if (screening.verdict() == Verdict.ALLOW) {
                    log(path, screening);
                    return Optional.of(new ProxyFormat.Download(response.status(),
                            new SequenceInputStream(new ByteArrayInputStream(prefix), continued), response.headers()));
                }
                // Withheld: the pull-through sees an empty result and treats it as a miss. A held artifact is streamed
                // whole into the /quarantine store for review (still un-buffered), a rejected one is discarded; either
                // way the durable QuarantineLog row is written before the upstream body is closed.
                try (response) {
                    if (screening.verdict() == Verdict.QUARANTINE) {
                        quarantine(path, new SequenceInputStream(new ByteArrayInputStream(prefix), continued),
                                url.toString());
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

    Verdict screen(String path, byte[] body, Instant lastModified, String upstream) throws IOException {
        // The buffered fetch body is the COMPLETE document, never a bounded prefix, so it is never truncated.
        Screening screening = unversioned(path, withVersion(path, assess(path, body,
                versioned(path) ? lastModified : null, false, upstream == null ? null : URI.create(upstream))));
        if (screening.verdict() == Verdict.QUARANTINE) {
            quarantine(path, new ByteArrayInputStream(body), upstream);
        }
        log(path, screening);
        return screening.verdict();
    }

    /**
     * Whether {@code path} names a release rather than a document about a package. A document naming no version - a
     * packument, a {@code maven-metadata.xml}, a project page, a compact index's {@code versions} - is about a package:
     * its dates move with every release, so the immaturity hold does not read them, and a withholding refuses it rather
     * than keeping a copy for review, since a copy released later would serve a document the upstream has moved past
     * and no version would have been reviewed. Every leg of the screen applies both, the streaming and the
     * body-free ones as the buffered one does.
     */
    private boolean versioned(String path) {
        return !pathDerivedSubject(path).version().isEmpty();
    }

    /** The upstream's {@code Last-Modified} for a path that names a release, and nothing for a document naming no
     *  version, which the immaturity hold does not read. */
    private Instant released(String path, String lastModified) {
        return versioned(path) ? lastModified(lastModified) : null;
    }

    /** {@code screening} with a hold for review on a document naming no version made a refusal, saying why. */
    private Screening unversioned(String path, Screening screening) {
        if (screening.verdict() != Verdict.QUARANTINE || versioned(path)) {
            return screening;
        }
        return screening.adding(UNVERSIONED_REASON).judged(Verdict.REJECT, Verdict.REJECT, false, false);
    }

    /**
     * {@code screening} with a file whose version has another file held for review held with it: a version is reviewed
     * whole, as the publish chain holds a file arriving for a held version, and a sibling fetched after the hold - a
     * sources jar, another wheel, another platform's archive - would otherwise serve beside files a reviewer has not
     * cleared. Held whatever the repository's screening mode, since the version itself is. A refusal stays a refusal,
     * and a path no installed layout maps to a version is judged alone. One bounded listing of the version's held
     * paths, on a fill only.
     */
    private Screening withVersion(String path, Screening screening) throws IOException {
        if (screening.verdict() != Verdict.ALLOW) {
            return screening;
        }
        ComplianceGate.Subject subject = pathDerivedSubject(path);
        if (subject.ecosystem().isEmpty() || subject.version().isEmpty() || !HeldSubjects.heldBesides(store,
                subject.ecosystem(), subject.coordinate(), subject.version(), path)) {
            return screening;
        }
        return screening.adding(subject.coordinate() + ":" + subject.version()
                        + " is held for review, so a file fetched for it is held with it")
                .ruled(ComplianceGate.VERSION_HELD_RULE)
                .judged(Verdict.QUARANTINE, Verdict.QUARANTINE, false, screening.pending());
    }

    /** Why a withheld document naming no version was refused rather than held for review. */
    public static final String UNVERSIONED_REASON = "Refused rather than held for review: the document names no version, so "
            + "there is no release to review and a held copy would go stale";

    /** Store a withheld body fetched from {@code upstream} under {@code /quarantine} for review - the durable hold the
     *  {@link QuarantineLog} row points at. Shared by the buffered/streaming screen and the hardened proxy leg
     *  ({@link HardenedScreen}), which passes {@code null} when it re-screens bytes it already held rather than a
     *  fetch. */
    void quarantine(String path, InputStream body, String upstream) throws IOException {
        // The durable path -> coordinate record goes first and the review pointer second, so no hold pointer
        // exists without the record that says what it is a hold on. A proxied hold is placed while the format that
        // claims the path is by construction installed - it is what the fetch was routed through - so the coordinate
        // is resolved now and every later path-keyed question (which kinds hold this, may this name be disclosed,
        // what does discarding it destroy) answers from the record once that module is gone.
        HeldSubjects.recordFetched(store, path, upstream);
        publication.link("/quarantine" + path, publication.storeBlob(body));
    }

    /** Inspect a bounded body and reach a verdict - the decision half of screening, without any store write, so the
     *  streaming {@code download} path can act on the verdict (stream through, quarantine, or reject) itself. */
    private Screening assess(String path, byte[] body, Instant lastModified, boolean truncated, URI origin)
            throws IOException {
        List<ComplianceGate.Subject> subjects;
        try {
            subjects = inspect(path, body, origin);
        } catch (MalformedArtifactException malformed) {
            // A proxied artifact an inspector claimed but could not parse (a corrupt .nupkg/.gem/.rpm/.deb pulled from
            // upstream) must never 500 the fetch nor be served silently. Screen it from its
            // path-derived coordinate against the operator deny-list (so a deny-listed coordinate delivered as a
            // corrupt body is still withheld), with the immaturity hold on top, and log the failure - never a silent
            // serve. (The hardened proxy leg is stricter: it REFUSES an unparseable body rather than falling back - see
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
     * that inferred completeness from the merged subject list being empty would lose that report the moment another
     * inspector produced a subject.
     */
    Screening assessSubjects(String path, QualityInspector.Inspection inspection, Instant lastModified) {
        Screening screening;
        try {
            screening = decide(gate, path, inspection, lastModified);
        } catch (RuntimeException failure) {
            screening = outage(path, inspection.subjects(), failure,
                    () -> decide(gate.advisories(AdvisorySource.none()), path, inspection, lastModified));
        }
        Screening decided = governed(path, screening);
        if (decided.verdict() != Verdict.REJECT) {
            recordAdvised(inspection.subjects());
        }
        return decided;
    }

    /**
     * Record, for a copy the screen keeps, the name each subject's inspector read the advisory databases know it by
     * ({@link ComplianceGate.Subject#advised}), so every later screen of the copy - the scan pass, a rescan - asks the
     * feeds what this one asked. One compare-and-set of the version's document where it records another name or none,
     * none where it records this one. A refused write fails the fill rather than leaving a copy every later screen
     * would ask about under a name the databases do not publish, which reads clean.
     */
    private void recordAdvised(List<ComplianceGate.Subject> subjects) {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        for (ComplianceGate.Subject subject : subjects) {
            if (subject.advised() == null || subject.contentScan() || subject.version() == null
                    || subject.version().isBlank()) {
                continue;
            }
            // Under the layout's spelling of the coordinate, which every read of the version's document resolves to.
            StoreRepositoryInventory.Coordinate copy = inventory.canonical(subject.ecosystem(), subject.coordinate(),
                    subject.version()).orElse(new StoreRepositoryInventory.Coordinate(subject.ecosystem(),
                    subject.coordinate(), subject.version()));
            try {
                inventory.advised(copy.ecosystem(), copy.coordinate(), copy.version(), subject.advised(),
                        Instant.now());
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not record the name the advisory databases know "
                        + subject.coordinate() + " " + subject.version() + " by", failure);
            }
        }
    }

    /**
     * What a feed that could not answer leaves the copy to: held under {@link ScreeningMode#HOLD}; otherwise decided
     * again by every dimension but the feed - so the deny-list, the licence and the immaturity hold still bite - and
     * served if they allow it, the outage among its reasons. A re-decision that fails too has nothing left to decide
     * by, and holds.
     */
    private Screening outage(String path, List<ComplianceGate.Subject> subjects, RuntimeException failure,
                             Supplier<Screening> withoutFeed) {
        if (!mode.admitsOutage()) {
            return feedFailed(path, subjects, failure);
        }
        Screening decided;
        try {
            decided = withoutFeed.get();
        } catch (RuntimeException again) {
            failure.addSuppressed(again);
            return feedFailed(path, subjects, failure);
        }
        LOGGER.warn("Screening the proxied " + path + " without the advisory feeds, one of which cannot answer ("
                + ScreeningMode.outage(failure) + "); the repository's screening mode is " + mode, failure);
        return decided.adding(ScreeningMode.admitted(failure)).judged(decided.verdict(), decided.floor(), true, true);
    }

    /** {@link ScreeningMode#RECORD}'s rule over a reached decision: it is lowered to its {@link Screening#floor} -
     *  what the deny-list decided - and what it found is kept among the reasons. Every other mode acts on the decision
     *  as reached. */
    private Screening governed(String path, Screening screening) {
        if (mode != ScreeningMode.RECORD || screening.verdict().compareTo(screening.floor()) <= 0) {
            return screening;
        }
        LOGGER.warn("Serving the proxied " + path + " the screen would have answered " + screening.verdict()
                + ": the repository's screening mode is RECORD - " + String.join("; ", screening.reasons()));
        return screening.adding(ScreeningMode.RECORDED_REASON)
                .judged(screening.floor(), screening.floor(), true, screening.pending());
    }

    /**
     * Fail closed when an advisory feed threw mid-assessment - a rate limit, a mirror outage: a real feed raises on a
     * failure rather than answering an empty, clean list, and the gate assesses through it. The copy is held for review
     * with a reason that names the outage, as a publish whose screen could not complete is, rather than served
     * unscreened or answered with an error that records nothing. The coordinate is the inspected one where the body
     * parsed, the path's otherwise.
     */
    private Screening feedFailed(String path, List<ComplianceGate.Subject> subjects, RuntimeException failure) {
        String message = ScreeningMode.outage(failure);
        LOGGER.warn("Could not fully screen the proxied " + path + "; an advisory feed failed closed - holding the copy "
                + "in quarantine rather than serving unscreened bytes or answering an error", failure);
        String coordinate = coordinate(subjects.isEmpty() ? List.of(pathDerivedSubject(path)) : subjects);
        return new Screening(Verdict.QUARANTINE, coordinate,
                List.of("Could not fully screen the artifact " + path + " - " + ComplianceGate.FEED_FAILED_CLOSED
                        + ": " + message),
                List.of(ComplianceGate.FEED_UNAVAILABLE_RULE), true);
    }

    /** {@link #assessSubjects}'s decision through {@code gate}, which raises when an advisory feed does. */
    private Screening decide(ComplianceGate gate, String path, QualityInspector.Inspection inspection,
                             Instant lastModified) {
        List<ComplianceGate.Subject> subjects = inspection.subjects();
        if (InspectionMerge.noPackageSubject(subjects)) {
            // No package subject came back, so no parsed coordinate ever reached the gate, and the path's own
            // coordinate is screened instead. Whether an inspector CLAIMED the path is no part of that: a claim is a
            // declaration of interest, not an assessment, and an inspector that claims an artifact and derives
            // nothing has screened exactly as much as one that never looked. When a bound stopped the read the hole is
            // wider still - a padded
            // archive with trailing metadata would proxy through un-screened, with nothing logged - and there the
            // fallback subject is APPENDED to the content findings rather than assessed beside them.
            if (inspection.complete()) {
                if (subjects.isEmpty()) {
                    return decideUnclaimed(gate, path, lastModified);
                }
                // Content findings and no package subject - a bundle fetched beside a file nothing parsed as a
                // package. The file is still screened from its path, and what was found beside it is assessed with
                // it; the stronger verdict decides and both sets of reasons are recorded.
                Screening beside = decideUnclaimed(gate, path, lastModified);
                ComplianceGate.Assessment scanned = gate.assess(subjects);
                List<String> reasons = new ArrayList<>(beside.reasons());
                for (ComplianceGate.Finding finding : scanned.findings()) {
                    reasons.add(finding.detail());
                }
                Verdict verdict = scanned.verdict().compareTo(beside.verdict()) > 0
                        ? scanned.verdict()
                        : beside.verdict();
                List<String> rules = new ArrayList<>(beside.rules());
                scanned.rules().stream().filter(rule -> !rules.contains(rule)).forEach(rules::add);
                Verdict floor = scanned.denied().compareTo(beside.floor()) > 0 ? scanned.denied() : beside.floor();
                return new Screening(verdict, beside.coordinate(), reasons, rules, true, floor, false);
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
        List<String> rules = new ArrayList<>(assessment.rules());
        if (verdict == Verdict.ALLOW && immature(lastModified)) {
            verdict = Verdict.QUARANTINE;
            reasons.add("Immature: upstream published within the " + holdDays + "-day hold");
            rules.add(IMMATURE_RULE);
        }
        if (!inspection.complete()) {
            // The screen reached a decision over less than the whole artifact. It is recorded on the outcome either
            // way, so a withholding names it among its reasons and an ALLOW is counted and logged rather than filed as
            // a clean whole-body screen (a fact that would change what a reviewer concludes is never swallowed).
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
                    rules.add(INCOMPLETE_RULE);
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
        return new Screening(verdict, coordinate(subjects), reasons, rules, inspection.complete(),
                assessment.denied(), false);
    }

    /** Record a non-{@code ALLOW} verdict in the durable {@link QuarantineLog}, and an {@code ALLOW} the repository's
     *  screening mode reached that the screen alone would not have, with what it found; a clean artifact records
     *  nothing.
     *  Shared with the hardened proxy leg, which records both a policy withholding and a structural refusal here. */
    void log(String path, Screening screening) throws IOException {
        if (screening.verdict() != Verdict.ALLOW || screening.governed()) {
            new QuarantineLog(store).record(Instant.now(), path, screening.coordinate(), screening.verdict(),
                    screening.reasons(), screening.rules());
        }
        if (screening.pending() && screening.verdict() == Verdict.ALLOW) {
            // Served without the feed's answer: marked, so the pending re-screen asks again whatever the cadence of
            // the passes that read the feeds. Written before the copy is cached, so no served copy lacks its marker; a
            // marker whose copy never landed is cleared by the pass.
            Properties marker = new Properties();
            marker.setProperty("path", path);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            marker.store(new OutputStreamWriter(body, StandardCharsets.UTF_8), null);
            store.write(ScreeningMode.pendingKey(path), new ByteArrayInputStream(body.toByteArray()));
            pendingNotice.run();
        }
    }

    /** Screen again the copy at {@code path} from its stored bytes, as a fill would have had the feeds answered:
     *  for {@link PendingScreenTask}. Nothing is written - the caller acts on the decision - and a feed still unable
     *  to answer leaves it {@link Screening#pending}. */
    Screening rescreen(String path, InputStream body) throws IOException {
        byte[] prefix = body.readNBytes(inspectionLimit());
        boolean truncated = body.read() != -1;
        return assess(path, prefix, null, truncated, null);
    }

    /** A screening outcome: the verdict, the coordinate the log names, the reasons behind a withholding and the rules
     *  that decided it, and whether the decision was reached over the WHOLE artifact or over as much of it as a bound
     *  allowed. Reused by the hardened proxy leg to carry its own decision and its named structural refusals. The
     *  {@code floor} is the verdict {@link ScreeningMode#RECORD} still enforces - what the deny-list decided - and
     *  {@code governed} says the repository's mode decided where the screen alone would not have, which is recorded
     *  even when it serves, and {@code pending} that it was decided without a feed that could not answer. */
    record Screening(Verdict verdict, String coordinate, List<String> reasons, List<String> rules, boolean complete,
                     Verdict floor, boolean governed, boolean pending) {

        Screening {
            reasons = List.copyOf(reasons);
            rules = List.copyOf(rules);
        }

        /** A screening no {@link ScreeningMode} lowers: its floor is its verdict. */
        Screening(Verdict verdict, String coordinate, List<String> reasons, List<String> rules, boolean complete) {
            this(verdict, coordinate, reasons, rules, complete, verdict, false, false);
        }

        /** A screening reached with every feed answering, lowered by no mode until {@link #governed} says so. */
        Screening(Verdict verdict, String coordinate, List<String> reasons, List<String> rules, boolean complete,
                  Verdict floor, boolean governed) {
            this(verdict, coordinate, reasons, rules, complete, floor, governed, false);
        }

        /** A screening reached over the whole artifact - a structural refusal or a drift alarm, which are decisions
         *  about the fetch rather than about how far an inspector read. */
        Screening(Verdict verdict, String coordinate, List<String> reasons, List<String> rules) {
            this(verdict, coordinate, reasons, rules, true);
        }

        /** This screening with {@code reason} said last. */
        Screening adding(String reason) {
            return new Screening(verdict, coordinate, Stream.concat(reasons.stream(), Stream.of(reason)).toList(),
                    rules, complete, floor, governed, pending);
        }

        /** This screening with {@code rule} among the rules it was decided under. */
        Screening ruled(String rule) {
            return new Screening(verdict, coordinate, reasons, Stream.concat(rules.stream(), Stream.of(rule)).toList(),
                    complete, floor, governed, pending);
        }

        /** This screening decided {@code verdict} over {@code floor}, {@code governed} by a mode or not, and
         *  {@code pending} a second screen or not. */
        Screening judged(Verdict verdict, Verdict floor, boolean governed, boolean pending) {
            return new Screening(verdict, coordinate, reasons, rules, complete, floor, governed, pending);
        }
    }

    /** The rule a release younger than the immaturity hold is held for. */
    static final String IMMATURE_RULE = "Immature release";

    /** The rule an artifact no inspector could read whole is held for, under withhold-incomplete-screens. */
    static final String INCOMPLETE_RULE = "Incomplete screen";

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
     *  from a path-derived subject, WITHOUT reading the body - {@link ComplianceGate#assessUnclaimed} deliberately
     *  skips the license/discovered dimensions, so an ordinary raw fetch is NOT over-quarantined as unknown-license
     *  while a deny-listed coordinate delivered as raw content is still withheld. The immaturity hold still applies on
     *  top, exactly as it does for a claimed subject. A feed that cannot answer holds the copy, as it does on the
     *  claimed legs, so the metadata and raw-download legs that screen from the path alone fail closed too. */
    private Screening assessUnclaimed(String path, Instant lastModified) {
        Screening screening;
        try {
            screening = decideUnclaimed(gate, path, lastModified);
        } catch (RuntimeException failure) {
            screening = outage(path, List.of(), failure,
                    () -> decideUnclaimed(gate.advisories(AdvisorySource.none()), path, lastModified));
        }
        return governed(path, screening);
    }

    /** {@link #assessUnclaimed}'s decision through {@code gate}, which raises when an advisory feed does. */
    private Screening decideUnclaimed(ComplianceGate gate, String path, Instant lastModified) {
        ComplianceGate.Assessment assessment = gate.assessUnclaimed(pathDerivedSubject(path));
        Verdict verdict = assessment.verdict();
        List<String> reasons = new ArrayList<>();
        for (ComplianceGate.Finding finding : assessment.findings()) {
            reasons.add(finding.detail());
        }
        List<String> rules = new ArrayList<>(assessment.rules());
        if (verdict == Verdict.ALLOW && immature(lastModified)) {
            verdict = Verdict.QUARANTINE;
            reasons.add("Immature: upstream published within the " + holdDays + "-day hold");
            rules.add(IMMATURE_RULE);
        }
        return new Screening(verdict, coordinate(List.of(pathDerivedSubject(path))), reasons, rules, true,
                assessment.denied(), false);
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
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Optional<ArtifactDescriptor> described = inventory.describe(path);
        if (described.isEmpty() || described.get().coordinate() == null || described.get().version() == null) {
            // A file the layout files under a version without describing it as one - a Go module's .info and .mod
            // beside its archive - is that version's.
            Optional<ArtifactDescriptor> member = inventory.versionOf(path);
            if (member.isPresent() && member.get().coordinate() != null) {
                described = member;
            }
        }
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
     *  its digest-pinned verdict record. A {@link QualityInspector} carries no version, so each validator
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
                ? aware.withTrust(SignerTrustProvider.trust(ComplianceSettings.lookup(store), store))
                : inspector;
    }

    List<ComplianceGate.Subject> inspect(String path, byte[] body, URI origin) throws IOException {
        QualityInspector.Lookup lookup = origin == null ? siblings : new SiblingLookup(origin);
        // Every inspector that claims the path screens the fetched body, not just the first to match, so the content
        // scanner (the embedded-secret dimension) composes with the format inspector; InspectionMerge keeps the
        // package subject ahead of any content-scan subject so the quarantine log names the coordinate.
        List<ComplianceGate.Subject> subjects = new ArrayList<>();
        for (QualityInspector claimed : INSPECTORS) {
            if (claimed.handles(path)) {
                QualityInspector inspector = trusting(claimed);
                subjects.addAll(attributed(inspector, path, () -> inspector.inspectArtifact(path, body, lookup)));
            }
        }
        return InspectionMerge.order(subjects);
    }

    /**
     * Inspect an artifact from its FULLY-SPOOLED body (the hardened proxy leg's full-body tier): every claiming
     * inspector screens the whole body via {@link QualityInspector#inspectArtifact(String, QualityInspector.Content,
     * QualityInspector.Lookup)} - a full-body-tier inspector (the secret content scanner) reading past the bounded
     * prefix so a secret sitting beyond the 32 MiB window is still caught, a format inspector default-bridged to the
     * same front prefix the {@code byte[]} leg hands it. Mirrors {@link #inspect(String, byte[], URI)} but over a
     * re-openable spool handle rather than a heap {@code byte[]}; a {@link MalformedArtifactException} still surfaces
     * for the hardened leg to refuse (fail-closed), exactly as on the {@code byte[]} path.
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
    QualityInspector.Inspection inspectFullBody(String path, QualityInspector.Content body, URI origin)
            throws IOException {
        QualityInspector.Lookup lookup = origin == null ? siblings : new SiblingLookup(origin);
        List<ComplianceGate.Subject> subjects = new ArrayList<>();
        List<String> boundStopped = new ArrayList<>();
        for (QualityInspector claimed : INSPECTORS) {
            if (claimed.handles(path)) {
                QualityInspector inspector = trusting(claimed);
                QualityInspector.Inspection inspection = attributed(inspector, path,
                        () -> inspector.inspectArtifact(path, body, lookup));
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
     * held, recorded, attributed - and so does this one: the caller catches only {@link MalformedArtifactException},
     * so all three shapes leave here as one naming the inspector, and the caller's fail-closed path records and
     * refuses identically whichever one occurred, however the artifact arrived. The distinction
     * the publish leg draws between "could not parse" and "threw" is preserved in the message rather than in the
     * type, because on this leg both mean the same thing to the caller: these bytes were not screened, so they do
     * not serve. A deployment installs many inspectors, so "an inspector threw" names none of them.
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

        /** The upstream URL the screened bytes were fetched from, or {@code null} where none is known. */
        private final URI origin;

        SiblingLookup(URI origin) {
            this.origin = origin;
        }

        @Override
        public Optional<URI> origin() {
            return Optional.ofNullable(origin);
        }

        /** The settings the screened repository's store carries. */
        @Override
        public UnaryOperator<String> settings() {
            return ComplianceSettings.lookup(store);
        }

        /** The coordinate the claiming format gives the path in this repository. */
        @Override
        public Optional<ArtifactDescriptor> described(String path) {
            return BlobLayout.claimed(path, store);
        }

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

        /** The stored read: {@link #fetchBounded} already reads the published pointer, with no withhold probe. */
        @Override
        public Optional<Bounded> fetchStored(String path, int limit) throws IOException {
            return fetchBounded(path, limit);
        }

        /** What a format recorded in this repository, by the key it wrote it under. */
        @Override
        public Optional<Bounded> fetchRecorded(String key, int limit) throws IOException {
            return QualityInspector.Lookup.recorded(store, key, limit);
        }
    }
}
