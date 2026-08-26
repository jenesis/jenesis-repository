package build.jenesis.repository.store.s3compatible;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;import software.amazon.awssdk.services.s3.S3Client;

/**
 * The half of an object-store backend that is the same whichever S3-compatible service is behind it.
 *
 * <p>Reading, listing, scanning and paging are ordinary S3 API calls and behave identically on AWS S3, on Google
 * Cloud Storage's S3-compatible XML API, and on MinIO. What differs between services is the version token and how
 * a conditional write is expressed - S3 uses {@code If-Match} / {@code If-None-Match} on the write, GCS honours
 * those only on reads and takes {@code x-goog-if-generation-match} instead - so that half stays with each backend.
 *
 * <p>The two backends carried both halves each. The listing side alone was fourteen methods and a hundred and
 * sixty-five lines, duplicated exactly, {@code pageListed} among them at fifty-six lines: continuation tokens,
 * delimiters, prefix arithmetic and the page-size bound. A paging fault found and fixed against one service would
 * have been left standing in the other, and nothing would have said so.
 */
public abstract class S3CompatibleArtifactStore implements ArtifactStore {

    protected final S3Client s3;
    protected final String bucket;
    protected final String keyPrefix;

    protected S3CompatibleArtifactStore(S3Client s3, String bucket, String keyPrefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix;
    }

    /** The storage prefix of a listing container - the scope's key prefix and the normalised container name with its
     *  trailing delimiter - so a caller's {@code a/b/} and {@code a/b} ask the service for one prefix. */
    private String base(String prefix) {
        String container = ArtifactStore.container(prefix);
        return keyPrefix + (container.isEmpty() ? "" : container + "/");
    }

    private static String name(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        // The names-only view of pageListed: the ordering rules below are subtle enough that a second copy
        // would drift, so this form derives from that one rather than repeating it.
        pageListed(prefix, startAfter, limit, listed -> consumer.accept(name(listed.key())));
    }

    public InputStream open(String key) throws IOException {
        try {
            return s3.getObject(b -> b.bucket(bucket).key(keyPrefix + key));
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    public void delete(String key) throws IOException {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(keyPrefix + key));
        } catch (S3Exception e) {
            throw new IOException("Could not delete " + key, e);
        }
    }

    public boolean exists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key));
            return true;
        } catch (S3Exception e) {
            // Only a 404 means absent; a throttle or auth failure must fail the request loudly, or a published
            // artifact silently turns into a miss (served as 404) for as long as the backend misbehaves.
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    public long size(String key) throws IOException {
        try {
            return s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key)).contentLength();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return -1L;
            }
            throw new IOException("Could not size " + key, e);
        }
    }

    /** Whether {@code name} may not be paged out yet at stream position {@code relative}: a proper prefix of it
     *  whose next character sorts below {@code '/'} could still arrive as a grouped prefix (its container key
     *  {@code prefix + "/"} sorts at or past the position), and that shorter child name must page first. */
    private static boolean held(String name, String relative) {
        for (int index = 1; index < name.length(); index++) {
            if (name.charAt(index) < '/' && relative.compareTo(name.substring(0, index) + "/") <= 0) {
                return true;
            }
        }
        return false;
    }

    /** A child as the listing saw it. {@code object} is null for a grouped prefix - a container - which reports no
     *  size or age because it has none; both halves of a leaf's metadata ride along in the response already. */
    private static Listed listed(String prefix, String name, S3Object object) {
        String container = ArtifactStore.container(prefix);
        String key = container.isEmpty() ? name : container + "/" + name;
        if (object == null) {
            return Listed.of(key);
        }
        return Listed.of(key,
                object.size() == null ? 0L : object.size(),
                object.lastModified() == null ? Instant.EPOCH : object.lastModified());
    }

    public void read(String key, OutputStream out) throws IOException {
        try {
            if (out instanceof ArtifactStore.RangedSink ranged) {
                String range = "bytes=" + ranged.offset() + "-" + (ranged.offset() + ranged.length() - 1);
                try (ResponseInputStream<GetObjectResponse> in = s3.getObject(
                        b -> b.bucket(bucket).key(keyPrefix + key).range(range))) {
                    in.transferTo(ranged.sink());
                }
            } else {
                try (ResponseInputStream<GetObjectResponse> in = s3.getObject(b -> b.bucket(bucket).key(keyPrefix + key))) {
                    in.transferTo(out);
                }
            }
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    public List<String> list(String prefix) {
        String base = base(prefix);
        TreeSet<String> names = new TreeSet<>();
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(base).delimiter("/"))) {
            page.commonPrefixes().forEach(common -> {
                String name = common.prefix().substring(base.length());
                if (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                if (!name.isEmpty()) {
                    names.add(name);
                }
            });
            for (S3Object object : page.contents()) {
                String name = object.key().substring(base.length());
                if (!name.isEmpty() && name.indexOf('/') < 0) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        if (limit <= 0) {
            throw new IllegalArgumentException("A scan limit must be positive: " + limit);
        }
        String base = base(prefix);
        // No delimiter, and therefore none of page()'s name-order repair: a recursive scan wants every object under
        // the prefix, and without grouped prefixes the listing arrives in exactly the key order this method owes.
        // maxKeys is limit + 1 so the page after the last delivered key is what proves whether more remains, rather
        // than a second request asking.
        long steps = 0;
        long delivered = 0;
        String last = null;
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> {
            b.bucket(bucket).prefix(base).maxKeys(Math.min(ArtifactStore.oneMoreThan(limit), 1000));
            if (startAfter != null && !startAfter.isEmpty()) {
                b.startAfter(keyPrefix + startAfter);
            }
        })) {
            steps++;
            for (S3Object object : page.contents()) {
                if (delivered == limit) {
                    return Scan.truncated(last, delivered, steps);
                }
                String key = object.key().substring(keyPrefix.length());
                // Both halves come out of the listing response, so a scanned page costs exactly its listing calls.
                consumer.accept(Listed.of(key,
                        object.size() == null ? 0L : object.size(),
                        object.lastModified() == null ? Instant.EPOCH : object.lastModified()));
                delivered++;
                last = key;
            }
        }
        return Scan.exhausted(delivered, steps);
    }

    public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        if (limit <= 0) {
            return;
        }
        String base = base(prefix);
        // The stream arrives in raw key order, where a container shows up as a grouped prefix at `name + "/"` -
        // AFTER any sibling whose name extends this one past a character below '/' (the object `app.txt`
        // precedes the grouped prefix `app/`, yet the child `app` must page before `app.txt`). Emitting in
        // child-NAME order therefore parks every name and releases the smallest parked one only once no
        // smaller-named child can still arrive - see held(). A released name at or below startAfter is dropped:
        // the server-side start-after skips the boundary's own object but not a same-named container's grouped
        // prefix, and a prefix-child of the boundary (`app` for `app.txt`) re-arrives here yet was already paged
        // by the call that emitted the boundary itself.
        // Keyed by child NAME, valued by what the listing said about it: a leaf carries its
        // size and age straight off the response, a grouped prefix carries neither because a
        // container has none of its own. Nothing here issues a request to fill them.
        TreeMap<String, Listed> pending = new TreeMap<>();
        int emitted = 0;
        String last = null;
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> {
            b.bucket(bucket).prefix(base).delimiter("/").maxKeys(Math.min(ArtifactStore.oneMoreThan(limit), 1000));
            if (!startAfter.isEmpty()) {
                b.startAfter(base + startAfter);
            }
        })) {
            List<String> ordered = new ArrayList<>();
            Map<String, S3Object> objects = new HashMap<>();
            for (S3Object object : page.contents()) {
                String relative = object.key().substring(base.length());
                if (!relative.isEmpty() && relative.indexOf('/') < 0) {
                    ordered.add(relative);
                    objects.put(relative, object);
                }
            }
            for (CommonPrefix common : page.commonPrefixes()) {
                String relative = common.prefix().substring(base.length());
                if (relative.length() > 1 && relative.indexOf('/') == relative.length() - 1) {
                    ordered.add(relative);
                }
            }
            Collections.sort(ordered);
            for (String relative : ordered) {
                while (!pending.isEmpty() && !held(pending.firstKey(), relative)) {
                    Map.Entry<String, Listed> entry = pending.pollFirstEntry();
                    String name = entry.getKey();
                    if (name.compareTo(startAfter) > 0) {
                        consumer.accept(entry.getValue());
                        last = name;
                        if (++emitted == limit) {
                            return;
                        }
                    }
                }
                String name = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
                if (!name.equals(last)) {
                    // A leaf and a same-named container page as one child; the leaf's metadata
                    // is kept, because that is what a GET of this key resolves to.
                    pending.merge(name, listed(prefix, name, objects.get(relative)),
                            (kept, arriving) -> kept.size().isPresent() ? kept : arriving);
                }
            }
        }
        for (Map.Entry<String, Listed> entry : pending.entrySet()) {
            if (entry.getKey().compareTo(startAfter) > 0) {
                consumer.accept(entry.getValue());
                if (++emitted == limit) {
                    return;
                }
            }
        }
    }

    public List<BatchOutcome> writeBatch(List<BatchWrite> writes) throws IOException {
        // Best-effort, per-key CAS, not a transaction (S3 has no multi-object atomicity): issue the conditional PUTs
        // bounded-parallel so a k-write commit is ~1 round-trip instead of k, classifying each 412/409/404 conflict
        // exactly as writeVersioned. The shared helper keeps input order and never overlaps two writes to one key.
        return ArtifactStore.writeBatchParallel(this, writes);
    }}
