package build.jenesis.repository.format;

import module java.base;

/**
 * A format whose coordinate names a package by more than the name the package goes by - an RPM coordinate carries the
 * repository it was published into, {@code <repo>/openssl} - so a bill of materials, which names a package by its own
 * metadata, reaches the copies a repository holds through {@link #packageName}. A role of {@link RepositoryFormat},
 * discovered with it; a format whose coordinate is the package's own name implements nothing, and {@link #of} answers
 * its coordinate unchanged.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Read purity.</b> {@link #packageName} is a function of its argument: no I/O, the same answer on every call,
 *       from any thread.</li>
 *   <li><b>Absence sentinel.</b> Never {@code null}; a coordinate it cannot read, and one that already is a package's
 *       name, is answered unchanged, so a name maps to itself.</li>
 *   <li><b>Ordering / determinism.</b> Several formats may declare one ecosystem; {@link #of} asks every installed one
 *       that renames and answers their name where they agree, the coordinate unchanged where they do not, so the
 *       answer is never a property of discovery order.</li>
 * </ol>
 */
public interface PackageNaming extends EcosystemLayout {

    /** The name the package at {@code coordinate} goes by in its own metadata and in a bill of materials. */
    String packageName(String coordinate);

    /** The name the package at {@code coordinate} of {@code ecosystem} goes by, as every installed format of the
     *  ecosystem that renames says - the coordinate itself where none does, or they disagree. */
    static String of(String ecosystem, String coordinate) {
        Set<String> named = new HashSet<>();
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            if (format instanceof PackageNaming naming && naming.ecosystem().equals(ecosystem)) {
                named.add(naming.packageName(coordinate));
            }
        }
        return named.size() == 1 ? named.iterator().next() : coordinate;
    }

    /** Whether an installed format of {@code ecosystem} names a package by more than its coordinate. */
    static boolean renames(String ecosystem) {
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            if (format instanceof PackageNaming naming && naming.ecosystem().equals(ecosystem)) {
                return true;
            }
        }
        return false;
    }
}
