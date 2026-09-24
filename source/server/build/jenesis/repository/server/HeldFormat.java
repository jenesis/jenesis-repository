package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.store.RepositoryDocument;

/**
 * What a routed repository holds and the path of the request as its formats see it - the type's mount in front of the
 * path within the repository. Every surface that serves a repository request or looks at one on its way resolves it
 * here, so none of them offers a path to a format the repository does not hold.
 *
 * @param type the repository's type: the formats it holds and the mount its paths take.
 * @param path the format-facing path.
 */
public record HeldFormat(RepositoryType type, String path) {

    /**
     * What {@code route}'s repository holds among {@code formats}, or empty when it holds nothing among them - never
     * created, created before repositories held a format, or holding one this composition does not carry.
     *
     * @param routing the routing that resolved {@code route}, which reads the repository's document
     *                ({@link RepositoryRouting#document}).
     */
    public static Optional<HeldFormat> of(RepositoryRouting routing, RepositoryRouting.Route route,
                                          List<RepositoryFormat> formats) throws IOException {
        return routing.document(route)
                .flatMap(document -> RepositoryType.of(document.format(), formats))
                .map(type -> new HeldFormat(type, type.formatPath(route.path())));
    }

    /** The format of the repository's type that claims the request's path, or empty when none does. */
    public Optional<RepositoryFormat> claiming() {
        return type.claiming(path);
    }
}
