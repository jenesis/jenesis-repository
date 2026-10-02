package build.jenesis.repository.importer.jenesis;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;

/**
 * Builds a {@link JenesisSource} over the request's base URL and repository for a {@code "jenesis"} migration. It
 * reports a format per asset, so it needs none up front. The API key is the request's password, else its username,
 * since this product's credential is a single opaque key.
 */
public final class JenesisSourceProvider implements ImportSourceProvider {

    @Override
    public String name() {
        return "jenesis";
    }

    @Override
    public String label() {
        return "Jenesis";
    }

    @Override
    public ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher) {
        JenesisSource source = new JenesisSource(request.url(), request.repository(), fetcher);
        String key = request.password() != null ? request.password() : request.username();
        if (key != null) {
            source = source.withKey(key);
        }
        return request.cursor() == null ? source : source.from(request.cursor());
    }
}
