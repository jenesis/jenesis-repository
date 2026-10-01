package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * Continuity: who a coordinate's accepted versions were signed by, learned through {@link #observed} and answered by
 * {@link #expected}. It holds no keys and admits nobody; it supplies the expectation a verified signature by another
 * signer is measured against under {@code signature-signer-changed}.
 *
 * <p>One document per coordinate and scheme ({@link #key}), rewritten under compare-and-set when an accepted version
 * carried a signer: the same signer counts one more version, once per version as {@link SignerIndex} decides, and
 * another replaces the expectation. Only admitted versions reach here, so a change an operator released becomes the
 * new expectation. A pin is answered ahead of this and never overwritten.
 */
final class ContinuityTrust implements SignerTrust {

    static final String ROOT = "signers";

    private final ArtifactStore store;

    ContinuityTrust(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public Optional<byte[]> material(String scheme) {
        return Optional.empty();
    }

    @Override
    public boolean trusts(SignerIdentity signer, String ecosystem, String coordinate) {
        return false;
    }

    @Override
    public Optional<Expectation> expected(String ecosystem, String coordinate, String scheme) {
        if (store == null || ecosystem == null || coordinate == null || scheme == null) {
            return Optional.empty();
        }
        try {
            return store.readVersioned(key(ecosystem, coordinate, scheme))
                    .flatMap(versioned -> parse(versioned.content()))
                    .map(record -> new Expectation(record.signer(), record.versions(), record.since(), false));
        } catch (IOException unreadable) {
            return Optional.empty();   // no expectation is the fail-open direction here: nothing is admitted by it
        }
    }

    @Override
    public void observed(String ecosystem, String coordinate, String version, SignerIdentity signer, Instant when)
            throws IOException {
        if (store == null || ecosystem == null || coordinate == null || version == null || signer == null) {
            return;
        }
        boolean first = SignerIndex.counted(store, ecosystem, coordinate, version, signer);
        Retries.update(store, key(ecosystem, coordinate, signer.scheme()), current -> {
            Optional<Record> stored = current.flatMap(versioned -> parse(versioned.content()));
            Record next;
            if (stored.isPresent() && stored.get().signer().equals(signer)) {
                Record same = stored.get();
                next = first ? new Record(signer, same.versions() + 1, same.since(), version) : same;
            } else {
                next = new Record(signer, 1, when, version);
            }
            return next.render();
        });
        // The same observation from the signer's side.
        SignerIndex.observed(store, ecosystem, coordinate, version, signer, when, first);
    }

    /**
     * The document key: the coordinate's digest, then the scheme, so an artifact signed two ways (OpenPGP and a Sigstore
     * bundle) keeps two continuities rather than one that flips.
     */
    static String key(String ecosystem, String coordinate, String scheme) {
        return ROOT + "/" + coordinateId(ecosystem, coordinate) + "/" + scheme;
    }

    /** The coordinate's digest, shared with the signer index. */
    static String coordinateId(String ecosystem, String coordinate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((ecosystem + "\n" + coordinate).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JDK", impossible);
        }
    }

    /** What the document holds: the signer, how many versions it signed, since when, and the last version counted. */
    record Record(SignerIdentity signer, int versions, Instant since, String last) {

        byte[] render() {
            return ("signer=" + signer.wire() + "\nversions=" + versions + "\nsince="
                    + (since == null ? "" : since) + "\nlast=" + last + "\n").getBytes(StandardCharsets.UTF_8);
        }
    }

    static Optional<Record> parse(byte[] content) {
        Map<String, String> fields = new HashMap<>();
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        String wire = fields.get("signer");
        if (wire == null || wire.indexOf(':') <= 0) {
            return Optional.empty();
        }
        try {
            SignerIdentity signer = new SignerIdentity(wire.substring(0, wire.indexOf(':')),
                    wire.substring(wire.indexOf(':') + 1));
            int versions = Integer.parseInt(fields.getOrDefault("versions", "1"));
            String since = fields.getOrDefault("since", "");
            return Optional.of(new Record(signer, versions, since.isEmpty() ? null : Instant.parse(since),
                    fields.getOrDefault("last", "")));
        } catch (IllegalArgumentException | DateTimeException malformed) {
            return Optional.empty();   // a document this deployment cannot read establishes nothing
        }
    }
}
