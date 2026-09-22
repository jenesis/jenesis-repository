package build.jenesis.repository.staging;

import module java.base;

/**
 * One staging repository and its lifecycle. It accepts {@link #stage staged} items while {@code OPEN}, seals for
 * review on {@link #close}, and then either {@link #promote promotes} every item into the release layout or
 * {@link #drop drops} the lot. The state machine is enforced here - staging into a sealed repository, promoting an
 * open one, or dropping a terminal one each throw - while the actual storage is the {@link StagingBackend}'s
 * concern. The compliance gate is the intended reviewer between close and promote, but staging does not require it,
 * so a deployment can gate or not.
 */
public final class StagingRepository {

    private final String id;
    private final StagingBackend backend;
    private final List<StagedItem> items;
    private StagingState state;

    public StagingRepository(String id, StagingBackend backend) {
        this.id = id;
        this.backend = backend;
        this.items = new ArrayList<>();
        this.state = StagingState.OPEN;
    }

    public String id() {
        return id;
    }

    public StagingState state() {
        return state;
    }

    public List<StagedItem> items() {
        return List.copyOf(items);
    }

    public void stage(StagedItem item) {
        if (state != StagingState.OPEN) {
            throw new IllegalStateException("Cannot stage into a " + state + " repository");
        }
        items.add(item);
    }

    public void close() {
        if (state != StagingState.OPEN) {
            throw new IllegalStateException("Only an open repository can be closed, was " + state);
        }
        state = StagingState.CLOSED;
    }

    public void promote() throws IOException {
        if (state != StagingState.CLOSED) {
            throw new IllegalStateException("Only a closed repository can be promoted, was " + state);
        }
        for (StagedItem item : items) {
            backend.promote(id, item);
        }
        state = StagingState.PROMOTED;
    }

    public void drop() throws IOException {
        if (state == StagingState.PROMOTED || state == StagingState.DROPPED) {
            throw new IllegalStateException("A " + state + " repository cannot be dropped");
        }
        backend.discard(id);
        state = StagingState.DROPPED;
    }
}
