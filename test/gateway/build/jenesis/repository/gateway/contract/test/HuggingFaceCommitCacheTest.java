package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.gateway.testkit.FormatDrive.Call;
import static build.jenesis.repository.gateway.testkit.FormatDrive.MemStore;
import static build.jenesis.repository.gateway.testkit.FormatDrive.format;

/**
 * Pins the Hugging Face {@code X-Repo-Commit} read-first fix. A hosted branch has no upstream commit, so its
 * reported commit is a SHA-1 over the whole sorted {@code (path, blob sha)} file tree - which the download path used to
 * recompute on every single-file GET, reading every file's pointer just to serve one file (O(files) per download).
 *
 * <p>The branch's commit is now precomputed on each upload and cached; the download reads it as an O(1) pointer. Two
 * load-bearing cells, both hermetic (no {@code huggingface_hub} client, no network): a counting store proves a
 * single-file download reads only the requested file's pointer and never re-walks the others, and the reported commit
 * stays a real content sha that moves exactly when the branch content changes.
 */
class HuggingFaceCommitCacheTest {

    private static final String REPO_ID = "acme/model";
    private static final String FILES_PREFIX = "hf/hf/models/" + REPO_ID + "/revs/main/files/";

    @Test
    void a_single_file_download_reads_only_that_file_never_re_walks_the_tree() throws IOException {
        CountingStore store = new CountingStore();
        RepositoryFormat hf = format("huggingface");
        put(hf, store, "config.json", "{\"model_type\":\"bert\"}");
        put(hf, store, "model.safetensors", "weights-weights-weights");
        put(hf, store, "tokenizer.json", "{\"version\":\"1.0\"}");

        // Download one file. With the branch commit precomputed, the download must touch only config.json's own pointer
        // (its size and its ETag hash) - never the pointers of model.safetensors or tokenizer.json.
        store.filePointerReads.clear();
        Call download = new Call("GET", resolve("config.json"));
        hf.handle(download, store);

        assertThat(download.status).isEqualTo(200);
        assertThat(download.responseHeader("X-Repo-Commit"))
                .as("a hosted branch reports a real 40-hex content commit").matches("[0-9a-f]{40}");
        assertThat(new LinkedHashSet<>(store.filePointerReads))
                .as("the download read only the requested file's pointer, not a walk of every file in the revision")
                .containsExactly(FILES_PREFIX + enc("config.json"));
    }

    @Test
    void the_reported_commit_is_stable_and_moves_only_when_the_branch_changes() throws IOException {
        MemStore store = new MemStore();
        RepositoryFormat hf = format("huggingface");
        put(hf, store, "config.json", "{\"a\":1}");

        String first = commit(hf, store);
        assertThat(commit(hf, store)).as("the commit is stable across reads with no upload between them")
                .isEqualTo(first);

        put(hf, store, "extra.txt", "new file changes the tree");
        assertThat(commit(hf, store)).as("a new file changes the content commit").isNotEqualTo(first);
    }

    private static String commit(RepositoryFormat hf, MemStore store) throws IOException {
        Call download = new Call("GET", resolve("config.json"));
        hf.handle(download, store);
        assertThat(download.status).isEqualTo(200);
        return download.responseHeader("X-Repo-Commit");
    }

    private static void put(RepositoryFormat hf, MemStore store, String file, String body) throws IOException {
        Call put = new Call("PUT", resolve(file), body.getBytes(StandardCharsets.UTF_8));
        hf.handle(put, store);
        assertThat(put.status).as("PUT " + file).isEqualTo(201);
    }

    private static String resolve(String file) {
        return "/huggingface/hf/" + REPO_ID + "/resolve/main/" + file;
    }

    /** The format encodes a filepath to a single key segment (percent-encoding the reserved characters); a flat name
     *  has none, so it stores under its own bytes - enough for this test's plain filenames. */
    private static String enc(String file) {
        return file;
    }

    /** A {@link MemStore} that records every {@code readVersioned} of a revision file pointer, so a test can assert a
     *  single-file download touches only the one file it serves rather than re-walking the whole tree. */
    private static final class CountingStore extends MemStore {

        final List<String> filePointerReads = new CopyOnWriteArrayList<>();

        @Override
        public Optional<Versioned> readVersioned(String key) {
            if (key.contains("/revs/main/files/")) {
                filePointerReads.add(key);
            }
            return super.readVersioned(key);
        }
    }
}
