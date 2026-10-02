package build.jenesis.repository.importer.nexus;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;

/**
 * Builds a {@link NexusSource} for a {@code "nexus"} migration. Nexus needs no format up front, since it reports one
 * per asset, and the walk resumes from a cursor.
 */
public final class NexusSourceProvider implements ImportSourceProvider {

    @Override
    public String name() {
        return "nexus";
    }

    @Override
    public String label() {
        return "Nexus";
    }

    @Override
    public ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher) {
        NexusSource source = new NexusSource(request.url(), request.repository(), fetcher);
        if (request.username() != null && request.password() != null) {
            source = source.withCredentials(request.username(), request.password());
        }
        return request.cursor() == null ? source : source.from(request.cursor());
    }
}
