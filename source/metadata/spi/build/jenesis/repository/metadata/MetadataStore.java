package build.jenesis.repository.metadata;

import module java.base;

/**
 * The consolidated metadata document over one repository's scoped store: any subsystem reads and mutates its own
 * tagged section of a coordinate version's document, and every surface reads the whole document in one lookup. This
 * is the foundation contract - the version-doc reader plus the section-scoped, multi-section CAS mutate that
 * generalises {@code StoreFindings.mutate}/{@code Document} to the section level. No production path reads or writes
 * this document yet; the per-subsystem cutovers (licenses, publish facts, findings, health, AI outcomes) land in
 * later tickets.
 *
 * <p>Every mutate is a section-scoped compare-and-set with bounded retry: a writer transforms only its own
 * {@link Section} and carries every other section verbatim, so disjoint-section concurrent writers converge (each
 * re-reads and re-applies its section on a token conflict) and a single multi-section batch commits in one CAS.
 */
public interface MetadataStore {

    /** The whole metadata document of a coordinate version, or empty when none was ever written. The read is total:
     *  a torn or foreign object reads as an empty document with a WARNING, never an exception. */
    Optional<MetadataDocument> read(String ecosystem, String coordinate, String version) throws IOException;

    /** One section of a coordinate version's document, or empty when the document or that section is absent. */
    Optional<Section> section(String ecosystem, String coordinate, String version, String tag) throws IOException;

    /** Mutate one section of a coordinate version's document in a single compare-and-set cycle, retrying a
     *  concurrent writer's conflict a bounded number of times. */
    void mutate(String ecosystem, String coordinate, String version, String tag, SectionMutation mutation)
            throws IOException;

    /**
     * Mutate several sections of a coordinate version's document in <em>one</em> compare-and-set cycle: one read,
     * every named section's transform applied, one CAS commit (retried on a token conflict). Disjoint-section
     * writers converge; sections not named are carried verbatim. This is the batched shape that collapses the
     * per-row CAS storms a later cutover replaces.
     *
     * @throws IllegalStateException when the stored document's {@code format} is newer than this reader knows - the
     *                               loud format guard; the write is refused rather than downgrade-rewriting it
     */
    void mutate(String ecosystem, String coordinate, String version,
                SequencedMap<String, SectionMutation> mutations) throws IOException;

    /**
     * The whole per-<em>coordinate</em> document - the version-independent facts (maintainer health) living at the
     * reserved {@link MetadataKey#COORDINATE} segment - or empty when none was ever written. The read is total, exactly
     * as {@link #read} is: a torn or foreign object reads as an empty document with a WARNING, never an exception. This
     * is the second document scope the {@code meta} tree carries (§5): one document per coordinate for the facts
     * that are a property of the project rather than a release.
     */
    Optional<MetadataDocument> readCoordinate(String ecosystem, String coordinate) throws IOException;

    /** One section of a coordinate's per-coordinate document, or empty when the document or that section is absent. */
    Optional<Section> coordinateSection(String ecosystem, String coordinate, String tag) throws IOException;

    /** Mutate one section of a coordinate's per-coordinate document in a single compare-and-set cycle, retrying a
     *  concurrent writer's conflict a bounded number of times - the coordinate-scoped sibling of {@link #mutate}. The
     *  section owner's own merge is preserved by the mutation (health keeps its monotonic-{@code scannedAt} guard). */
    void mutateCoordinate(String ecosystem, String coordinate, String tag, SectionMutation mutation)
            throws IOException;
}
