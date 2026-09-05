package build.jenesis.repository.store.filesystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filesystem store's compare-and-set under two <em>processes</em> on one directory - two nodes on a shared mount,
 * the cheapest way to run a fleet. Within one JVM the store's striped monitors serialize the check-then-move that its
 * last-modified-and-checksum token amounts to, and the in-process race test passes on their account; two processes
 * share no monitor, so both can pass the check with one token and both land, and the second rename silently discards
 * the first write. Two JVMs each increment one counter through read-then-compare-and-set; every increment retried
 * until it lands must be counted, so the final value is the sum. Anything less is a lost update.
 */
class TwoProcessCompareAndSetTest {

    private static final int INCREMENTS = 3000;

    @Test
    void two_processes_incrementing_one_key_lose_no_increment(@TempDir Path root) throws Exception {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        assertThat(store.writeVersioned("m/counter", "0".getBytes(StandardCharsets.UTF_8), null)).isTrue();

        Process first = incrementer(root);
        Process second = incrementer(root);
        String said = output(first) + output(second);
        assertThat(first.exitValue()).as("the first process ran to completion: %s", said).isZero();
        assertThat(second.exitValue()).as("the second process ran to completion: %s", said).isZero();

        String counter = new String(store.readVersioned("m/counter").orElseThrow().content(), StandardCharsets.UTF_8);
        assertThat(Integer.parseInt(counter))
                .as("every increment either process made landed; a shortfall is updates one process lost to the "
                        + "other between its compare and its move (%s)", said)
                .isEqualTo(2 * INCREMENTS);
    }

    /** The same two processes, one over the directory and one over a symbolic link to it - two nodes mounting one
     *  share at different paths. The stripe lock is chosen from the key relative to the root; taken from the absolute
     *  path, the two would hold different locks for one key and the first leg's shortfall would be back. */
    @Test
    void two_processes_naming_one_directory_differently_lose_no_increment(@TempDir Path scratch) throws Exception {
        Path root = Files.createDirectories(scratch.resolve("shared"));
        Path alias = Files.createSymbolicLink(scratch.resolve("alias"), root);
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        assertThat(store.writeVersioned("m/counter", "0".getBytes(StandardCharsets.UTF_8), null)).isTrue();

        Process first = incrementer(root);
        Process second = incrementer(alias);
        String said = output(first) + output(second);
        assertThat(first.exitValue()).as("the first process ran to completion: %s", said).isZero();
        assertThat(second.exitValue()).as("the second process ran to completion: %s", said).isZero();

        String counter = new String(store.readVersioned("m/counter").orElseThrow().content(), StandardCharsets.UTF_8);
        assertThat(Integer.parseInt(counter))
                .as("every increment landed although the two processes named the directory differently (%s)", said)
                .isEqualTo(2 * INCREMENTS);
    }

    /** A second JVM on this one's module path, running {@link Incrementer} over {@code root}. */
    private static Process incrementer(Path root) throws IOException {
        String java = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException("the running JVM's command is not known"));
        String modulePath = System.getProperty("jdk.module.path");
        assertThat(modulePath).as("the test runs on the module path, which the child inherits").isNotBlank();
        return new ProcessBuilder(java, "--module-path", modulePath, "--module",
                "build.jenesis.repository.store.filesystem.test/" + Incrementer.class.getName(),
                root.toString(), Integer.toString(INCREMENTS))
                .redirectErrorStream(true)
                .start();
    }

    private static String output(Process process) throws Exception {
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("the process exits; it said: %s", output).isTrue();
        return output;
    }
}
