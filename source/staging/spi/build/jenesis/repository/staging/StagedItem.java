package build.jenesis.repository.staging;

/**
 * One artifact held in a staging repository: the release-layout {@code path} it will occupy when promoted (for
 * example {@code /maven/org/example/lib/1.0/lib-1.0.jar}) and the content {@code blob} it points at. Because the
 * blob is content-addressed and already stored, promotion only has to publish the path against it.
 */
public record StagedItem(String path, String blob) {
}
