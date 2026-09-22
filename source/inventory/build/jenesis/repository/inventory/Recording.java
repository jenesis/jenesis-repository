package build.jenesis.repository.inventory;

import module java.base;

/**
 * One publish's inventory facts, recorded together: the published section, the local-upload origin, the declared
 * licences and the provenance summary land in a single compare-and-set of the version's metadata document, and the
 * identity index folds once, with the licences the document ends up holding. The gate builds one per accepted
 * publish from what it already knows and commits it.
 *
 * <p>Before this the same facts arrived through three faces of the inventory, each a read-modify-write of the same
 * document - three reads and two writes where one of each does, measured key by key - and the identity index folded
 * on the first write with a licence fingerprint the second had yet to record, so a first publish sat in the index
 * with an empty fingerprint until the re-fold caught up. A recording is built, filled and committed on the thread
 * that publishes; it is not shared.
 */
public final class Recording {

    private final InventoryRecording recording;
    final String ecosystem;
    final String coordinate;
    final String version;
    final boolean prerelease;
    final Instant published;
    String originSha256;
    List<LicenseInventory.Declared> licenses;
    Boolean provenanceVerified;
    String provenanceSha256;
    String signatureOutcome;
    String signatureSigner;
    String signatureGrade;
    String signatureLocation;
    String signatureSource;
    Map<String, String> signatureDetails;

    Recording(InventoryRecording recording, String ecosystem, String coordinate, String version, boolean prerelease,
              Instant published) {
        this.recording = recording;
        this.ecosystem = Objects.requireNonNull(ecosystem, "ecosystem");
        this.coordinate = Objects.requireNonNull(coordinate, "coordinate");
        this.version = Objects.requireNonNull(version, "version");
        this.prerelease = prerelease;
        this.published = Objects.requireNonNull(published, "published");
    }

    /** The content hash of a hand-uploaded body - the store computed it on write, so an origin row never re-reads
     *  the body. A {@code null} or blank hash records no origin row: a hold release or a re-screen has none. */
    public Recording origin(String sha256) {
        this.originSha256 = sha256;
        return this;
    }

    /** The licences the inspected body declares - unioned into what the document already records, so a re-publish
     *  never loses a licence a previous body declared. An empty list records that none were declared, which is a
     *  fact in its own right and not the absence of one. */
    public Recording licenses(List<LicenseInventory.Declared> licenses) {
        this.licenses = List.copyOf(licenses);
        return this;
    }

    /** The provenance summary an attestation gave: whether the artifact was verified against it, and the digest it
     *  named. Recorded only when an attestation shipped - no attestation means no summary and no warning. */
    public Recording provenance(boolean verified, String sha256) {
        this.provenanceVerified = verified;
        this.provenanceSha256 = sha256;
        return this;
    }

    /**
     * The publisher signature this version turned out to carry - what verifying produced, who signed it, how much the
     * signature is worth, and where the material sat.
     *
     * <p>Recorded for every outcome rather than only for a good one. "Nobody signed this" and "somebody we do not
     * know signed this" are facts a console has to be able to show, and a record written only on success would leave
     * a screen unable to tell them from a version published before signatures were being checked at all.
     *
     * <p>{@code source} is where the signer's trust came from - a configured key or pin, a discovered key, the
     * provenance of the declared repository - and {@code details} what else the material stated that an operator
     * reads apart from the identity: a keyless signer's issuer and subject, its transparency-log entry. Either
     * may be absent, and a summary without them reads as unknown, never as a signature that said nothing.
     */
    public Recording signature(String outcome, String signer, String grade, String location, String source,
                               Map<String, String> details) {
        this.signatureOutcome = outcome;
        this.signatureSigner = signer;
        this.signatureGrade = grade;
        this.signatureLocation = location;
        this.signatureSource = source;
        this.signatureDetails = details == null ? Map.of() : Map.copyOf(details);
        return this;
    }

    /** Land every fact in one write of the version's document and fold the identity index once. */
    public void commit() throws IOException {
        recording.commit(this);
    }
}
