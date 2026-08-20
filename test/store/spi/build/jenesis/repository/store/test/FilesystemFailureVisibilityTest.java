package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract clause 6 on the default backend: <b>a failure to look is not an absence</b> (D-071).
 *
 * <p>The three object-store backends have always complied - each re-throws anything that is not a 404 - but the
 * filesystem answered a confident {@code false} / empty for a permission refusal, an I/O error or a stale mount,
 * because {@code Files.isRegularFile} swallows every {@link IOException} internally and {@code list} / {@code page}
 * caught one and returned nothing. Since it is the default backend, every fail-closed screen in the product was
 * fiction on a filesystem deployment: an unreadable {@code withheld/<hash>} marker reads as "not withheld" and the
 * held bytes serve, an unreadable {@code gc/condemned/<hash>} marker means a re-publish never un-condemns its blob
 * and the next sweep deletes live content, and an unreadable reference shard directory marks every blob under one
 * leading byte unreferenced.
 *
 * <p>The two answers that must stay absent are pinned here too, because they are what made the swallow tempting: a
 * key with nothing at it, and - the one that is easy to get wrong - a key <em>below</em> a stored object, which is
 * ordinary in the {@code publish/} namespace where a pointer and a path beneath it coexist.
 */
class FilesystemFailureVisibilityTest {

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private final List<Path> sealed = new ArrayList<>();

    /** Make a container unreadable, the cheapest faithful stand-in for the permission refusal, disconnected mount or
     *  I/O error clause 6 is about: every probe beneath it fails at the syscall exactly as it would there. */
    private Path unreadable(String name) throws IOException {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "an unreadable container is only expressible on a POSIX filesystem");
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "root is not refused by file permissions, so the failure cannot be provoked");
        Path directory = Files.createDirectories(root.resolve(name));
        Files.write(directory.resolve("object"), "held".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(directory, Set.of());
        sealed.add(directory);
        return directory;
    }

    /** Give the sealed containers their permissions back, or JUnit's own temp-directory cleanup cannot descend. */
    @AfterEach
    void restore() throws IOException {
        for (Path directory : sealed) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void a_probe_that_could_not_look_never_answers_absent() throws IOException {
        var _ = unreadable("sealed");
        ArtifactStore store = store();

        assertThatThrownBy(() -> store.exists("sealed/object"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("sealed/object");
        assertThatThrownBy(() -> store.readVersioned("sealed/object"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void an_enumeration_that_could_not_look_never_answers_drained() throws IOException {
        var _ = unreadable("sealed");
        ArtifactStore store = store();

        assertThatThrownBy(() -> store.list("sealed"))
                .isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.page("sealed", "", 10, _ -> {
        })).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void nothing_stored_at_a_key_is_still_a_plain_absence() throws IOException {
        ArtifactStore store = store();

        assertThat(store.exists("nothing/here")).isFalse();
        assertThat(store.readVersioned("nothing/here")).isEmpty();
        assertThat(store.list("nothing")).isEmpty();
        store.page("nothing", "", 10, _ -> Assertions.fail("an absent container has no children"));
    }

    @Test
    void a_key_below_a_stored_object_is_absent_rather_than_a_failure() throws IOException {
        ArtifactStore store = store();
        store.write("publish/maven/g/a/1/a-1.jar", new ByteArrayInputStream("jar".getBytes(StandardCharsets.UTF_8)));

        // ENOTDIR, not a fault: the publish namespace routinely probes a path beneath a pointer, and answering
        // anything but "absent" here would turn every such probe into an error.
        assertThat(store.exists("publish/maven/g/a/1/a-1.jar/below")).isFalse();
        assertThat(store.readVersioned("publish/maven/g/a/1/a-1.jar/below")).isEmpty();
        assertThat(store.list("publish/maven/g/a/1/a-1.jar")).isEmpty();
        store.page("publish/maven/g/a/1/a-1.jar", "", 10, _ -> Assertions.fail("an object has no children"));
    }
}
