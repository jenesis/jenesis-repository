package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * Everything one signer signed, per repository: the browse an operator opens when a signer-changed finding names an
 * identity, and the blast radius of a key the moment it is revoked - which is the same question asked in the other
 * direction. Written beside the continuity record from the same observation, an accepted version that carried a
 * trusted signature, and read by bounded, cursor-paged point reads; nothing here enumerates a repository.
 *
 * <p>Two small key spaces under the continuity root. {@code signers/who/<signer hash>} holds the identity a hash
 * stands for, written once; {@code signers/by/<signer hash>/<coordinate hash>} holds one document per signer and
 * coordinate - ecosystem, coordinate, versions counted, since when, the last version counted - rewritten under
 * compare-and-set as versions land, with the same rule the continuity record applies: a version already counted is
 * not counted again, so a re-publish and a late sidecar re-derivation agree. The hashes keep a signer's own
 * characters (a Sigstore subject is a URL, an OpenPGP fingerprint forty hex digits) and a coordinate's separators
 * out of the key space, the way the continuity document's key already does, and they are what a cursor names.
 *
 * <p>Read the other way, a page of signers is a page of names under {@code signers/by} with one point read each
 * for the identity, and a page of one signer's coordinates a page of names under that signer's prefix with one
 * point read each for the document: a repository with a million signed versions costs a screen no more than a
 * repository with ten, and a caller past a page follows the cursor. A hash that appears while its identity or
 * document is still being written - the two writes are not one - is skipped rather than rendered half-known, and
 * shows on the next page load.
 */
public final class SignerIndex {

    static final String WHO = ContinuityTrust.ROOT + "/who";
    static final String BY = ContinuityTrust.ROOT + "/by";

    /** The largest page a surface may ask for; a caller past it follows {@code next}. */
    public static final int MAX_PAGE = 1000;

    private SignerIndex() {
    }

    /** A signer the index knows: its identity, and the hash the index files it under (what a cursor names). */
    public record Signer(SignerIdentity signer, String id) {
    }

    /** One coordinate a signer signed: how many of its versions this signer signed, since when, and the last one. */
    public record Signed(String ecosystem, String coordinate, int versions, Instant since, String last) {
    }

    /** A page of rows and the cursor the next page starts after, {@code null} on the last page. */
    public record Page<T>(List<T> rows, String next) {
    }

    /** The signers seen on accepted versions, a page at a time in hash order. */
    public static Page<Signer> signers(ArtifactStore store, String after, int limit) throws IOException {
        List<String> ids = names(store, BY, after, limit);
        List<Signer> signers = new ArrayList<>();
        for (String id : ids.subList(0, Math.min(ids.size(), limit))) {
            Optional<SignerIdentity> identity = store.readVersioned(WHO + "/" + id)
                    .flatMap(versioned -> SignerIdentity.ofWire(new String(versioned.content(), StandardCharsets.UTF_8).trim()));
            identity.ifPresent(signer -> signers.add(new Signer(signer, id)));
        }
        return new Page<>(List.copyOf(signers), ids.size() > limit ? ids.get(limit - 1) : null);
    }

    /** The coordinates one signer signed, a page at a time. Empty for a signer the index does not know. */
    public static Page<Signed> signedBy(ArtifactStore store, SignerIdentity signer, String after, int limit)
            throws IOException {
        String prefix = BY + "/" + id(signer);
        List<String> ids = names(store, prefix, after, limit);
        List<Signed> signed = new ArrayList<>();
        for (String id : ids.subList(0, Math.min(ids.size(), limit))) {
            store.readVersioned(prefix + "/" + id).flatMap(versioned -> parse(versioned.content())).ifPresent(signed::add);
        }
        return new Page<>(List.copyOf(signed), ids.size() > limit ? ids.get(limit - 1) : null);
    }

    /**
     * Record that an accepted version of a coordinate carried this signer's trusted signature - the continuity
     * observation, indexed from the signer's side. The identity document is written once; the per-coordinate
     * document counts a version not yet counted.
     */
    public static void observed(ArtifactStore store, String ecosystem, String coordinate, String version,
                                SignerIdentity signer, Instant when) throws IOException {
        String id = id(signer);
        String who = WHO + "/" + id;
        if (store.readVersioned(who).isEmpty()) {
            store.writeVersioned(who, (signer.wire() + "\n").getBytes(StandardCharsets.UTF_8), null);
        }
        String coordinateId = ContinuityTrust.coordinateId(ecosystem, coordinate);
        Retries.update(store, BY + "/" + id + "/" + coordinateId, current -> {
            Optional<Signed> stored = current.flatMap(versioned -> parse(versioned.content()));
            Signed next;
            if (stored.isPresent()) {
                Signed same = stored.get();
                next = version.equals(same.last()) ? same
                        : new Signed(ecosystem, coordinate, same.versions() + 1, same.since(), version);
            } else {
                next = new Signed(ecosystem, coordinate, 1, when, version);
            }
            return render(next);
        });
    }

    /** The hash a signer is filed under: a digest of its wire form, so the identity's own characters never shape
     *  the key space. */
    public static String id(SignerIdentity signer) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(signer.wire().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JDK", impossible);
        }
    }

    /** Up to {@code limit + 1} child names under {@code prefix} after {@code after}: one more than the page, so the
     *  caller knows whether a next page exists without a count. */
    private static List<String> names(ArtifactStore store, String prefix, String after, int limit) {
        List<String> names = new ArrayList<>();
        store.page(prefix, after == null ? "" : after, Math.clamp(limit, 1, MAX_PAGE) + 1, names::add);
        return names;
    }

    private static byte[] render(Signed signed) {
        return ("ecosystem=" + signed.ecosystem() + "\ncoordinate=" + signed.coordinate() + "\nversions="
                + signed.versions() + "\nsince=" + (signed.since() == null ? "" : signed.since()) + "\nlast="
                + signed.last() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Optional<Signed> parse(byte[] content) {
        Map<String, String> fields = new HashMap<>();
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        String ecosystem = fields.get("ecosystem"), coordinate = fields.get("coordinate");
        if (ecosystem == null || coordinate == null) {
            return Optional.empty();
        }
        try {
            String since = fields.getOrDefault("since", "");
            return Optional.of(new Signed(ecosystem, coordinate, Integer.parseInt(fields.getOrDefault("versions", "1")),
                    since.isEmpty() ? null : Instant.parse(since), fields.getOrDefault("last", "")));
        } catch (IllegalArgumentException | DateTimeException malformed) {
            return Optional.empty();   // a document this deployment cannot read is not a row
        }
    }
}
