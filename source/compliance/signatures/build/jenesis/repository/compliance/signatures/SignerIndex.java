package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.store.Checksums;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * Everything one signer signed, per repository: the browse a signer-changed finding opens and a revoked key's blast
 * radius. Written from the continuity observation of an accepted, trusted signature; read by cursor-paged point reads.
 *
 * <p>Under the continuity root: {@code signers/who/<signer hash>} holds the identity, written once;
 * {@code signers/by/<signer hash>/<coordinate hash>} the per-coordinate count, under compare-and-set; and
 * {@code signers/counted/<signer hash>/<coordinate hash>/<version hash>}, created once, decides whether a version
 * counts, here and in the continuity record alike. A crash between marker and count leaves a version one short, never
 * counted twice. A hash whose documents are still being written is skipped until the next page load.
 */
public final class SignerIndex {

    static final String WHO = ContinuityTrust.ROOT + "/who";
    static final String BY = ContinuityTrust.ROOT + "/by";
    static final String COUNTED = ContinuityTrust.ROOT + "/counted";

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
     * Records an accepted version's trusted signature from the signer's side: the identity once, and the version counted
     * once.
     */
    public static void observed(ArtifactStore store, String ecosystem, String coordinate, String version,
                                SignerIdentity signer, Instant when) throws IOException {
        observed(store, ecosystem, coordinate, version, signer, when,
                counted(store, ecosystem, coordinate, version, signer));
    }

    /** Marks {@code version} as signed by {@code signer}: {@code true} only the first time, when it counts. */
    static boolean counted(ArtifactStore store, String ecosystem, String coordinate, String version,
                           SignerIdentity signer) throws IOException {
        String key = COUNTED + "/" + id(signer) + "/" + ContinuityTrust.coordinateId(ecosystem, coordinate) + "/"
                + Checksums.sha256(version);
        return store.writeVersioned(key, new byte[0], null);
    }

    /** {@link #observed(ArtifactStore, String, String, String, SignerIdentity, Instant)} with whether this is the
     *  version's first count already decided. */
    static void observed(ArtifactStore store, String ecosystem, String coordinate, String version,
                         SignerIdentity signer, Instant when, boolean first) throws IOException {
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
                next = first ? new Signed(ecosystem, coordinate, same.versions() + 1, same.since(), version) : same;
            } else {
                next = new Signed(ecosystem, coordinate, 1, when, version);
            }
            return render(next);
        });
    }

    /** The hash a signer is filed under, a digest of its wire form. */
    public static String id(SignerIdentity signer) {
        return Checksums.sha256(signer.wire());
    }

    /** Up to {@code limit + 1} child names after {@code after}, the extra one saying whether a next page exists. */
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
