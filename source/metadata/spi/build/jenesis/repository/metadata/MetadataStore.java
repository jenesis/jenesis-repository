package build.jenesis.repository.metadata;

import module java.base;

/**
 * The consolidated metadata document over one repository's scoped store: each subsystem reads and mutates its own
 * tagged section of a coordinate version's document, and every surface reads the whole document in one lookup.
 *
 * <p>Every mutate is a section-scoped compare-and-set with bounded retry: a writer transforms only its own
 * {@link Section} and carries the others verbatim, so concurrent writers of disjoint sections converge and a
 * multi-section batch commits in one CAS.
 */
public interface MetadataStore {

    /** The whole document of a coordinate version, or empty when none was written. Total: a torn or foreign object
     *  reads as an empty document with a warning. */
    Optional<MetadataDocument> read(String ecosystem, String coordinate, String version) throws IOException;

    /** One section of a coordinate version's document, or empty when the document or that section is absent. */
    Optional<Section> section(String ecosystem, String coordinate, String version, String tag) throws IOException;

    /** Mutate one section in a single compare-and-set cycle, retrying a concurrent writer's conflict a bounded number
     *  of times. */
    void mutate(String ecosystem, String coordinate, String version, String tag, SectionMutation mutation)
            throws IOException;

    /**
     * Mutate several sections in one compare-and-set cycle: one read, every named transform applied, one commit
     * (retried on a token conflict). Sections not named are carried verbatim, so a batch of rows costs one CAS rather
     * than one per row.
     *
     * @throws IllegalStateException when the stored document's {@code format} is newer than this reader knows; the
     *     write is refused rather than downgrade-rewriting it
     */
    void mutate(String ecosystem, String coordinate, String version,
                SequencedMap<String, SectionMutation> mutations) throws IOException;

    /** The per-coordinate document - the version-independent facts such as maintainer health, at the reserved
     *  {@link MetadataKey#COORDINATE} segment - or empty when none was written. Total, as {@link #read} is. */
    Optional<MetadataDocument> readCoordinate(String ecosystem, String coordinate) throws IOException;

    /** One section of a coordinate's per-coordinate document, or empty when the document or that section is absent. */
    Optional<Section> coordinateSection(String ecosystem, String coordinate, String tag) throws IOException;

    /** Mutate one section of a coordinate's document in a single compare-and-set cycle with bounded retry. The section
     *  owner's merge rides in the mutation (health keeps its monotonic {@code scannedAt} guard). */
    void mutateCoordinate(String ecosystem, String coordinate, String tag, SectionMutation mutation)
            throws IOException;
}
