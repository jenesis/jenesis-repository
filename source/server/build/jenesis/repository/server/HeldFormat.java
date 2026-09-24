package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.RepositoryDocument;

/**
 * The format a routed repository holds and the path of the request as that format sees it - its
 * {@link RepositoryFormat#mount mount} in front of the path within the repository. Every surface that serves a
 * repository request or looks at one on its way resolves it here, so none of them offers a path to a format the
 * repository does not hold.
 *
 * @param format the format the repository holds.
 * @param path   the format-facing path.
 */
public record HeldFormat(RepositoryFormat format, String path) {

    /**
     * The format {@code route}'s repository holds among {@code formats}, or empty when it holds none of them - never
     * created, created before repositories held a format, or holding one this composition does not carry.
     *
     * @param routing the routing that resolved {@code route}, which reads the repository's document
     *                ({@link RepositoryRouting#document}).
     */
    public static Optional<HeldFormat> of(RepositoryRouting routing, RepositoryRouting.Route route,
                                          List<RepositoryFormat> formats) throws IOException {
        Optional<RepositoryDocument> document = routing.document(route);
        if (document.isEmpty()) {
            return Optional.empty();
        }
        for (RepositoryFormat format : formats) {
            if (format.name().equals(document.get().format())) {
                return Optional.of(new HeldFormat(format, format.mount() + route.path()));
            }
        }
        return Optional.empty();
    }
}
