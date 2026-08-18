package build.jenesis.repository.gc.test;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * A stand-in for the blobs-namespace format whose served blobs are reachable only through a stored <em>document</em> -
 * OCI's shape, kept format-neutral here so the collector's half of D-027 is asserted without the gc tests taking a
 * dependency on a format plugin or a JSON parser. It resolves the two key faces OCI has:
 *
 * <ul>
 *   <li>{@code oci/<name>/tags/<tag>} - a pointer whose {@code sha256:<hex>} body names the document;</li>
 *   <li>{@code oci/types/<hex>} - a per-document sidecar whose KEY names it, the digest-only image's only lifeline.</li>
 * </ul>
 *
 * <p>From there it reports every {@code sha256:<hex>} the document mentions - the manifest's own digest plus the
 * config and layer digests a real manifest JSON carries - by scanning rather than parsing, since what is under test is
 * that the mark phase unions a lent set into the reference shards the sweep reads, not how a format spells its
 * documents. {@code OciFormat} owns the real derivation and its own tests.
 *
 * <p>It also implements the contract's fail-closed clause: a document that is present but unreadable ({@code !!}, the
 * stand-in for an unparseable manifest) throws rather than answering a short list.
 */
final class DocumentReferences implements BlobReferences {

    private static final Pattern DIGEST = Pattern.compile("sha256:([0-9a-f]{64})");

    @Override
    public List<String> blobRoots() {
        return List.of("oci");
    }

    @Override
    public List<String> references(String key, ArtifactStore store) throws IOException {
        String document = document(key, store);
        if (document == null) {
            return List.of();
        }
        Optional<ArtifactStore.Versioned> body = store.readVersioned("blobs/" + document);
        if (body.isEmpty()) {
            return List.of(document);   // already collected residue - the sidecar keeps nothing else alive
        }
        String content = new String(body.get().content(), StandardCharsets.UTF_8);
        if (content.startsWith("!!")) {
            throw new IOException("the document " + document + " that " + key + " serves cannot be read, so the "
                    + "blobs it references cannot be enumerated");
        }
        List<String> hashes = new ArrayList<>();
        hashes.add(document);
        Matcher matcher = DIGEST.matcher(content);
        while (matcher.find()) {
            hashes.add(matcher.group(1));
        }
        return hashes;
    }

    /** The document hash this key resolves to, or {@code null} for a key that names none. */
    private static String document(String key, ArtifactStore store) throws IOException {
        if (key.startsWith("oci/types/")) {
            String hex = key.substring("oci/types/".length());
            return hex.matches("[0-9a-f]{64}") ? hex : null;
        }
        if (!key.startsWith("oci/") || key.lastIndexOf("/tags/") < 0) {
            return null;
        }
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
        if (pointer.isEmpty()) {
            return null;
        }
        Matcher matcher = DIGEST.matcher(new String(pointer.get().content(), StandardCharsets.UTF_8).trim());
        return matcher.matches() ? matcher.group(1) : null;
    }
}
