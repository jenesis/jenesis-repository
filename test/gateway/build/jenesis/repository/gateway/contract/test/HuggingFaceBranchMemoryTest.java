package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.UpstreamMemory;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.gateway.testkit.FormatDrive.Call;
import static build.jenesis.repository.gateway.testkit.FormatDrive.MemStore;
import static build.jenesis.repository.gateway.testkit.FormatDrive.format;

/**
 * A Hugging Face branch is mutable metadata, so the commit a proxy resolves it to is remembered for
 * {@code jenrepo.cache.upstream-ttl} rather than asked of the upstream on every read: a burst of reads of one branch
 * costs one resolution, a branch that moved upstream is resolved again once the memory forgets it, and another
 * repository asks its own upstream.
 */
class HuggingFaceBranchMemoryTest {

    private static final URI UPSTREAM = URI.create("https://hub.example/");
    private static final String BRANCH = "/huggingface/hf/acme/model/resolve/main/config.json";

    @AfterEach
    void forget() {
        System.clearProperty("jenrepo.cache.upstream-ttl");
        UpstreamMemory.reset();
    }

    /** An upstream that resolves the branch to {@code commit} and counts how often it was asked. */
    private static ProxyFormat.Fetcher hub(AtomicInteger heads, AtomicReference<String> commit) {
        return new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return Optional.empty();
            }

            @Override
            public ProxyFormat.Fetcher beside() {
                return this;
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return Optional.empty();
            }

            @Override
            public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) {
                heads.incrementAndGet();
                return Optional.of(new ProxyFormat.Head(200, Map.of("X-Repo-Commit", commit.get())));
            }
        };
    }

    private static String kept(RepositoryFormat hf, MemStore repository, ProxyFormat.Fetcher hub) throws IOException {
        return ((ProxyFormat) hf).keptAs(new Call("GET", BRANCH), repository, UPSTREAM, hub).orElseThrow();
    }

    @Test
    void a_branch_is_resolved_once_per_ttl_and_again_once_forgotten() throws IOException {
        System.setProperty("jenrepo.cache.upstream-ttl", "PT6H");
        UpstreamMemory.reset();
        RepositoryFormat hf = format("huggingface");
        MemStore repository = new MemStore();
        AtomicInteger heads = new AtomicInteger();
        AtomicReference<String> commit = new AtomicReference<>("a".repeat(40));
        ProxyFormat.Fetcher hub = hub(heads, commit);

        assertThat(kept(hf, repository, hub)).contains("/resolve/" + "a".repeat(40) + "/");
        commit.set("b".repeat(40));
        assertThat(kept(hf, repository, hub)).as("the remembered commit, though the branch moved upstream")
                .contains("/resolve/" + "a".repeat(40) + "/");
        assertThat(heads).as("two reads, one resolution").hasValue(1);

        kept(hf, new MemStore(), hub);
        assertThat(heads).as("another repository asks its upstream itself").hasValue(2);

        UpstreamMemory.node().clear();
        assertThat(kept(hf, repository, hub)).as("once forgotten, the branch resolves to where it moved")
                .contains("/resolve/" + "b".repeat(40) + "/");
    }

    @Test
    void with_the_memory_off_every_read_resolves_the_branch() throws IOException {
        System.setProperty("jenrepo.cache.upstream-ttl", "0");
        UpstreamMemory.reset();
        RepositoryFormat hf = format("huggingface");
        MemStore repository = new MemStore();
        AtomicInteger heads = new AtomicInteger();
        ProxyFormat.Fetcher hub = hub(heads, new AtomicReference<>("a".repeat(40)));

        kept(hf, repository, hub);
        kept(hf, repository, hub);

        assertThat(heads).hasValue(2);
    }
}
