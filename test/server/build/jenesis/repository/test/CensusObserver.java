package build.jenesis.repository.test;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

/**
 * The ingress census's after-commit probe: a discovered {@link PublicationObserver}, inert for every path that does not
 * carry the distinctive {@code ingress-census} token, which records - at the exact instant it is notified - whether the
 * route's serving key already exists in the store. That is the machine-checkable half of "the edge notifies only after
 * visibility commits": an observer that fires while its artifact's serving pointer is still absent would record
 * {@code false} and fail the census, whatever the source order looks like.
 *
 * <p>The serving key is route-specific (a {@code publish/} pointer for the deploy and import edges, an {@code oci/}
 * tag object for the OCI choke point), so the census {@linkplain #expect installs} it before driving each route rather
 * than this fixture guessing a namespace.
 */
public final class CensusObserver implements PublicationObserver {

    static final String MARKER = "ingress-census";

    private static final List<Notification> NOTIFICATIONS = new CopyOnWriteArrayList<>();
    private static volatile String servingKey;

    /** One notification: the observed path, and whether the route's serving key was already stored when it fired. */
    record Notification(String path, String hash, boolean servingKeyPresent) {
    }

    /** Arm the census for one route: forget earlier notifications and name the key that makes that route visible. */
    static void expect(String servingKey) {
        NOTIFICATIONS.clear();
        CensusObserver.servingKey = servingKey;
    }

    static List<Notification> notifications() {
        return List.copyOf(NOTIFICATIONS);
    }

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        String key = servingKey;
        if (artifact.path() == null || !artifact.path().contains(MARKER) || key == null) {
            return;
        }
        NOTIFICATIONS.add(new Notification(artifact.path(), artifact.hash(), store.exists(key)));
    }
}
