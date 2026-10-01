package build.jenesis.repository.gate.store;

import module java.base;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ComplianceSettings;
import build.jenesis.repository.compliance.MalformedArtifactException;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.SignerTrustProvider;
import build.jenesis.repository.compliance.TrustAware;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gate.InspectionMerge;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.PublishInterceptor.Content;

/**
 * How the publish gate turns a just-stored artifact into compliance subjects: the {@link QualityInspector}s that
 * claim its path run over one bounded read of it (or, past the prefix, over a stream when the deployment says so),
 * each inside a containment that names the inspector a failure came from, with the already-published siblings
 * reachable through {@link #siblings}. What it returns is subjects, or one of the two refusals the
 * {@link ComplianceScreen} turns into a disposition - it decides nothing itself.
 *
 * <p>The screen holds the one instance, built from the inspectors and the trust discovered as the screen's class is
 * initialised, so the discovery runs once per JVM. Everything but the sibling lookup is package-private: the lookup
 * is public because the signature-completion observer re-inspects a held artifact through it and a test drives it
 * directly, and nothing else here is API.
 */
public final class PublishInspection {

    private final List<QualityInspector> inspectors;

    /** Whether the deployment supplies signer trust at all. Present, it is overlaid onto every {@link TrustAware}
     *  inspector before it runs, for the reason the health ledger is overlaid onto its dimension: the screen holds
     *  the request's scoped store and the {@link java.util.ServiceLoader}-built inspector does not. Absent, an
     *  inspector verifies against nothing and reports every signature untrusted - the fail-closed direction, and never
     *  a silent "trusted" because a module is missing. */
    private final boolean trustInstalled;

    PublishInspection(List<QualityInspector> inspectors, boolean trustInstalled) {
        this.inspectors = inspectors;
        this.trustInstalled = trustInstalled;
    }

    /** The most bytes handed to an inspector from a claimed artifact. A claimed upload is a metadata document or a
     *  small archive by the inspectors' nature (a POM, a {@code package.json}, a {@code .nuspec}), whose declaration
     *  sits at the front - a jar's manifest, a wheel's METADATA - so a bounded prefix carries everything they read,
     *  while a pathologically large jar is never pulled whole into a {@code byte[]} to gate it (which a
     *  {@code readAllBytes} would, materialising the entire artifact in heap on the publish path). An artifact past the
     *  cap is streamed or refused as the deployment's oversized policy says ({@link #inspect}).
     *  <p>It is the SPI's prefix tier itself, not a screen-local copy of the same number: the {@code byte[]} legs are
     *  contractually handed at most that much, and {@link #inspect} decides whether the inspectors saw the artifact
     *  whole by comparing the body against this very bound - so a screen reading a different amount than the tier the
     *  inspectors are written against would make that completeness test answer about a different body.
     *  <p>Read through the accessor, never latched: the tier is an operator dial, and a screen holding a stale
     *  copy of it would disagree with the inspectors it is comparing against about what "whole" means. */
    private static int inspectionLimit() {
        return QualityInspector.prefixInspectionLimit();
    }

    /** The prefixes whose held artifacts the publish of {@code published} completes the declaration for - every
     *  claiming inspector's {@link QualityInspector#completes} answer, in discovery order and without repeats. */
    Set<String> completing(String published) {
        Set<String> prefixes = new LinkedHashSet<>();
        for (QualityInspector inspector : inspectors) {
            if (inspector.handles(published)) {
                inspector.completes(published).ifPresent(prefixes::add);
            }
        }
        return prefixes;
    }

    /** Read a claimed artifact back from the store for its inspectors - the one place the screen materialises
     *  content, and only for an upload an inspector claims; the unclaimed bulk of a large artifact streams past. The
     *  read is capped at {@link #inspectionLimit()}, so even a claimed but pathologically large jar is gated from a
     *  bounded prefix rather than pulled whole into heap. Every inspector that claims the path runs over that one
     *  bounded read (a format inspector and the content scanner compose - {@link InspectionMerge} keeps the package
     *  subject first), not just the first to match. */
    List<ComplianceGate.Subject> inspect(ArtifactDescriptor artifact, Content content) throws IOException {
        String path = artifact.path();
        List<QualityInspector> claiming = new ArrayList<>();
        for (QualityInspector inspector : inspectors) {
            if (inspector.handles(path)) {
                claiming.add(inspector);
            }
        }
        // An inspector that only reads what sits beside an artifact handles a raw path without claiming it unless a
        // sidecar is actually there: nothing claimed, nothing read - the unclaimed bulk of a raw upload streams past.
        QualityInspector.Lookup siblings = siblings(content);
        if (claiming.stream().noneMatch(inspector -> inspector.claims(path, siblings))) {
            return List.of();
        }
        byte[] metadata;
        boolean truncated;
        try (InputStream in = content.open()) {
            metadata = in.readNBytes(inspectionLimit());
            // Whether the artifact ran past the inspection window: one byte beyond the prefix means the inspectors saw
            // only a truncated head, so an empty result may be "metadata beyond the window" rather than "nothing here".
            truncated = in.read() != -1;
        }
        if (truncated) {
            // The artifact is larger than the most any inspector is handed in one array, and what to do about
            // that is the operator's call rather than this screen's. Only STREAM reaches a verdict about what
            // the artifact CONTAINS; the other two refuse on its size and say so, because nothing read it.
            QualityInspector.Oversized policy = QualityInspector.oversized(ComplianceSettings.lookup(content.store()));
            if (policy != QualityInspector.Oversized.STREAM) {
                throw new OversizedArtifactException(policy, artifact.size(), inspectionLimit());
            }
            return streamed(artifact, content, metadata, claiming, siblings);
        }
        List<ComplianceGate.Subject> subjects = new ArrayList<>();
        for (QualityInspector claimed : claiming) {
            QualityInspector inspector = bound(claimed, content);
            subjects.addAll(contained(inspector, path, () -> inspector.inspect(path, metadata, siblings)));
        }
        return InspectionMerge.order(subjects);
    }

    /**
     * Screen a claimed artifact bigger than the prefix by reading it from the store as a STREAM - the default answer
     * to {@link QualityInspector#OVERSIZED_KEY}, and the one that reaches a verdict about the artifact rather than
     * about its size.
     *
     * <p>This is the same leg the hardened proxy screens an upstream artifact through
     * ({@link QualityInspector#inspectArtifact(String, QualityInspector.Content, QualityInspector.Lookup)}), handed a
     * re-openable body over the blob just stored. An inspector that overrides it streams the whole artifact under the
     * full-body tier; one that does not is bridged down to the same front prefix it would have seen anyway and says
     * so in its own answer, so nothing here silently upgrades what an inspector actually read.
     *
     * <p><b>What it buys.</b> The format inspectors crack their archives through
     * {@code BoundedArchive.zipEntry}, which already takes an {@code InputStream} - so a declaration stored at the
     * BACK of a large archive becomes reachable without a new parser and without random access. Without it,
     * a 1.5 GiB NuGet package or a {@code .deb} of the same size is held on the shipped defaults, because the
     * signature material each carries inside itself sits past the bound and an unreadable signature is scored with
     * the untrusted dial.
     *
     * <p>An inspector that ran out of body or out of budget reports an incomplete inspection, and a claimed artifact
     * whose incomplete inspection yielded no package subject falls back to a path-derived one rather than reading as
     * nothing to gate.
     */
    private List<ComplianceGate.Subject> streamed(ArtifactDescriptor artifact, Content content,
                                                         byte[] metadata, List<QualityInspector> claiming,
                                                         QualityInspector.Lookup siblings) throws IOException {
        String path = artifact.path();
        QualityInspector.Content body = new QualityInspector.Content() {
            @Override
            public long size() {
                // Counted as the bytes streamed into the store, so this never stats the blob it just wrote.
                return artifact.size();
            }

            @Override
            public InputStream open() throws IOException {
                return content.open();
            }
        };
        List<ComplianceGate.Subject> subjects = new ArrayList<>();
        boolean incomplete = false;
        for (QualityInspector claimed : claiming) {
            QualityInspector inspector = bound(claimed, content);
            if (!inspector.streams()) {
                // It would read the same front prefix through either leg, so it is handed the array the read
                // above already produced, by the very call a publish has always made. Its answer covers a
                // prefix of a larger body, which is what the fallback below is for.
                subjects.addAll(contained(inspector, path, () -> inspector.inspect(path, metadata, siblings)));
                incomplete = true;
                continue;
            }
            QualityInspector.Inspection inspection =
                    contained(inspector, path, () -> inspector.inspectArtifact(path, body, siblings));
            subjects.addAll(inspection.subjects());
            incomplete |= !inspection.complete();
        }
        if (incomplete && InspectionMerge.noPackageSubject(subjects)) {
            // An incomplete inspection that yielded no package subject must NOT read as "nothing to gate" and silently
            // ACCEPT - a padded archive with trailing metadata would then publish un-screened. A filename-derived
            // coordinate subject keeps the deny-list and license dimensions biting, so the incomplete screening is
            // recorded with a reason rather than passed silently.
            //
            // The guard asks whether any PACKAGE subject came back, not whether the list is empty: a content-scan
            // subject - a detected secret, an inbound attestation, a publisher's signature - makes the list non-empty
            // while carrying no licensable identity, so reading emptiness would let any content inspector that found
            // something switch this fallback off. The proxy leg asks the same question through the same predicate.
            //
            // The fallback is APPENDED rather than substituted, so the content finding is kept.
            List<ComplianceGate.Subject> withFallback = new ArrayList<>(subjects);
            withFallback.add(pathDerivedSubject(artifact));
            return InspectionMerge.order(withFallback);
        }
        return InspectionMerge.order(subjects);
    }

    /** Trust is rebound per request, not per process: it is tenant state, and the screen is what knows which tenant
     *  this publish belongs to. An inspector that verifies nothing never implements the seam and is handed through
     *  untouched. Both inspection legs bind the same way, so they bind in one place. Trust is configuration, read
     *  through {@link ComplianceSettings#lookup(ArtifactStore)} - the lookup the gate's dimensions are built from -
     *  since trust read anywhere else would answer about a different deployment than the one the gate was built for. */
    private QualityInspector bound(QualityInspector claimed, Content content) {
        return claimed instanceof TrustAware aware && trustInstalled
                ? aware.withTrust(SignerTrustProvider.trust(ComplianceSettings.lookup(content.store()), content.store()))
                : claimed;
    }

    /** What an inspection call may do that is not returning an answer, named. */
    @FunctionalInterface
    private interface Inspecting<T> {

        T inspect() throws IOException;
    }

    /**
     * Run one inspector inside the screen's containment - the host half of the inspector fan-out, in one place
     * because both legs need it and a second copy is how the two come to fail differently.
     *
     * <p>The identity is read from the inspector's CLASS BEFORE the call and never asked of the guest afterwards: a
     * handler that re-enters a broken guest to ask what to blame is how containment gets defeated from inside its own
     * handler. Every failure shape the SPI permits therefore leaves this named, and leaves it on the SAME fail-closed
     * leg: {@code QualityInspector.inspect} declares {@code throws IOException}, so a plain IOException (a
     * ZipException off a truncated central directory, an EOFException off a half-written body) is as legal an
     * inspector failure as a RuntimeException. The caller catches MalformedArtifactException and RuntimeException,
     * so an IOException is re-raised as an {@link InspectionFault} and held with a legible reason rather than
     * reaching the publisher as a raw 500 with no hold, no recorded finding and no diagnostic.
     *
     * <p>The could-not-parse leg stays distinct - it is a contract-bearing answer the caller routes on - and gains the
     * name of the inspector that raised it.
     */
    private static <T> T contained(QualityInspector inspector, String path, Inspecting<T> call) throws IOException {
        String identity = inspector.getClass().getName();
        try {
            return call.inspect();
        } catch (MalformedArtifactException malformed) {
            throw new MalformedArtifactException(identity + " could not parse " + path + ": "
                    + reason(malformed), malformed);
        } catch (IOException | RuntimeException failure) {
            // Both re-raised as the inspection-fault the caller already fails closed on, attributed to the
            // inspector: a deployment carries many of these, and "a quality inspector threw" names none of them.
            throw new InspectionFault(identity + " threw inspecting " + path + ": " + reason(failure), failure);
        }
    }

    /**
     * A discovered {@link QualityInspector} failed inspecting an upload, named. Unchecked so it lands on the screen's
     * existing inspection-fault leg (fail closed, hold the upload, record the reason) rather than needing a second
     * one, and carrying the inspector's implementation class in its message because a deployment installs many of
     * them and "a quality inspector threw" names none. It exists to carry an attribution out of {@link #inspect},
     * never as an API - which is why it is package-private and why nothing catches it by type.
     */
    static final class InspectionFault extends IllegalStateException {

        InspectionFault(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The one-line reason an attributed message carries: the failure's own message where it has one, else its type.
     *  The same shape the {@code Contributions.reason} uses on the collected-report side. */
    static String reason(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /**
     * The already-published-sibling lookup this screen hands its inspectors on the publish leg: a thin adapter over
     * the publication seam's <em>own</em> two sibling reads, one compliance leg delegating to one store leg.
     *
     * <p><b>Each leg delegates to its counterpart; neither is derived from the other.</b> A bounded read synthesised
     * from the whole-document read would inherit the whole-document ceiling, so {@code AttestationInspector}, which
     * asks for a 32 MiB bounded read of the artifact its referrer names, would get an exception above
     * {@link PublishInterceptor.Content#LARGEST_SIBLING} (8 MiB) here while the proxy leg, which streams, answers
     * {@code truncated} for the very same sibling - an 8-32 MiB companion degrading on one leg and raising on the
     * other. The publication seam offers a real bounded read ({@link PublishInterceptor.Content#sibling(String, int)},
     * capped at the store), so the screen hands that through unchanged and the two legs agree.
     *
     * <p>The two {@code Bounded} records are the same pair of values in two SPIs (the store contract and the
     * compliance contract, which must not depend on each other's shapes), so the adapter re-wraps rather
     * than re-reads - the array is handed on, never copied, since duplicating it would double the very heap the bound
     * protects.
     *
     * <p>Exposed for the bounded-read guard test, which drives both legs directly rather than through a registered
     * inspector - the same seam {@code ProxyScreen.siblingLookup()} offers on the other leg.
     */
    public static QualityInspector.Lookup siblings(Content content) {
        return new QualityInspector.Lookup() {

            /** The settings the publication's store carries - the same lookup the screen judging it reads. */
            @Override
            public UnaryOperator<String> settings() {
                return ComplianceSettings.lookup(content.store());
            }

            @Override
            public Optional<byte[]> fetch(String path) throws IOException {
                // The whole-document read, delegated: it carries the publication seam's LARGEST_SIBLING ceiling and
                // throws past it, which is what the compliance Lookup's own fetch clause promises.
                Optional<byte[]> published = content.sibling(path);
                return published.isPresent() ? published : owned(path, content.store());
            }

            @Override
            public Optional<QualityInspector.Lookup.Bounded> fetchBounded(String path, int limit) throws IOException {

                // The bounded-fact read, delegated to the publication seam's own bounded leg - capped at the store,
                // honouring the CALLER's limit rather than LARGEST_SIBLING, and reporting the overflow instead of
                // raising on it.
                Optional<QualityInspector.Lookup.Bounded> published = content.sibling(path, limit)
                        .map(bounded -> new QualityInspector.Lookup.Bounded(bounded.content(), bounded.truncated()));
                if (published.isPresent()) {
                    return published;
                }
                return ownedPrefix(path, content.store(), limit).map(read -> new QualityInspector.Lookup.Bounded(
                        read.length > limit ? Arrays.copyOf(read, limit) : read, read.length > limit));
            }

            @Override
            public Optional<QualityInspector.Lookup.Bounded> fetchStored(String path, int limit) throws IOException {
                // The stored pointer, not the serving resolution: one point read for a companion that is absent,
                // where the serving question pays the withhold-chain probe and the content-addressed marker on top.
                // The serving question would cost three store reads per Maven publish for an .asc that is usually not
                // there, a fixed cost on the request path for a question this seam does not need answered.
                Optional<String> stored = new Publication(content.store()).blob(path);
                if (stored.isEmpty()) {
                    // Falls back to the blobs-namespace formats' own serving keys, which have no publish/ pointer to
                    // read: those resolve through the format and are unaffected by the distinction being drawn here.
                    return ownedPrefix(path, content.store(), limit).map(read -> new QualityInspector.Lookup.Bounded(
                            read.length > limit ? Arrays.copyOf(read, limit) : read, read.length > limit));
                }
                try (InputStream in = new Blobs(content.store()).open(stored.get())) {
                    byte[] read = in.readNBytes(limit + 1);
                    return Optional.of(read.length > limit
                            ? new QualityInspector.Lookup.Bounded(Arrays.copyOf(read, limit), true)
                            : new QualityInspector.Lookup.Bounded(read, false));
                }
            }

            @Override
            public Optional<QualityInspector.Lookup.Bounded> fetchRecorded(String key, int limit) throws IOException {
                // A format's own record, by the key it wrote it under: one versioned point read, bounded after the
                // fact, since a record is a few lines and an index copy is held to the signature bound by its writer.
                return content.store().readVersioned(key).map(recorded -> recorded.content().length > limit
                        ? new QualityInspector.Lookup.Bounded(Arrays.copyOf(recorded.content(), limit), true)
                        : new QualityInspector.Lookup.Bounded(recorded.content(), false));
            }
        };
    }

    /**
     * The sibling as the FORMAT that owns the layout serves it, for the formats whose artifacts are not published
     * through the generic {@code publish/<path>} pointer - Hugging Face, npm, PyPI, Go, Conan and the
     * rest of the blobs-namespace group.
     *
     * <p>The generic read above answers for every format that uses that pointer, and empty for every format that
     * does not, however plainly the artifact is being served. A format that keeps its own key space answers through
     * {@link BlobLayout#servingKey} instead, which resolves the way its serving does - the same revision rules, the
     * same absence - so an inspector can read a Hugging Face model card beside the file it describes and gate a
     * repository on the licence it declares.
     *
     * <p>Read whole under the publication seam's own sibling ceiling, so this path is bounded exactly like the one it
     * backs up rather than becoming the way a large companion gets pulled into heap.
     */
    private static Optional<byte[]> owned(String path, ArtifactStore store) throws IOException {
        return ownedPrefix(path, store, PublishInterceptor.Content.largestSibling());
    }

    private static Optional<byte[]> ownedPrefix(String path, ArtifactStore store, int limit) throws IOException {
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            if (!(format instanceof BlobLayout layout)) {
                continue;
            }
            Optional<String> key = layout.servingKey(path, store);
            if (key.isEmpty()) {
                continue;
            }
            Blobs blobs = new Blobs(store);
            // A hold applies to a sibling exactly as it applies to a served read: an inspector must not be handed the
            // bytes of a document the registry is withholding, or a screen would reach a verdict off evidence no
            // client can see. Blobs.read resolves the pointer to its blob, which is also why this cannot be a plain
            // store.open of the key - that would hand back the pointer's own body, a hash.
            if (blobs.withheld(key.get())) {
                return Optional.empty();
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!blobs.read(key.get(), buffer)) {
                return Optional.empty();
            }
            byte[] read = buffer.toByteArray();
            return Optional.of(read.length > limit + 1 ? Arrays.copyOf(read, limit + 1) : read);
        }
        return Optional.empty();
    }

    /**
     * An artifact past the inspection prefix on a deployment that chose not to stream it.
     *
     * <p>Unchecked so it rides out of {@link #inspect} the way {@link InspectionFault} does, and carrying the policy
     * because the two values it can hold lead to different dispositions. It is the one refusal in this screen that is
     * not a failure: nothing was attempted, so nothing failed, and the message says exactly that.
     */
    static final class OversizedArtifactException extends IllegalStateException {

        private final QualityInspector.Oversized policy;

        OversizedArtifactException(QualityInspector.Oversized policy, long size, int bound) {
            super("the artifact is " + size + " bytes, past the " + bound + "-byte inspection prefix ("
                    + QualityInspector.PREFIX_INSPECTION_LIMIT_KEY + "), and this deployment's "
                    + QualityInspector.OVERSIZED_KEY + " is " + policy + " - so it was not screened, and nothing "
                    + "here is a statement about what it contains");
            this.policy = policy;
        }

        QualityInspector.Oversized policy() {
            return policy;
        }
    }

    /** The stand-in subject for an artifact screened from its path alone rather than from parsed content: an inspector
     *  claimed it but could not read it within the inspection window (a truncated archive), OR no inspector claimed it
     *  at all (raw / un-inspected content). Its own coordinate where the layout descriptor carries one, else its
     *  filename, so the deny-list still applies. It declares no license: the truncated-claimed path runs the full
     *  {@link ComplianceGate#assess} so the unknown-license dimension holds it, while the unclaimed path runs
     *  {@link ComplianceGate#assessUnclaimed}, which skips that dimension - a raw upload is screened for the deny-list
     *  without being over-quarantined as unknown-license. */
    static ComplianceGate.Subject pathDerivedSubject(ArtifactDescriptor artifact) {
        String ecosystem = artifact.ecosystem() == null ? "" : artifact.ecosystem();
        String coordinate = artifact.coordinate() != null ? artifact.coordinate() : fileName(artifact.path());
        String version = artifact.version() == null ? "" : artifact.version();
        return new ComplianceGate.Subject(ecosystem, coordinate, version, List.of());
    }

    /** The last path segment - the filename a truncated-artifact fallback subject is coordinated by when the layout
     *  maps it to no coordinate. */
    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
