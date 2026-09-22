package build.jenesis.repository.staging;

/**
 * The lifecycle of a staging repository. {@code OPEN} accepts deploys; {@code CLOSED} is sealed for review, no
 * longer accepting deploys but not yet released; {@code PROMOTED} has had its artifacts re-pointed into the
 * release layout; {@code DROPPED} has been discarded. Both {@code PROMOTED} and {@code DROPPED} are terminal.
 */
public enum StagingState {

    OPEN,

    CLOSED,

    PROMOTED,

    DROPPED
}
