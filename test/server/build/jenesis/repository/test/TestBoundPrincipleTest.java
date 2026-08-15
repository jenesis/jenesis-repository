package build.jenesis.repository.test;

import module java.base;

import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard for <b>D-118</b>: a test cell's bound must be <em>progress</em>, not a reading of the wall clock.
 *
 * <p>The shape this exists to keep out: a cell drives workers for {@code N} milliseconds and then asserts over
 * whatever accumulated. On a cold JVM under a parallel build nothing completes, and the assertion is made about an
 * empty world - {@code Requests received: []}, a verdict that says nothing about the property either way. The redirect
 * guard that produced that line passed standalone under twelve synthetic busy loops and failed twice in a row once the
 * gateway split trebled its wall clock, which is the whole hazard in one sentence: a time bound does not get stricter
 * on a slow machine, it gets <em>weaker</em>, and the module-graph wave is deliberately making machines busier.
 *
 * <p>So the rule is stated at the point the hazard enters the code, not at the point it bites: <b>a test source may
 * not derive a deadline from a clock read</b> - {@code Instant.now().plus...}, {@code System.nanoTime() + ...},
 * {@code System.currentTimeMillis() + ...}. Drive until the observable thing happens instead, capped by a generous
 * attempt count, and fail by name when the attempts run out. A machine too busy then makes the wait longer rather than
 * hollowing out the claim.
 *
 * <p>Deliberately <em>not</em> a ban on reading the clock. A cell that <em>measures</em> elapsed time - "a stuck
 * transport must not hold the shared forwarding lease past its declared in-band ceiling" - reads
 * {@code System.nanoTime()} before and after and asserts on the difference; the duration is its subject, not its
 * bound, and no offset is added to a clock read to make a deadline. Timestamp freshness checks
 * ({@code isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5))}) are likewise data, not control flow: the
 * scan looks only at deadlines <em>bound into a local</em>, which is what a loop can then consult.
 *
 * <p>The allowlist is a burn-down list keyed by test-relative path with a one-line reason, and it is <b>shrink-only</b>
 * - an entry whose deadline is gone fails the hygiene leg below, so a conversion cannot leave a stale grant behind.
 * Scope is this repository's {@code test/**}; the downstream edition carries the same guard over its own tree - the
 * two trees hit the same hazard, so the rule is stated in both rather than in whichever one happened to notice.
 */
class TestBoundPrincipleTest {

    /**
     * A local bound to <em>a clock read plus an offset</em> - the form a loop can then consult, and therefore the
     * form that turns a clock into a bound. Both halves matter: the offset is what separates a deadline from a
     * timestamp ({@code Instant started = Instant.now()} is a measurement), and the binding into a local is what
     * separates a bound from data ({@code assertThat(at).isBetween(..., Instant.now().plusSeconds(5))} is a
     * freshness range, and a future {@code Instant} handed to a fixture is a value, not a verdict).
     */
    private static final Pattern DEADLINE_LOCAL = Pattern.compile(
            "(?m)^[^\\n;]*\\b(?:Instant|long|Duration|LocalDateTime|OffsetDateTime|ZonedDateTime)\\s+"
                    + "(\\w+)\\s*=\\s*[^;\\n]*?(?:Instant\\.now\\(\\)\\s*\\.\\s*plus"
                    + "|System\\.(?:nanoTime|currentTimeMillis)\\(\\)\\s*\\+"
                    + "|\\+\\s*System\\.(?:nanoTime|currentTimeMillis)\\(\\))");

    /** The second half: the local has to reach a loop or branch condition before it is a <em>bound</em> on anything.
     *  Built per name so a future instant that is only ever passed as an argument stays what it is - data. */
    private static Pattern consultedBy(String local) {
        return Pattern.compile("\\b(?:while|for|if)\\s*\\([^;{]{0,300}?\\b" + Pattern.quote(local) + "\\b");
    }

    /**
     * Cells whose bound is legitimately a clock, keyed by test-relative path, each with the reason it cannot be a
     * progress bound. Shrink-only: convert one and delete its line; never add one without a reason a reviewer can
     * disagree with.
     *
     * <p>The line these entries sit on: <b>if the test drives the thing it is waiting for, the bound is attempts; if
     * it only watches something outside the build come up, the bound is time.</b> A container's startup is not made
     * faster by polling it more often, so "attempts" and "seconds" mean the same thing there and seconds are the
     * honest way to say it - and each of these throws, naming the service, when its patience runs out.
     */
    private static final Map<String, String> DEADLINE_ALLOWLIST = Map.ofEntries(
            Map.entry("store/contract/build/jenesis/repository/store/contract/test/Containers.java",
                    "waits for a store container outside the build to answer; polling harder does not start it sooner"),
            Map.entry("store/azure/build/jenesis/repository/store/azure/test/AzureArtifactStoreTest.java",
                    "waits for the Azurite container to answer; the bound is how long we wait on an external process"),
            Map.entry("store/azure/build/jenesis/repository/store/azure/test/AzureArtifactStoreProviderTest.java",
                    "waits for the Azurite container to answer; the bound is how long we wait on an external process"),
            Map.entry("store/gcs/build/jenesis/repository/store/gcs/test/GcsArtifactStoreTest.java",
                    "waits for the fake-GCS container to answer; the bound is how long we wait on an external process"),
            Map.entry("store/s3/build/jenesis/repository/store/s3/test/S3ArtifactStoreTest.java",
                    "waits for the MinIO container to answer; the bound is how long we wait on an external process"),
            Map.entry("store/s3/build/jenesis/repository/store/s3/test/S3ArtifactStoreProviderTest.java",
                    "waits for the MinIO container to answer; the bound is how long we wait on an external process"),
            Map.entry("server/build/jenesis/repository/test/TestBoundPrincipleTest.java",
                    "this guard's own negative control is a deadline loop by construction - that is what it recognises"));

    /** Non-vacuity pin: a file the scan must classify, so a broken walk or a broken pattern cannot pass as "clean". */
    private static final String NEGATIVE_CONTROL = """
            class Example {
                void wait() {
                    Instant deadline = Instant.now().plusSeconds(30);
                    while (Instant.now().isBefore(deadline)) {
                        sleep();
                    }
                }
            }
            """;

    /** Its counterpart: an attempt-bounded wait that reads no clock at all - the shape the rule asks for. */
    private static final String POSITIVE_CONTROL = """
            class Example {
                void wait() throws IOException {
                    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                        if (happened()) {
                            return;
                        }
                        pause();
                    }
                    throw new AssertionError("never happened across " + ATTEMPTS + " attempts");
                }
            }
            """;

    @Test
    void no_test_cell_bounds_itself_by_a_clock_reading() throws IOException {
        Map<String, List<String>> found = deadlines(testRoot());

        List<String> offenders = found.entrySet().stream()
                .filter(entry -> !DEADLINE_ALLOWLIST.containsKey(entry.getKey()))
                .map(entry -> "  - " + entry.getKey() + ": " + String.join("; ", entry.getValue()))
                .sorted()
                .toList();

        assertThat(offenders)
                .as("these test cells bind a deadline from a clock read, so a machine too busy to make progress "
                        + "shortens the observation instead of lengthening the wait, and whatever assertion follows "
                        + "is made about a world the cell never drove. Drive until the observable thing happens, "
                        + "capped by a generous attempt count, and fail by name when the attempts run out - or add a "
                        + "reasoned DEADLINE_ALLOWLIST entry saying why a clock is the honest bound here.%n%s",
                        String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    @Test
    void the_scan_recognises_a_deadline_and_leaves_an_attempt_bound_alone() {
        assertThat(deadlinesIn(NEGATIVE_CONTROL))
                .as("the scan must classify the canonical deadline loop as a deadline, or the guard above passes "
                        + "vacuously over a tree that has stopped matching its own pattern")
                .containsExactly("deadline");
        assertThat(deadlinesIn(POSITIVE_CONTROL))
                .as("and must leave the attempt-bounded shape the rule asks for alone")
                .isEmpty();
        assertThat(deadlinesIn("Instant started = Instant.now();\nassertThat(elapsed).isLessThan(CEILING);\n"))
                .as("a cell that MEASURES elapsed time reads the clock on purpose - the duration is its subject, "
                        + "not its bound - and must not be flagged")
                .isEmpty();
        assertThat(deadlinesIn("assertThat(at).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));\n"))
                .as("a timestamp freshness range is data, not control flow")
                .isEmpty();
    }

    @Test
    void every_allowlisted_deadline_still_exists() throws IOException {
        Map<String, List<String>> found = deadlines(testRoot());

        List<String> stale = DEADLINE_ALLOWLIST.keySet().stream()
                .filter(file -> !found.containsKey(file))
                .map(file -> "  - " + file)
                .sorted()
                .toList();

        assertThat(stale)
                .as("these files no longer bind a deadline from a clock read, so their grant masks nothing and can "
                        + "only rot into cover for a future one - delete the entry with the conversion that earned "
                        + "it.%n%s", String.join(System.lineSeparator(), stale))
                .isEmpty();
    }

    // --- the scan -------------------------------------------------------------------------------------------------

    /** Every test source that binds a deadline into a local, mapped to the names it bound. */
    private static Map<String, List<String>> deadlines(Path root) throws IOException {
        Map<String, List<String>> found = new TreeMap<>();
        try (Stream<Path> sources = Files.walk(root)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> bound = deadlinesIn(read(source));
                if (!bound.isEmpty()) {
                    found.put(root.relativize(source).toString().replace(File.separatorChar, '/'), bound);
                }
            }
        }
        return found;
    }

    /** The deadline locals a source binds <em>and then consults in a condition</em>, comments stripped so a javadoc
     *  example is never an offender. */
    private static List<String> deadlinesIn(String source) {
        String code = stripComments(source);
        Set<String> bound = new TreeSet<>();
        Matcher matcher = DEADLINE_LOCAL.matcher(code);
        while (matcher.find()) {
            String local = matcher.group(1);
            if (consultedBy(local).matcher(code).find()) {
                bound.add(local);
            }
        }
        return List.copyOf(bound);
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\n]*", " ");
    }

    /** Charset-tolerant, because a fixture elsewhere in the tree carries non-UTF-8 bytes and a structural scan must
     *  never die on one. */
    private static String read(Path source) throws IOException {
        try {
            return Files.readString(source);
        } catch (MalformedInputException notUtf8) {
            return new String(Files.readAllBytes(source), StandardCharsets.ISO_8859_1);
        }
    }

    private static Path testRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("test")) && Files.isDirectory(dir.resolve("build/jenesis"))) {
                return dir.resolve("test");
            }
        }
        throw new AssertionError("could not locate the downstream repo root (an ancestor holding test/ beside "
                + "build/jenesis) from working directory " + start + " - this structural check must run from the "
                + "repository tree so it can read the test sources");
    }
}
