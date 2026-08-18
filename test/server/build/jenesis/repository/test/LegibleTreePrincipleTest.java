package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard that keeps every file in this repository <b>legible to a text scan</b> (D-120).
 *
 * <h2>The failure this exists to prevent</h2>
 * A single raw {@code NUL} byte inside a string literal makes {@code grep} classify the whole file as <em>binary</em>
 * and drop it from every result - no warning, no error, just a file that never appears. This plan's method is scans and
 * censuses, so that is the absence-as-evidence shape one level up from the code: a census reads "not in the results" as
 * "not an offender" over a file it never read. It is not hypothetical. {@code RpmFormat} carried one, which is why
 * D-069's proxy-leg census counted twelve legs when there were thirteen - and the thirteenth was a genuine offender
 * carrying the same unscreened prologue as eight others.
 *
 * <h2>Why this scan reads bytes rather than asking a tool</h2>
 * <b>The obvious detector has the same blind spot as the thing it detects.</b> {@code git}'s binary heuristic inspects
 * only the first ~8000 bytes of a file, so a {@code NUL} sitting deeper reads as <em>text</em> to git while
 * {@code grep} - which scans the whole buffer - calls the same file binary. {@code RpmFormat}'s own {@code NUL} was at
 * offset ~31400, and a {@code git grep -I} sweep therefore found 4 of the 10 files that carried one. A ratchet built on
 * either tool's heuristic inherits that tool's gap, so this one builds on neither: it opens every file and looks at
 * every byte. {@link #the_scan_finds_a_hostile_byte_wherever_it_sits()} is the proof, and it deliberately probes the
 * offsets on both sides of git's 8000-byte window.
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li><b>Legibility.</b> No file under this repository carries a byte that hides it from a text scan: {@code NUL},
 *       any other C0 control that is not tab/newline/carriage-return, or {@code DEL}. Spell it as a Java escape
 *       instead - {@code "\0"} builds the identical string, so nothing downstream changes.</li>
 *   <li><b>Decodability.</b> Every file decodes as UTF-8 under a <em>reporting</em> decoder. Malformed bytes are
 *       grep's other binary trigger, and a file a scan cannot decode is a file it cannot read.</li>
 *   <li><b>Comprehension, not mere opening.</b> Every {@code .java} file parses into a {@code package} or
 *       {@code module} declaration. Opening a file proves nothing about whether the scan understood it, and a scan
 *       that silently failed to understand a file reports that file clean forever (D-101's shape, copied here).</li>
 *   <li><b>Liveness.</b> The walk is measured against what it must have seen - both source roots, this file, and the
 *       plan it enforces - and the detector is run against synthetic probes at five offsets, so a broken walk or a
 *       broken predicate cannot pass legs 1-3 vacuously.</li>
 *   <li><b>Nothing is skipped in silence.</b> A directory is pruned only by name, with a reason; a symlink is
 *       inventoried rather than stepped over; an unreadable file fails the build instead of being counted clean.</li>
 * </ol>
 *
 * <p><b>Scope is the whole worktree, not just {@code source/}.</b> This repository's own D-120 file was a test
 * ({@code StoreWalkSelfHealTest}), and a census that greps the docs is as blind to a {@code NUL} in a design document
 * as to one in a format. So the roots are everything the worktree holds except the four pruned below.
 *
 * <p><b>The downstream edition carries its own copy of this guard.</b> It cannot be shared from here: downstream
 * consumes core modules as released pins, so a helper landed in a testkit would not reach that repository until a
 * release, and a ratchet that runs on only one side of a two-repository plan is exactly the gap D-120 is about. The two
 * copies differ only in their roots, their liveness anchors and their symlink inventory.
 */
class LegibleTreePrincipleTest {

    /** Directories the walk does not enter, each with the reason it is not repository content. */
    private static final Map<String, String> PRUNED = Map.of(
            ".git", "git's own object database, which is compressed content rather than tracked files",
            ".jenesis", "the build tool submodule - a separate project with its own guards",
            "target", "build output; the compiled bytes of the very files this walk reads",
            "node_modules", "vendored javascript, if a console build ever leaves any behind");

    /** Symlinks this worktree is expected to hold. A walk that steps over an unlisted one is a walk that skipped
     *  something in silence, which is the failure mode this whole suite is about. */
    private static final Map<String, String> SYMLINKS = Map.of(
            "build/jenesis", "the build tool, linked into .jenesis/upstream and pruned there");

    /**
     * The bytes that hide a file from a text scan: {@code NUL} first, then every other C0 control and {@code DEL}.
     * Tab, newline and carriage return are the three controls a text file legitimately holds.
     *
     * <p>{@code NUL} is the one that trips grep's binary heuristic; the rest are here because they are the same defect
     * one notch down - invisible in an editor, invisible in a diff, invisible in review - and the byte scan that found
     * D-120's ten found three more files carrying them (all in the downstream edition; this repository had none).
     */
    private static final List<Byte> HOSTILE = hostile();

    private static List<Byte> hostile() {
        List<Byte> bytes = new ArrayList<>();
        for (int value = 0x00; value < 0x20; value++) {
            if (value != '\t' && value != '\n' && value != '\r') {
                bytes.add((byte) value);
            }
        }
        bytes.add((byte) 0x7f);
        return List.copyOf(bytes);
    }

    /** {@link #HOSTILE} as a lookup table, because the scan reads every byte of a 30 MB worktree. */
    private static final boolean[] REJECTED = rejected();

    private static boolean[] rejected() {
        boolean[] rejected = new boolean[256];
        HOSTILE.forEach(value -> rejected[Byte.toUnsignedInt(value)] = true);
        return rejected;
    }

    // --- leg 1: no file hides from a text scan -------------------------------------------------------------------

    @Test
    void no_file_carries_a_byte_that_hides_it_from_a_text_scan() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : files()) {
            for (Finding finding : hostileBytes(file)) {
                offenders.add(root().relativize(file) + " byte 0x%02x at offset %d".formatted(
                        finding.value(), finding.offset()));
            }
        }
        Collections.sort(offenders);
        assertThat(offenders)
                .as("""
                        these files carry a byte that makes a text scan drop them. grep classifies a file holding a \
                        NUL as binary and reports nothing about it at all - no warning, no error - so every \
                        grep-based census in this plan reads "not in the results" as "not an offender" over a file it \
                        never read. That is how a thirteenth proxy leg hid behind a twelve-leg census. Spell the byte \
                        as a Java escape instead: '\\0' is the same char and "\\0" the same string, so the keys, \
                        ETags and hashes it feeds are byte-identical. Prove that mechanically rather than by eye - \
                        map every control byte to its escape and map the result back - because the diff will not \
                        show you the character you are changing.""")
                .isEmpty();
    }

    // --- leg 2 and 3: decodable, and understood -------------------------------------------------------------------

    @Test
    void every_file_decodes_as_utf8_and_every_java_file_parses() throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        List<String> undecodable = new ArrayList<>();
        List<String> ununderstood = new ArrayList<>();
        int parsed = 0;
        for (Path file : files()) {
            String text;
            try {
                text = decoder.reset().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            } catch (CharacterCodingException failure) {
                undecodable.add(root().relativize(file) + ": " + failure);
                continue;
            }
            if (!file.getFileName().toString().endsWith(".java")) {
                continue;
            }
            if (DECLARATION.matcher(text).find()) {
                parsed++;
            } else {
                ununderstood.add(root().relativize(file).toString());
            }
        }

        assertThat(undecodable)
                .as("these files are not valid UTF-8, which is grep's other reason to call a file binary and drop it "
                        + "silently. A file a scan cannot decode is a file it cannot read")
                .isEmpty();
        assertThat(ununderstood)
                .as("""
                        these .java files were opened but not understood: no package or module declaration parsed out \
                        of them. Opening a file proves nothing - a scan that failed to comprehend a file reports that \
                        file clean forever, which is the same defect as not reading it at all, one level down. Either \
                        the file is not what its extension says, or this scan's own parser has rotted.""")
                .isEmpty();
        assertThat(parsed)
                .as("the java parse leg must have understood the tree, not an empty subset of it")
                .isGreaterThan(400);
    }

    // --- leg 4: the detector is alive, at every offset -------------------------------------------------------------

    @Test
    void the_scan_finds_a_hostile_byte_wherever_it_sits() throws IOException {
        Path probes = Files.createTempDirectory("legible-probe");
        try {
            // git's binary heuristic reads the first ~8000 bytes only. RpmFormat's NUL was at ~31400, so git called
            // it text while grep called it binary, and a `git grep -I` sweep found 4 of the 10. This leg is why this
            // ratchet may not be built on either tool: detection must not depend on WHERE the byte sits.
            for (int offset : new int[] {0, 7999, 8001, 31400, 65535}) {
                Path probe = probes.resolve("nul-at-" + offset + ".txt");
                byte[] body = new byte[65536];
                Arrays.fill(body, (byte) 'a');
                body[offset] = 0;
                Files.write(probe, body);
                assertThat(hostileBytes(probe))
                        .as("a NUL at offset %d must be found; git's heuristic stops at ~8000 bytes and this scan "
                                + "may not inherit that limit", offset)
                        .extracting(Finding::offset).containsExactly((long) offset);
            }

            // ... and every other byte the predicate claims to reject really is rejected, one probe each, so the
            // predicate cannot quietly shrink to "NUL only".
            for (byte value : HOSTILE) {
                Path probe = probes.resolve("byte-%02x.txt".formatted(value));
                Files.write(probe, new byte[] {'x', value, 'y'});
                assertThat(hostileBytes(probe))
                        .as("byte 0x%02x is declared hostile, so it must be detected", value)
                        .extracting(Finding::value).containsExactly(value);
            }

            // ... and the mirror: a file of ordinary text, tabs, newlines and multi-byte UTF-8 is NOT flagged, or
            // leg 1 would be asserting that the repository is empty.
            Path clean = probes.resolve("clean.txt");
            Files.writeString(clean, "package a;\n\tint x = 1; // é中😀\r\n");
            assertThat(hostileBytes(clean))
                    .as("tab, newline, carriage return and multi-byte UTF-8 are ordinary text; flagging them would "
                            + "make leg 1 pass for the wrong reason")
                    .isEmpty();
        } finally {
            delete(probes);
        }
    }

    @Test
    void the_walk_reaches_the_whole_worktree_and_skips_nothing_in_silence() throws IOException {
        Set<Path> files = files();
        Set<String> relative = files.stream().map(file -> root().relativize(file).toString())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(relative)
                .as("the walk must reach both source roots (this repository's own D-120 file was a test, so a "
                        + "source-only scan would have reported it clean), this suite's own file, and the documents a "
                        + "census greps - a walk that found nothing passes legs 1-3 vacuously")
                .contains("source/store/spi/build/jenesis/repository/store/Publication.java",
                        "test/walk/build/jenesis/repository/walk/test/StoreWalkSelfHealTest.java",
                        "test/server/build/jenesis/repository/test/LegibleTreePrincipleTest.java",
                        "DESIGN.md",
                        "AGENTS.md");
        assertThat(files).as("the worktree holds hundreds of files; a smaller answer means the walk was pruned")
                .hasSizeGreaterThan(500);
        assertThat(relative.stream().filter(path -> path.startsWith("source/")).count())
                .as("source/ is reached").isGreaterThan(200);
        assertThat(relative.stream().filter(path -> path.startsWith("test/")).count())
                .as("test/ is reached").isGreaterThan(200);

        assertThat(symlinks())
                .as("""
                        a symlink is not a file this walk can read, so it is inventoried rather than stepped over. \
                        An unlisted one is something the scan silently did not look at - which is the exact failure \
                        this suite exists to refuse. If a new link is legitimate, name it in SYMLINKS with the reason \
                        its target is covered elsewhere.""")
                .containsExactlyInAnyOrderElementsOf(SYMLINKS.keySet());
        SYMLINKS.values().forEach(reason -> assertThat(reason).isNotBlank());
        PRUNED.values().forEach(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    void an_unreadable_file_fails_rather_than_counting_as_clean() throws IOException {
        Path missing = Files.createTempDirectory("legible-missing").resolve("gone.txt");
        assertThatThrownBy(() -> hostileBytes(missing))
                .as("a file the scan cannot open must raise. Swallowing the failure would mark it clean, which is "
                        + "the same lie as never reading it")
                .isInstanceOf(IOException.class);
    }

    // --- the scan itself ------------------------------------------------------------------------------------------

    /** One hostile byte, and where it is. The offset is carried because it is the whole point: a detector whose
     *  answer depends on the offset is a detector with git's blind spot. */
    private record Finding(byte value, long offset) {
    }

    /** Every hostile byte in {@code file}, found by reading <b>every byte</b> of it. Nothing here asks git or grep
     *  whether the file "looks binary"; that question is what D-120 is about. */
    private static List<Finding> hostileBytes(Path file) throws IOException {
        List<Finding> findings = new ArrayList<>();
        byte[] buffer = new byte[1 << 16];
        long base = 0;
        try (InputStream stream = Files.newInputStream(file)) {
            for (int read = stream.read(buffer); read > 0; read = stream.read(buffer)) {
                for (int index = 0; index < read; index++) {
                    if (REJECTED[Byte.toUnsignedInt(buffer[index])]) {
                        findings.add(new Finding(buffer[index], base + index));
                    }
                }
                base += read;
            }
        }
        return findings;
    }

    /** Every regular file in the worktree, minus the four pruned trees. Symlinks are excluded here and asserted
     *  separately, because a walk that follows one silently leaves the worktree. */
    private static Set<Path> files() throws IOException {
        Set<Path> files = new TreeSet<>();
        walk(root(), files, new TreeSet<>());
        return files;
    }

    private static Set<String> symlinks() throws IOException {
        Set<String> links = new TreeSet<>();
        walk(root(), new TreeSet<>(), links);
        return links;
    }

    private static void walk(Path directory, Set<Path> files, Set<String> links) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    links.add(root().relativize(entry).toString());
                } else if (Files.isDirectory(entry)) {
                    if (!PRUNED.containsKey(entry.getFileName().toString())) {
                        walk(entry, files, links);
                    }
                } else if (Files.isRegularFile(entry)) {
                    files.add(entry);
                } else {
                    throw new AssertionError("the walk met an entry that is neither a regular file, a directory nor "
                            + "a symlink and would have skipped it in silence: " + entry);
                }
            }
        }
    }

    private static final Pattern DECLARATION =
            Pattern.compile("(?m)^\\s*(?:(?:open|abstract)\\s+)*(?:package|module)\\s+[\\w.]+\\s*[;{]");

    private static void delete(Path directory) throws IOException {
        try (Stream<Path> entries = Files.walk(directory)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    /** The repository root, located the way every source-scanning guard here locates it. Fails loudly rather than
     *  letting the whole suite pass over an empty tree. */
    private static Path root() {
        Path start = Path.of("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve("build/jenesis"))) {
                // Standalone the tree is source/ beside build/jenesis; inside an enclosing project it is
                // free/source/ beside the enclosing one. Nested first: only the outer build has both.
                Path nested = directory.resolve("free").resolve("source");
                if (Files.isDirectory(nested)) {
                    return directory.resolve("free");
                }
                if (Files.isDirectory(directory.resolve("source"))) {
                    return directory;
                }
            }
        }
        throw new AssertionError("could not locate the core repo root (an ancestor holding source/ or free/source beside "
                + "build/jenesis) from working directory " + start);
    }
}
