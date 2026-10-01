package build.jenesis.repository.importer.maven;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;

/**
 * Builds a {@link MavenSource} for a {@code "maven"} migration - any server exposing the Maven layout over plain HTTP.
 * The repository is the tree's path under the base URL ({@code .} when the URL is the tree root). The root is probed
 * once: a host that does not answer at all builds no source, so the submission is rejected as a bad request; an
 * answering host with listing disabled is fine, since the walk falls back to the index.
 */
public final class MavenSourceProvider implements ImportSourceProvider {

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public String label() {
        return "Maven repository";
    }

    @Override
    public ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher) {
        MavenSource source = new MavenSource(request.url(), request.repository(), fetcher);
        if (request.username() != null && request.password() != null) {
            source = source.withCredentials(request.username(), request.password());
        }
        if (request.cursor() != null) {
            source = source.from(request.cursor());
        }
        return source.reachable() ? source : null;
    }
}
