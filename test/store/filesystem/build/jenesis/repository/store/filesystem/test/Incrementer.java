package build.jenesis.repository.store.filesystem.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

/**
 * One process of {@link TwoProcessCompareAndSetTest}: increments the counter at {@code m/counter} under the store
 * root named by the first argument as many times as the second says, each increment a read followed by a
 * compare-and-set that is retried until it lands. Two of these at once over one directory are what the test runs -
 * the shape two nodes on one shared mount produce, which no thread pool inside one JVM can produce, because the
 * store's in-process monitors serialize threads and not processes.
 */
public final class Incrementer {

    private Incrementer() {
    }

    public static void main(String[] arguments) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? arguments[0] : null);
        int increments = Integer.parseInt(arguments[1]);
        long lost = 0;
        for (int each = 0; each < increments; each++) {
            while (true) {
                ArtifactStore.Versioned versioned = store.readVersioned("m/counter").orElseThrow();
                int current = Integer.parseInt(new String(versioned.content(), StandardCharsets.UTF_8));
                if (store.writeVersioned("m/counter", Integer.toString(current + 1).getBytes(StandardCharsets.UTF_8),
                        versioned.token())) {
                    break;
                }
                lost++;
            }
        }
        System.out.println("increments " + increments + " lost-and-retried " + lost);
    }
}
