package build.jenesis.repository.format;

import module java.base;

import build.jenesis.repository.store.Providers;

/**
 * A repository type that holds several formats at once. A repository normally holds one format, and its URLs carry no
 * format segment - the format's {@link RepositoryFormat#mount mount} is put back in front of the path within it. A
 * combined type is for formats that serve one artifact two ways from the same store: its URLs keep each format's own
 * segment, so neither format's paths can shadow the other's.
 *
 * <p>Discovered through {@code ServiceLoader}, so a combined type ships with the module that knows the formats belong
 * together rather than being named by either of them.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #name()} is the repository type a repository is created as. It is a format name's shape and is unique
 *       across the installed formats and combined types together; a clash is a packaging error.</li>
 *   <li>{@link #formats()} names at least two formats. The type is offered only while every one of them is installed,
 *       and a request in such a repository is offered to exactly those formats.</li>
 *   <li>Pure and stateless: both answers are constants, read once per process.</li>
 * </ul>
 */
public interface CombinedFormat {

    /** The repository type's name, as a repository's document records it. */
    String name();

    /** The names of the formats a repository of this type holds, each at its own mount. */
    List<String> formats();

    /** Every combined type on the module path, validated and held for the process. */
    static List<CombinedFormat> installed() {
        return Declared.ALL;
    }

    /** A holder, so the load happens on first use and not on the interface's own initialisation. */
    final class Declared {

        private static final List<CombinedFormat> ALL = Providers.all("combined format",
                ServiceLoader.load(CombinedFormat.class), CombinedFormat::name, _ -> true, Optional::of);

        private Declared() {
        }
    }
}
