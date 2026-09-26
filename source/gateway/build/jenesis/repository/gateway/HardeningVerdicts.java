package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The read-only console surface of the hardened proxy leg: what the now-invisible full-body screening
 * leg has durably decided, assembled for an operator without re-screening or re-fetching a single byte. It reads only
 * what the leg already persisted - the digest-pinned {@link VerdictSection verdict} record in the consolidated metadata
 * document ({@link HardenedScreen} writes it on every screen), the typed structural refusals in the durable
 * {@link QuarantineLog} (the leg records each {@link HardenedScreen.Refusal} as a {@code REJECT} row prefixed
 * {@link HardenedScreen#REFUSAL_REASON_PREFIX}), and the gateway-wide drift alarm counter
 * ({@link HardenedScreen#driftEvents()}, the same count {@link HardeningObservability} reports) - so the read stands
 * when the upstream is down and never pays for a screen (§10 reads render, §7 the reader pays for nothing).
 *
 * <p>Every accessor is a pure durable-state read: {@link #view(String, int)} looks the coordinate's recorded verdict up
 * by a single metadata section read (the same {@link HardenedScreen.Coordinate coordinate derivation} the leg keys its
 * record off, so the console and the leg agree on the address), pages recent hardened refusals off the
 * {@link QuarantineLog}, and folds in the drift counter. A missing metadata module (no persistence installed) leaves
 * the verdict {@code null} - the leg records none in that mode, so the console shows exactly what is durable, an
 * un-screened rather than a fabricated verdict. The recorded {@code screenedAt} instant is surfaced verbatim so a
 * caller sees how stale the rendered verdict is (§10's staleness line): {@code null} reads as "never screened", never
 * as "clean".
 */
public final class HardeningVerdicts {

    /** How many QuarantineLog rows to scan per requested refusal, so a repository whose recent activity mixes hardened
     *  refusals with ordinary publish/proxy gate holds still fills the refusal page rather than under-reporting. */
    private static final int SCAN_FACTOR = 4;

    private final MetadataStore metadata;
    private final QuarantineLog quarantine;

    /** A verdict reader over an explicit {@code metadata} store and the repository's durable {@code quarantine}
     *  ledger. */
    public HardeningVerdicts(MetadataStore metadata, QuarantineLog quarantine) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
    }

    /** A verdict reader over a repository's scoped store: the consolidated metadata store is discovered through
     *  {@link MetadataProvider} and the {@link QuarantineLog} is read from the same store the hardened leg wrote its
     *  refusals to. This is the production wiring; the {@linkplain #HardeningVerdicts(MetadataStore, QuarantineLog)
     *  explicit constructor} is the
     *  test seam. */
    public static HardeningVerdicts over(ArtifactStore repositoryStore) {
        return new HardeningVerdicts(MetadataProvider.installed().over(repositoryStore),
                new QuarantineLog(repositoryStore));
    }

    /** The hardened-leg read for a coordinate: the digest-pinned verdict recorded over its last-screened bytes, the
     *  most recent hardened refusals in the repository (bounded by {@code refusalLimit}), and the gateway-wide drift
     *  alarm signal - all durable, none re-screened. */
    public View view(String path, int refusalLimit) throws IOException {
        return new View(path, verdict(path), refusals(refusalLimit), new Drift(HardenedScreen.driftEvents()));
    }

    /** The digest-pinned verdict recorded for a coordinate, or {@code null} when none is recorded (never screened, the
     *  document lost the section) - a single metadata section read, no screen. */
    public RecordedVerdict verdict(String path) throws IOException {
        HardenedScreen.Coordinate coordinate = HardenedScreen.coordinate(path);
        return VerdictSection.recorded(metadata.section(coordinate.ecosystem(), coordinate.coordinate(),
                coordinate.version(), VerdictSection.TAG)).map(HardeningVerdicts::render).orElse(null);
    }

    /** The most recent hardened structural refusals in the repository, newest first, bounded by {@code limit}: the
     *  {@link QuarantineLog} {@code REJECT} rows the leg recorded with the {@link HardenedScreen#REFUSAL_REASON_PREFIX}
     *  reason (oversize, stalled fetch, drift, unparseable, inspector error), told apart from an ordinary publish/proxy
     *  gate hold by that prefix. A read of the durable ledger's recent page, never a full scan and never a screen. */
    public List<Refusal> refusals(int limit) throws IOException {
        if (limit <= 0) {
            return List.of();
        }
        List<Refusal> refusals = new ArrayList<>();
        for (QuarantineLog.Event event : quarantine.events(limit * SCAN_FACTOR)) {
            if (hardenedRefusal(event)) {
                refusals.add(new Refusal(event.when().toString(), event.path(), event.coordinate(),
                        event.verdict().name(), event.reasons()));
                if (refusals.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(refusals);
    }

    /** Whether a recorded gate decision is a hardened-leg structural refusal (its reason names the leg), as opposed to
     *  an ordinary publish- or proxy-path gate hold recorded in the same log. */
    private static boolean hardenedRefusal(QuarantineLog.Event event) {
        return event.reasons().stream().anyMatch(reason -> reason.startsWith(HardenedScreen.REFUSAL_REASON_PREFIX));
    }

    private static RecordedVerdict render(VerdictSection.Recorded recorded) {
        List<Validator> validators = new ArrayList<>();
        for (VerdictSection.Validator validator : recorded.validators()) {
            validators.add(new Validator(validator.name(), validator.version()));
        }
        return new RecordedVerdict(recorded.digest(), recorded.verdict().name(), recorded.refusal(),
                recorded.screenedAt() == null ? null : recorded.screenedAt().toString(), recorded.profile(),
                recorded.source(), List.copyOf(validators));
    }

    /** The hardened-leg read for one coordinate, rendered for the console/API: the recorded verdict (or {@code null}
     *  when never screened), the repository's recent hardened refusals, and the drift alarm. */
    public record View(String path, RecordedVerdict verdict, List<Refusal> refusals, Drift drift) {

        public View {
            refusals = refusals == null ? List.of() : List.copyOf(refusals);
        }

        /** Whether a verdict has ever been recorded for this coordinate - false renders as "never screened", never as
         *  "clean" (§10). */
        public boolean screened() {
            return verdict != null;
        }

        /** The instant the coordinate was last screened, or {@code null} when never - the staleness line a read shows
         *  so a caller (even one lacking the write role) sees how fresh the rendered verdict is. */
        public String screenedAt() {
            return verdict == null ? null : verdict.screenedAt();
        }
    }

    /** A digest-pinned recorded verdict, flattened to strings so the console/API carries no metadata types. */
    public record RecordedVerdict(String digest, String verdict, String refusal, String screenedAt, String profile,
                                  String source, List<Validator> validators) {

        public RecordedVerdict {
            validators = validators == null ? List.of() : List.copyOf(validators);
        }
    }

    /** One validator that ran over the artifact: its name and version (version {@code null} when it supplies none). */
    public record Validator(String name, String version) {
    }

    /** One recorded hardened structural refusal: when it happened, the coordinate refused, the verdict and the reasons. */
    public record Refusal(String when, String path, String coordinate, String verdict, List<String> reasons) {

        public Refusal {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    /** The gateway-wide upstream-drift alarm: the count of immutable-coordinate re-fetches refused because the bytes
     *  changed under a pinned verdict (the {@code jenreg.gateway.hardened.drift} counter {@link HardeningObservability}
     *  reports). A non-zero count is a compromise indicator for the upstream. */
    public record Drift(long alarms) {

        public boolean alarming() {
            return alarms > 0;
        }
    }
}
