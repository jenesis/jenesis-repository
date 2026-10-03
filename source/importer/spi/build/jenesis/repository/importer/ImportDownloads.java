package build.jenesis.repository.importer;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.net.Origins;

/**
 * How a migration connector downloads one asset from the incumbent. The credential it was given goes only to the
 * incumbent's own origin: a listing may name an asset anywhere, and a cross-origin download goes unauthenticated, so
 * a {@code 401} there fails the import rather than handing the incumbent's credential to a third party. Anything but
 * a {@code 200} is an {@link ImportFailure} classified by its status.
 */
public final class ImportDownloads {

    private ImportDownloads() {
    }

    /** The asset at {@code url}, the credential headers sent only when {@code url} shares {@code source}'s origin. */
    public static InputStream open(ProxyFormat.Fetcher fetcher, URI source, URI url, Map<String, String> credentials)
            throws IOException {
        return open(fetcher, url, Origins.same(source, url) ? credentials : Map.of());
    }

    /** The asset at {@code url}, requested with exactly {@code headers}. */
    public static InputStream open(ProxyFormat.Fetcher fetcher, URI url, Map<String, String> headers)
            throws IOException {
        ProxyFormat.Download download = fetcher.download(url, headers)
                .orElseThrow(() -> ImportFailure.unreachable(url));
        if (download.status() != 200) {
            download.close();
            throw ImportFailure.status(download.status(), url, "Download");
        }
        return download.body();
    }
}
