package build.jenesis.repository.store.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.ReadMemo;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request-scoped read memo: a repeated {@code readVersioned} of one key within an operation asks the store once,
 * absence included; any write through the memo forgets the key first, so a compare-and-set never acts on a
 * remembered token and a read-modify-write loop lands against a peer's concurrent write with one lost try and no
 * lost update; a scoped view keeps its own memo and the delegate's identity; and past the capacity a read is
 * answered and not kept.
 */
class ReadMemoTest {

    @TempDir
    Path root;

    private ArtifactStore raw() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Optional<ArtifactStore.Versioned> read) {
        return new String(read.orElseThrow().content(), StandardCharsets.UTF_8);
    }

    @Test
    void a_repeated_read_asks_the_store_once_and_a_write_forgets_the_key() throws IOException {
        ArtifactStore raw = raw();
        raw.writeVersioned("meta/x", bytes("v1"), null);
        FaultInjectingStore counting = FaultInjectingStore.wrap(raw);
        ArtifactStore memo = ReadMemo.over(counting);

        assertThat(text(memo.readVersioned("meta/x"))).isEqualTo("v1");
        assertThat(text(memo.readVersioned("meta/x"))).isEqualTo("v1");
        assertThat(memo.version("meta/x")).isPresent();
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED)).as("three reads, one store call").isEqualTo(1);

        assertThat(memo.readVersioned("meta/absent")).isEmpty();
        assertThat(memo.readVersioned("meta/absent")).isEmpty();
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED)).as("absence is remembered too").isEqualTo(2);

        Object token = memo.readVersioned("meta/x").orElseThrow().token();
        assertThat(memo.writeVersioned("meta/x", bytes("v2"), token)).isTrue();
        assertThat(text(memo.readVersioned("meta/x"))).as("a write forgets the key, so the read is the store's")
                .isEqualTo("v2");
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED)).isEqualTo(3);

        memo.delete("meta/absent");
        memo.readVersioned("meta/absent");
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED)).as("a delete forgets the key too").isEqualTo(4);
    }

    @Test
    void a_compare_and_set_never_acts_on_a_remembered_token_so_a_peers_write_is_never_lost() throws IOException {
        ArtifactStore raw = raw();
        raw.writeVersioned("meta/x", bytes("v1"), null);
        FaultInjectingStore counting = FaultInjectingStore.wrap(raw);
        ArtifactStore memo = ReadMemo.over(counting);
        assertThat(text(memo.readVersioned("meta/x"))).isEqualTo("v1");

        // A peer moves the key through another instance over the same backing, unseen by this memo.
        Object peerSaw = raw.readVersioned("meta/x").orElseThrow().token();
        assertThat(raw.writeVersioned("meta/x", bytes("peer"), peerSaw)).isTrue();
        assertThat(text(memo.readVersioned("meta/x"))).as("a plain read within the operation answers what it last saw")
                .isEqualTo("v1");

        long lostBefore = Retries.lostToPeer();
        String landed = Retries.decide(memo, "meta/x", current ->
                Retries.Verdict.write(bytes(text(current) + "+mine"), text(current) + "+mine"));

        assertThat(landed).as("the loop re-read the key after the remembered token was refused").isEqualTo("peer+mine");
        assertThat(text(raw.readVersioned("meta/x"))).as("the peer's write is kept, never overwritten from a memo")
                .isEqualTo("peer+mine");
        assertThat(Retries.lostToPeer() - lostBefore).as("exactly one lost try: the one that carried the remembered token")
                .isEqualTo(1);
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED))
                .as("the first read, then the one fresh read the retry made; the lost try read nothing").isEqualTo(2);
    }

    @Test
    void a_scoped_view_keeps_its_own_memo_and_answers_the_delegates_identity() throws IOException {
        ArtifactStore raw = raw();
        raw.scope("t").scope("r").writeVersioned("meta/x", bytes("scoped"), null);
        FaultInjectingStore counting = FaultInjectingStore.wrap(raw);
        ArtifactStore memo = ReadMemo.over(counting);
        ArtifactStore scoped = memo.scope("t").scope("r");

        assertThat(ReadMemo.is(scoped)).isTrue();
        assertThat(scoped.identity()).isEqualTo(raw.scope("t").scope("r").identity());
        assertThat(memo.identity()).isEqualTo(raw.identity());
        assertThat(text(scoped.readVersioned("meta/x"))).isEqualTo("scoped");
        assertThat(memo.readVersioned("meta/x")).as("the root's memo is not the subspace's").isEmpty();
        assertThat(ReadMemo.over(memo)).as("over() never stacks a second memo").isSameAs(memo);
    }

    @Test
    void past_the_capacity_a_read_is_answered_and_not_kept() throws IOException {
        FaultInjectingStore counting = FaultInjectingStore.wrap(raw());
        ArtifactStore memo = ReadMemo.over(counting);
        int beyond = 520;
        for (int index = 0; index < beyond; index++) {
            memo.readVersioned("meta/" + index);
            memo.readVersioned("meta/" + index);
        }
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED))
                .as("the first 512 keys cost one read each, the rest two").isEqualTo(512 + 2 * (beyond - 512));

        ((ReadMemo) memo).forget();
        memo.readVersioned("meta/0");
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED)).as("forgotten, so read again")
                .isEqualTo(512 + 2 * (beyond - 512) + 1);
    }
}
