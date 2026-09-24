package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * The store-backed half of continuity: what a coordinate's accepted versions established about who signs it, learned
 * from {@link #observed} and answered by {@link #expected}. It holds no key material and admits nobody - a signer
 * seen before is not thereby trusted - so its whole contribution to the composed trust is the expectation a verified
 * signature by another signer is measured against, under the {@code signature-signer-changed} dial.
 *
 * <p>One small document per coordinate, {@code signers/<sha256 of ecosystem and coordinate>}, read by point read
 * when a signature verified and rewritten under compare-and-set when an accepted version carried a signer: the
 * same signer counts one more version (a version already counted is not counted again, so a re-publish and a late
 * sidecar re-derivation agree); another signer replaces the expectation and starts its count at one. An accepted
 * version is what reaches here - a held one does not until an operator releases it - so the expectation follows
 * what the deployment actually admitted, and a change an operator waved through becomes the new expectation the
 * moment it lands. An operator's pin ({@code signature-trusted-signers}) is answered ahead of this by the configured
 * trust and is never overwritten by what was learned. Every observation is also indexed from the signer's side by
 * {@link SignerIndex}, under the same root.
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
        Retries.update(store, key(ecosystem, coordinate, signer.scheme()), current -> {
            Optional<Record> stored = current.flatMap(versioned -> parse(versioned.content()));
            Record next;
            if (stored.isPresent() && stored.get().signer().equals(signer)) {
                Record same = stored.get();
                next = version.equals(same.last()) ? same
                        : new Record(signer, same.versions() + 1, same.since(), version);
            } else {
                next = new Record(signer, 1, when, version);
            }
            return next.render();
        });
        // The same observation from the signer's side, so what one identity signed can be browsed and a revoked
        // key's blast radius read without a walk.
        SignerIndex.observed(store, ecosystem, coordinate, version, signer, when);
    }

    /**
     * The document key: the coordinate's digest and then the scheme.
     *
     * <p>The scheme is part of the key, and that is this part's whole answer to an artifact signed several ways at
     * once. A Maven release carries the detached OpenPGP signature its layout requires and may carry a Sigstore
     * bundle beside it, which establishes two continuities rather than one that flips; keyed by the coordinate
     * alone the two signers overwrote each other on every publish, and the next assessment reported whichever lost
     * as a change of signer. Measured 2026-09-15 by the end-to-end Sigstore leg, the only tier where both
     * signatures of one artifact reach the gate in the order a client sends them.
     *
     * <p>Records written before the scheme joined the key are unreachable, and continuity re-establishes itself
     * from the next signed publish of the coordinate. That is the fail-open direction for a dimension whose finding
     * is "this is not who signed last time", so nothing reads the old key and nothing migrates it.
     */
    static String key(String ecosystem, String coordinate, String scheme) {
        return ROOT + "/" + coordinateId(ecosystem, coordinate) + "/" + scheme;
    }

    /** The coordinate's own digest, without a scheme - shared with the signer index, which is keyed by coordinate
     *  and must name one the same way this does. */
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
