package build.jenesis.repository.dependents.spi;

import module java.base;

/**
 * The read model of one repository's declared dependencies, bound to its scoped store by a
 * {@link DependentsQueryProvider}: for a package, the versions whose manifest declares a dependency on it and the
 * requirement each states - what the console, the CLI and {@code /api/dependents} answer "who declares a dependency on
 * X" from, a page at a time. Which published versions rely on a version, as their resolved closures reach it, is the
 * closure's relied-on index; a declaration is a requirement, whose resolution is a client's later decision, so the two
 * are kept apart and a declaration never counts as reaching a version.
 */
public interface DependentsQuery {

    /**
     * One bounded page of the versions whose manifest declares a dependency on the package {@code dependency} - spelled
     * as its ecosystem spells a coordinate, without a version - each with the requirement the manifest states,
     * resumable by the opaque {@code cursor}.
     *
     * <p>It answers what the index recorded, so a version since deleted or withheld may be listed until the next pass;
     * a surface that discloses names screens each row.
     *
     * @param cursor a previous page's {@link DeclarationPage#nextCursor()}, or {@code null}/empty for the first page
     * @param limit the maximum declarations to return (a non-positive limit yields an empty page)
     */
    DeclarationPage declarations(String dependency, String cursor, int limit) throws IOException;

    /** When the declared dependencies last completed a pass over every published version, or empty before the first:
     *  an empty page before it reads as "not yet indexed", never as "nothing declares it". */
    Optional<Instant> declarationsBuiltAt() throws IOException;

    /** One version's declaration of a dependency: the declaring version's ecosystem, coordinate and version, and the
     *  stated requirement - empty where there is none. */
    record Declaration(String ecosystem, String coordinate, String version, String requirement) {
        public Declaration {
            Objects.requireNonNull(ecosystem, "ecosystem");
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
            requirement = requirement == null ? "" : requirement;
        }
    }

    /** One bounded page of {@link #declarations} and the opaque cursor to resume after, or {@code null} on the last
     *  page. */
    record DeclarationPage(List<Declaration> declarations, String nextCursor) {
        public DeclarationPage {
            declarations = List.copyOf(declarations);
        }
    }
}
