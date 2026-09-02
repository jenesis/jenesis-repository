package build.jenesis.repository.walk.test;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * A minimal in-memory {@link ArtifactStore} for driving the traversal primitives over a synthetic key tree of any
 * shape without touching disk - the pathological shapes (a 20 000-segment-deep key, a container wider than any real
 * one, a name a real write path would have rejected) that a filesystem cannot hold. Objects live in a sorted set,
 * immediate-child enumeration is derived from it, and only the {@code exists} / {@code page} / {@code list} the
 * descent consumes are implemented; every other operation is unused here and throws.
 *
 * <p>Deliberately not a contract-complete store double: it proves traversal shape, never persistence, durability or
 * concurrency. Suites that need those drive a real {@code FilesystemArtifactStore}.
 */
final class MemoryStore implements ArtifactStore {

    private final NavigableSet<String> keys = new TreeSet<>();

    /** Seed one stored leaf key; its ancestors become containers implicitly, exactly as in a real key layout. */
    void seed(String key) {
        keys.add(key);
    }

    @Override
    public boolean exists(String key) {
        return keys.contains(key);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        String base = prefix.isEmpty() ? "" : prefix + "/";
        NavigableSet<String> names = new TreeSet<>();
        for (String key : keys) {
            if (!base.isEmpty() && !key.startsWith(base)) {
                continue;
            }
            String rest = key.substring(base.length());
            if (rest.isEmpty()) {
                continue;
            }
            int slash = rest.indexOf('/');
            names.add(slash < 0 ? rest : rest.substring(0, slash));
        }
        int emitted = 0;
        for (String name : names) {
            if (name.compareTo(startAfter) <= 0) {
                continue;
            }
            if (emitted++ >= limit) {
                break;
            }
            consumer.accept(name);
        }
    }

    @Override
    public List<String> list(String prefix) {
        List<String> names = new ArrayList<>();
        page(prefix, "", Integer.MAX_VALUE, names::add);
        return names;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return this;
    }

    @Override
    public Optional<Versioned> readVersioned(String key) {
        return Optional.empty();
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long size(String key) {
        return exists(key) ? 0L : -1L;
    }

    @Override
    public void delete(String key) {
        keys.remove(key);
    }

    @Override
    public void read(String key, OutputStream out) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream open(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(String key, InputStream in) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String writeBlob(InputStream in) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}
