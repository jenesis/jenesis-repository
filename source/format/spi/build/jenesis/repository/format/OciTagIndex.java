package build.jenesis.repository.format;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The tags that name an OCI manifest, by the manifest's digest: one empty entry
 * {@code oci/.tagged/<hex>/<name>@<tag>} for every tag pointer {@code oci/<name>/tags/<tag>} that has been linked at
 * it, so the question "which tags serve this manifest" is a page of one small folder rather than a walk of every tag
 * the repository holds.
 *
 * <p>The index is a superset of the truth, never a subset. An entry is written <em>before</em> the pointer it names
 * and retired only <em>after</em> the pointer stopped naming its digest, so a crash between the two leaves an entry
 * too many and never one too few; and every answer is confirmed by a point read of each entry's pointer, so an entry
 * left behind costs a read and changes no decision. That ordering is what lets a removal decide from it: the one
 * failure that would cost data - a live tag the index does not know - cannot arise from the write paths, and a caller
 * that wants to prove a tag's own entry is there (as the sidecar guard does) is told by {@link #entered} whether the
 * index predates it.
 *
 * <p>The root sits inside the format's own {@code oci} space behind a leading dot, which no image name's path
 * component may carry, so no image can be named over it.
 */
public final class OciTagIndex {

    /** Where the index lives, under the OCI format's space. */
    public static final String ROOT = "oci/.tagged/";

    /** The page width one call reads the index in; a digest's entries are the tags ever linked at it, a handful. */
    private static final int PAGE = 1_000;

    private OciTagIndex() {
    }

    /** One tag of one image. */
    public record Tag(String name, String tag) {

        /** The tag's pointer key. */
        public String pointer() {
            return "oci/" + name + "/tags/" + tag;
        }
    }

    /** Record that the tag pointer {@code pointerKey} names the manifest {@code hex}, before the pointer is written.
     *  A key that is not a tag pointer records nothing. */
    public static void enter(ArtifactStore store, String pointerKey, String hex) throws IOException {
        Tag tag = tag(pointerKey);
        if (tag != null && hex != null) {
            store.write(entry(hex, tag), InputStream.nullInputStream());
        }
    }

    /** Retire the entry for {@code pointerKey} under {@code hex}, after the pointer stopped naming it. */
    public static void retire(ArtifactStore store, String pointerKey, String hex) throws IOException {
        Tag tag = tag(pointerKey);
        if (tag != null && hex != null) {
            store.delete(entry(hex, tag));
        }
    }

    /** Every tag entered under {@code hex}, confirmed or not - the raw index, for a caller that needs to know whether
     *  a tag it holds was entered at all. */
    public static List<Tag> entered(ArtifactStore store, String hex) {
        List<Tag> tags = new ArrayList<>();
        String folder = ROOT + hex;
        String after = "";
        while (true) {
            List<String> names = new ArrayList<>();
            store.page(folder, after, PAGE, names::add);
            for (String name : names) {
                Tag tag = decode(name);
                if (tag != null) {
                    tags.add(tag);
                }
            }
            if (names.size() < PAGE) {
                return tags;
            }
            after = names.getLast();
        }
    }

    /** The tags whose pointer names {@code hex} now: the entered ones, each confirmed by a read of its pointer. */
    public static List<Tag> current(ArtifactStore store, String hex) throws IOException {
        List<Tag> current = new ArrayList<>();
        for (Tag tag : entered(store, hex)) {
            if (names(store, tag, hex)) {
                current.add(tag);
            }
        }
        return current;
    }

    /** Whether {@code tag}'s pointer names {@code hex} right now. */
    public static boolean names(ArtifactStore store, Tag tag, String hex) throws IOException {
        return store.readVersioned(tag.pointer())
                .map(pointer -> hex(new String(pointer.content(), StandardCharsets.UTF_8).trim()))
                .filter(hex::equals)
                .isPresent();
    }

    /** The tag a pointer key {@code oci/<name>/tags/<tag>} names, or {@code null} for any other key. */
    public static Tag tag(String pointerKey) {
        if (!pointerKey.startsWith("oci/")) {
            return null;
        }
        int marker = pointerKey.lastIndexOf("/tags/");
        if (marker <= "oci/".length()) {
            return null;
        }
        String name = pointerKey.substring("oci/".length(), marker);
        String tag = pointerKey.substring(marker + "/tags/".length());
        return OciTags.isTag(tag) && !name.startsWith(".") ? new Tag(name, tag) : null;
    }

    /** The hex of a pointer body {@code sha256:<hex>}, or {@code null} for a body that names no digest. */
    private static String hex(String body) {
        return body.startsWith("sha256:") ? body.substring("sha256:".length()) : null;
    }

    private static String entry(String hex, Tag tag) {
        return ROOT + hex + "/" + URLEncoder.encode(tag.name(), StandardCharsets.UTF_8) + "@" + tag.tag();
    }

    private static Tag decode(String entry) {
        int colon = entry.lastIndexOf('@');
        if (colon <= 0) {
            return null;
        }
        String tag = entry.substring(colon + 1);
        return OciTags.isTag(tag) ? new Tag(URLDecoder.decode(entry.substring(0, colon), StandardCharsets.UTF_8), tag)
                : null;
    }
}
