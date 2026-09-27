package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.metadata.MetadataDocument;

/**
 * What a version document says this repository holds of that version - the one reading the recording, the
 * enumerations and the repairs share, so none of them decides it a second way.
 *
 * <p>A version is held as one of two kinds. A <em>release</em> carries the {@code published} section: it was published
 * into this repository, and retention and every other reader of the published set keep to it. A <em>cached copy</em>
 * carries the {@code cached} section instead: a pull-through fetched it from an upstream, and it is seen by what is
 * about everything the repository holds - the scheduled advisory and health scans, the repository's overview and a
 * coordinate's page - and by nothing that keeps to releases. A version carrying both sections is a release.
 */
final class Holdings {

    private Holdings() {
    }

    /** The kind of holding a document records. */
    enum Kind {
        /** Published into this repository. */
        RELEASE,
        /** Cached from an upstream by a pull-through. */
        CACHED,
        /** Nothing: the document carries sections of its own (an origin trail, a verdict) but no holding. */
        NONE
    }

    static Kind kind(MetadataDocument document) {
        if (PublishedSection.published(document.section(PublishedSection.TAG))) {
            return Kind.RELEASE;
        }
        if (CachedSection.cached(document.section(CachedSection.TAG))) {
            return Kind.CACHED;
        }
        return Kind.NONE;
    }

    /** Whether the document records the version as held, of either kind. */
    static boolean held(MetadataDocument document) {
        return kind(document) != Kind.NONE;
    }

    /**
     * Whether the version's bytes arrived from an upstream rather than by a publish: its origin trail names one or
     * more fetches through a fallback and no hand upload. The trail is written beside the bytes by the fallback that
     * fetched them, so it is what a repair reads to tell a copy the store has no record of, or one recorded as a
     * release, from a release whose record was lost. It is the
     * same reading a quota reclaim takes of what is re-heatable: a copy of an upstream's artifact is exactly that.
     */
    static boolean fetched(MetadataDocument document) {
        return OriginSection.reheatableFallbackOnly(document.section(OriginSection.TAG));
    }

    /** The upstream an origin trail names for a fetched version: the address the first fallback fetch recorded, or
     *  {@code null} where it recorded none. */
    static String upstream(MetadataDocument document) {
        return OriginSection.acquisitions(document.section(OriginSection.TAG)).stream()
                .filter(OriginSection.Acquisition::fallback)
                .map(OriginSection.Acquisition::target)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
