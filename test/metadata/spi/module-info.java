/**
 * The consolidated metadata document contract in isolation: the canonical key codec round-trips and guards, the
 * total reader tolerates torn/foreign bytes, the tri-state envelope serialises and re-reads, an unrecognised or
 * newer-tagged section is carried through a mutate byte-for-structure verbatim (the row-carry property at the section
 * level), and a newer-format document is rendered but refused for mutation.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.metadata
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.metadata.test {
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.compliance;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
