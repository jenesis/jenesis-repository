package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.TaskStatus;

/**
 * An {@link ArtifactStore} decorator that refuses every write, so a deployment configured read-only
 * ({@code jenrepo.read-only=true}) serves reads normally while every mutation - a hosted publish, a
 * {@code maven-metadata.xml} compare-and-set, a delete, a content-addressed blob write - is rejected at this single
 * low-level choke point, whether it originates at an HTTP write endpoint or an internal path (a write-through proxy
 * cache, an import replay, a background sweep). Because every serving, routing, tenant and console bean resolves
 * through the one wrapped store, wrapping here refuses <em>every</em> write by construction, not just the ones an
 * endpoint guard remembers to cover.
 *
 * <p>The read methods pass straight through to the delegate; {@link #scope} re-wraps the scoped delegate so every
 * tenant / repository subspace stays read-only too (the same recursion {@link QuotaArtifactStore#scope} uses). A
 * refused write raises {@link ReadOnlyException} before the delegate is touched - no partial bytes are stored - which
 * a server maps to HTTP {@code 403}. This wrapper is applied only when the deployment opts in, so an ordinary
 * read-write deployment never pays for it.
 */
public final class ReadOnlyArtifactStore extends ForwardingArtifactStore implements ObservabilitySource {


    public ReadOnlyArtifactStore(ArtifactStore delegate) {
        super(delegate);
    }

    /** The wrapped store's signals - a quota's usage, say - since the context holds only this, the outermost store. */
    @Override
    public List<Metric> metrics() {
        return delegate instanceof ObservabilitySource wrapped ? wrapped.metrics() : List.of();
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return delegate instanceof ObservabilitySource wrapped ? wrapped.healthChecks() : List.of();
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        return delegate instanceof ObservabilitySource wrapped ? wrapped.taskStatuses() : List.of();
    }

    /** Recency is a write of the access time, so a read-only store records none. */
    @Override
    public void touch(String key) {
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new ReadOnlyArtifactStore(delegate.scope(tenant));
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        throw new ReadOnlyException();
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        throw new ReadOnlyException();
    }

    @Override
    public void delete(String key) throws IOException {
        throw new ReadOnlyException();
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        throw new ReadOnlyException();
    }

    /** Refused like every other write, and refused <em>before</em> the stream is read: the inherited body would
     *  buffer the whole content into heap and only then throw. */
    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) {
        throw new ReadOnlyException();
    }
}
