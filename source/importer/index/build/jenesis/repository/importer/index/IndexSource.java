package build.jenesis.repository.importer.index;

import module java.base;

import build.jenesis.repository.importer.ImportDownloads;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportFailure;
import build.jenesis.repository.importer.ImportSource;

/**
 * An {@link ImportSource} over a format's own published index: it streams what
 * {@link ProxyFormat#enumerate(ProxyFormat.Fetcher, URI)} yields, reporting every asset under the format's name so the
 * orchestrator routes it to that format's importer. An enumerated path derives from a foreign index, so it is
 * {@link ImportSource#safePath semi-trusted} like a listing path and a traversal-laced one is skipped; the absolute URL
 * a coordinate carries is screened at the fetch by {@link build.jenesis.repository.importer.ImportScreen}.
 *
 * <p>The resume cursor is the last fully consumed layout path, reported every {@value #CHECKPOINT_INTERVAL} assets. A
 * resumed walk re-enumerates and skips past the cursor; when the cursor does not appear (the index changed) the walk
 * restarts, since an import is idempotent and re-importing is safe where losing assets is not. Bytes stream lazily
 * through the fetcher's {@code download}, so a skipped asset is never fetched and a large one never buffered.
 */
public final class IndexSource implements ImportSource {

    private static final int CHECKPOINT_INTERVAL = 64;

    private final RepositoryFormat format;
    private final URI root;
    private final ProxyFormat.Fetcher fetcher;
    private final String cursor;

    IndexSource(RepositoryFormat format, URI root, ProxyFormat.Fetcher fetcher, String cursor) {
        this.format = format;
        this.root = root;
        this.fetcher = fetcher;
        this.cursor = cursor;
    }

    /** Whether the walk's root answers at all - any HTTP status counts, only a transport failure does not - so a
     *  submission naming an unreachable host is rejected synchronously instead of failing asynchronously. */
    boolean reachable() {
        try {
            return fetcher.fetch(root, Map.of()).isPresent();
        } catch (IOException unreachable) {
            return false;
        }
    }

    @Override
    public void forEach(Asset consumer, Checkpoint checkpoint) throws IOException {
        if (cursor == null || !walk(consumer, checkpoint, cursor)) {
            walk(consumer, checkpoint, null);
        }
        checkpoint.reached(null);
    }

    /** Walks one enumeration of the index, skipping to just past {@code resume} when given; false when the cursor
     *  never appeared (nothing was consumed - the caller restarts the walk from the beginning). */
    private boolean walk(Asset consumer, Checkpoint checkpoint, String resume) throws IOException {
        try (Stream<ProxyFormat.Coordinate> coordinates = ((ProxyFormat) format).enumerate(fetcher, root)) {
            Iterator<ProxyFormat.Coordinate> iterator = coordinates.iterator();
            boolean skipping = resume != null;
            int pending = 0;
            String last = null;
            while (iterator.hasNext()) {
                ProxyFormat.Coordinate coordinate = iterator.next();
                if (!ImportSource.safePath(coordinate.path())) {
                    consumer.dropped(coordinate.path(), ImportSource.Reason.UNSAFE_PATH);
                    continue;
                }
                if (skipping) {
                    skipping = !coordinate.path().equals(resume);
                    continue;
                }
                consumer.accept(format.name(), coordinate.path(), () -> open(coordinate));
                last = coordinate.path();
                if (++pending == CHECKPOINT_INTERVAL) {
                    checkpoint.reached(last);
                    pending = 0;
                }
            }
            if (pending > 0) {
                checkpoint.reached(last);
            }
            return !skipping;
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private InputStream open(ProxyFormat.Coordinate coordinate) throws IOException {
        URI url = coordinate.url();
        // The download URL derives from a foreign index and is an initial request rather than a redirect, so it is
        // screened by the ImportScreen wrapped around this source's fetcher, the one rule for every connector.
        return ImportDownloads.open(fetcher, url, coordinate.headers());
    }
}
