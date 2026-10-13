package build.jenesis.repository.store.testkit;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.StoredListing;

/**
 * Finishes the store writes a test left queued before JUnit deletes the directories the test's stores live in.
 *
 * <p>A publish, a hold or a lifecycle mark queues work that lands after the call returns: a listing's derived twins
 * on {@link StoredListing}'s deferred executor and a counter's delta on {@link StoredCounter}'s flusher. Over a
 * filesystem store under a {@link TempDir}, one landing while JUnit deletes that directory recreates a folder in
 * it, and the deletion fails the test with {@code Failed to delete temp directory} although every assertion held.
 * Settling both after each test and after each class - before the method's and the class's temporary directories are
 * closed - leaves nothing to land.
 *
 * <p>Registered for every test on a module path carrying this kit, through JUnit's extension autodetection, which
 * the test processes switch on with {@code junit.jupiter.extensions.autodetection.enabled}. An autodetected
 * extension is registered first, so its after-callbacks run after the test's own {@code @AfterEach} and
 * {@code @AfterAll} methods, which may themselves publish.
 */
public final class SettleDeferredWrites implements AfterEachCallback, AfterAllCallback {

    public SettleDeferredWrites() {
    }

    @Override
    public void afterEach(ExtensionContext context) {
        settle();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        settle();
    }

    private static void settle() {
        StoredListing.settle();
        StoredCounter.settle();
    }
}
