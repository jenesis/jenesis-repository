package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.BoundedArchive;
import build.jenesis.repository.compliance.BoundedBodyReader;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.compliance.MalformedArtifactException;
import build.jenesis.repository.compliance.QualityInspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared bounded-read primitives every {@link QualityInspector} screens through: the manifest-tier read that
 * refuses rather than truncates, the prefix tier that says whether a {@code byte[]} is the artifact whole, and the
 * bounded archive walk that reports being cut off instead of passing a half-walked archive off as one that declares
 * nothing. These are the edge cases the sixteen hand-rolled copies each had to get right on their own; getting them
 * wrong here is what a truncated manifest parsed as a complete declaration, or an unbounded inflate, would look like.
 *
 * <p>The walk bound itself is {@link ArchiveWalk} since the earlier work, so what is pinned here is the gate's
 * ranking walk over it and the two behaviours the gate's own hand-rolled cap got wrong before it went:
 * a truncated walk that handed back the decoy it passed on the way, and a budget exactly spent that cried truncation
 * over an archive it had seen whole.
 *
 * <p>It also pins the two ways a walk's outcome is taken - {@code orNull()} for an optional declaration beside a
 * path-derived coordinate, {@code required(...)} for a manifest that carries the artifact's identity - because the
 * choice between degrading and failing closed is the one every archive-cracking inspector has to make, and it is a
 * property of what the entry carries rather than of the format.
 */
class BoundedInspectionTest {

    @Test
    void the_inspection_tiers_are_a_ladder_and_the_order_is_what_they_mean() {
        // Four constants that look independent are not: each pair below encodes a relationship the rest of the
        // screening path relies on, and is what it costs when one of them is only implied. The full-body tier
        // was the secret scanner's private 16 MiB budget - HALF the prefix tier - so the leg that exists to read past
        // the bounded prefix read a third as far as it, on both legs, and nothing caught the inversion.
        assertThat(ArchiveInflation.largestEntry())
                .as("one embedded declaration cannot be larger than the prefix that is supposed to carry it. The "
                        + "manifest tier is the shared archive-inflation ceiling since the earlier work, so this "
                        + "rung is now a DEPLOYMENT fact rather than a compile-time one: an operator who raises %s "
                        + "past the prefix tier has bought reach the prefix cannot carry, and this is where they "
                        + "find out", ArchiveInflation.LARGEST_ENTRY_KEY)
                .isLessThanOrEqualTo(QualityInspector.PREFIX_INSPECTION_LIMIT);
        assertThat(ArchiveWalk.largestWalk())
                .as("an archive walk fed from a prefix-tier body must be allowed to consume at least that prefix, or "
                        + "the walk stops inside bytes the leg already holds. The walk tier is the shared "
                        + "archive-walk bound since the earlier work, so this rung is a DEPLOYMENT fact too: an operator who "
                        + "lowered %s below the prefix tier has bought a walk that cannot cross what the leg holds",
                        ArchiveWalk.LARGEST_WALK_KEY)
                .isGreaterThanOrEqualTo(QualityInspector.PREFIX_INSPECTION_LIMIT);
        assertThat(QualityInspector.FULL_BODY_INSPECTION_LIMIT)
                .as("the full-body tier must be STRICTLY above the prefix tier: a whole-artifact leg that reads no "
                        + "further than the bounded-prefix leg buys bounded heap and no reach - and the screens decide "
                        + "'were the inspectors shown the whole body?' by comparing its length against the prefix "
                        + "tier, so an inspector reading less far returns empty over a body they believe was seen "
                        + "whole, which is ALLOWed as 'understood, declares nothing'")
                .isGreaterThan(QualityInspector.PREFIX_INSPECTION_LIMIT);
        assertThat(QualityInspector.FULL_BODY_INSPECTION_LIMIT)
                .as("a full-body content scan must not stop earlier than a prefix-tier inspector's archive walk over "
                        + "the same body - that is the same inversion one seam over")
                .isGreaterThanOrEqualTo(ArchiveWalk.largestWalk());
    }

    @AfterEach
    void restoreConfig() {
        Features.reset();
    }

    @Test
    void a_value_within_the_limit_is_read_whole() throws IOException {
        byte[] manifest = "{\"license\":\"MIT\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(ArchiveInflation.entry(new ByteArrayInputStream(manifest), 64).orNull()).isEqualTo(manifest);
    }

    @Test
    void a_value_exactly_at_the_limit_is_still_complete() throws IOException {
        byte[] manifest = new byte[64];
        Arrays.fill(manifest, (byte) 'x');
        assertThat(ArchiveInflation.entry(new ByteArrayInputStream(manifest), 64).orNull())
                .as("the limit is the largest COMPLETE value, not the first refused one")
                .hasSize(64);
    }

    @Test
    void one_byte_past_the_limit_yields_no_value_rather_than_a_plausible_prefix() throws IOException {
        // The bound must be an outcome, not a shorter value: a manifest cut off at the cap could still parse - a
        // truncated JSON object, a truncated control stanza - and would then be screened as if it were the whole
        // declaration. Returning nothing at all is what makes reaching the bound visible to the caller.
        ArchiveInflation.Entry entry = ArchiveInflation.entry(new ByteArrayInputStream(new byte[65]), 64);
        assertThat(entry.orNull()).isNull();
        assertThat(entry.truncated())
                .as("and it is a distinct OUTCOME, not the same empty an entry that carries nothing answers with")
                .isTrue();
    }

    @Test
    void the_manifest_tier_an_inspector_reads_under_is_the_operator_settable_shared_ceiling() throws IOException {
        // the inspectors' manifest tier used to be BoundedBodyReader's private 4 MiB constant, so an operator
        // who raised or lowered jenreg.archive.largest-entry moved the FORMATS' ceiling and not the GATE's - two
        // numbers that were only ever parallel by convention. One bound now answers for both.
        byte[] manifest = new byte[512];
        assertThat(ArchiveInflation.entry(new ByteArrayInputStream(manifest)).exhausted())
                .as("512 bytes is a manifest under the shared default")
                .isTrue();

        Features.configure(key -> ArchiveInflation.LARGEST_ENTRY_KEY.equals(key) ? "128" : null);
        assertThat(ArchiveInflation.entry(new ByteArrayInputStream(manifest)).truncated())
                .as("the operator lowered the shared ceiling, so the same manifest is now over it - the read that "
                        + "every inspector makes honours the key without any of them knowing about it")
                .isTrue();
    }

    @Test
    void a_hugely_over_cap_source_is_refused_without_materialising_it() throws IOException {
        // A decompression bomb: the reader must stop at the cap, not drain the source to find out how big it is. The
        // counting stream proves nothing beyond the cap plus the shared read's own copy buffer was ever pulled.
        Counting bomb = new Counting(InputStream.nullInputStream()) {
            @Override
            public int read(byte[] bytes, int offset, int length) {
                Arrays.fill(bytes, offset, offset + length, (byte) 'A');
                count += length;
                return length;                                  // an endless, endlessly compressible stream
            }
        };
        assertThat(ArchiveInflation.entry(bomb, 4096).orNull()).isNull();
        assertThat(bomb.count).as("the read stops at the cap rather than draining the bomb")
                .isLessThanOrEqualTo(4096 + 8192);
    }

    @Test
    void an_unusable_limit_is_refused_rather_than_silently_adjusted() {
        assertThatThrownBy(() -> ArchiveInflation.entry(InputStream.nullInputStream(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArchiveInflation.entry(InputStream.nullInputStream(), 0))
                .as("a zero ceiling would make every member truncated, which is not a bound but a refusal")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_prefix_tier_tells_a_whole_artifact_from_a_window_full_of_one() {
        assertThat(BoundedBodyReader.completeArtifact(new byte[0])).isTrue();
        assertThat(BoundedBodyReader.completeArtifact(new byte[]{1, 2, 3})).isTrue();
        assertThat(BoundedBodyReader.completeArtifact(null))
                .as("no bytes at all is not an artifact read whole").isFalse();
    }

    @Test
    void an_artifact_filling_the_prefix_window_is_not_treated_as_read_whole() throws IOException {
        // A body of exactly the window is indistinguishable from a larger one cut off there, so a whole-artifact fact
        // (the attestation binding digest) must not be asserted over it.
        byte[] window = new byte[QualityInspector.PREFIX_INSPECTION_LIMIT];
        assertThat(BoundedBodyReader.completeArtifact(window)).isFalse();
        assertThat(BoundedBodyReader.completeArtifact(Arrays.copyOf(window, window.length - 1))).isTrue();
    }

    @Test
    void the_prefix_bridge_stops_at_the_prefix_tier() throws IOException {
        Counting endless = new Counting(InputStream.nullInputStream()) {
            @Override
            public int read(byte[] bytes, int offset, int length) {
                count += length;
                return length;
            }
        };
        byte[] prefix = BoundedBodyReader.readPrefix(new QualityInspector.Content() {
            @Override
            public long size() {
                return Long.MAX_VALUE;
            }

            @Override
            public InputStream open() {
                return endless;
            }
        });
        assertThat(prefix).hasSize(QualityInspector.PREFIX_INSPECTION_LIMIT);
        assertThat(BoundedBodyReader.completeArtifact(prefix))
                .as("the bridged prefix declares itself incomplete to whatever reads it").isFalse();
    }

    @Test
    void the_walk_bound_is_the_operator_settable_shared_one() throws IOException {
        // the gate's archive-walk ceiling used to be BoundedArchive's own 64 MiB constant and its own byte-
        // counting stream, in parallel with a private copy of both in every archive-cracking FORMAT one repository
        // over. An operator who moved jenreg.archive.largest-walk moved neither. One bound answers for all of them
        // now, and the walk that applies it honours the key without any inspector knowing about it.
        byte[] archive = zip(Map.of("manifest", "root"));
        assertThat(read(archive, ArchiveWalk.largestWalk()).orNull()).isEqualTo("root");

        Features.configure(key -> ArchiveWalk.LARGEST_WALK_KEY.equals(key) ? "16" : null);
        assertThat(BoundedArchive.zipEntry("Test artifact", new ByteArrayInputStream(archive),
                ArchiveWalk.largestWalk(), name -> name.equals("manifest") ? 0 : -1,
                (_, entry) -> Optional.ofNullable(ArchiveInflation.entry(entry).orNull())
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))).truncated())
                .as("the operator lowered the shared bound, so the same archive no longer reaches its manifest")
                .isTrue();
    }

    @Test
    void a_body_relative_ceiling_never_falls_below_the_shared_tier() {
        assertThat(ArchiveWalk.largestWalk(0, 100)).isEqualTo(ArchiveWalk.largestWalk());
        assertThat(ArchiveWalk.largestWalk(1024, 100))
                .as("a small package still gets the whole shared budget").isEqualTo(ArchiveWalk.largestWalk());
        assertThat(ArchiveWalk.largestWalk(ArchiveWalk.largestWalk(), 4))
                .as("a large one is bounded by its own compressed size times the format's ratio")
                .isEqualTo(4 * ArchiveWalk.largestWalk());
        assertThat(ArchiveWalk.largestWalk(Long.MAX_VALUE / 2, 100))
                .as("the multiplication saturates rather than overflowing into a tiny (or negative) ceiling")
                .isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> ArchiveWalk.largestWalk(1024, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_preferred_manifest_wins_over_a_fallback_wherever_it_sits() throws IOException {
        byte[] archive = zip(Map.of("prefix/manifest", "nested", "manifest", "root", "src/code", "payload"));
        assertThat(read(archive, ArchiveWalk.largestWalk()).orNull()).isEqualTo("root");
    }

    @Test
    void a_fallback_manifest_is_used_when_there_is_no_preferred_one() throws IOException {
        byte[] archive = zip(Map.of("prefix/manifest", "nested", "src/code", "payload"));
        ArchiveWalk.Found<String> found = read(archive, ArchiveWalk.largestWalk());
        assertThat(found.orNull()).isEqualTo("nested");
        assertThat(found.truncated()).isFalse();
    }

    @Test
    void an_archive_that_declares_nothing_is_an_answer_not_a_truncation() throws IOException {
        ArchiveWalk.Found<String> found = read(zip(Map.of("src/code", "payload")), ArchiveWalk.largestWalk());
        assertThat(found.exhausted()).as("the whole archive was walked - it genuinely declares nothing").isTrue();
        assertThat(found.truncated()).isFalse();
        assertThat(found.orNull()).isNull();
    }

    @Test
    void a_walk_cut_off_by_the_ceiling_says_so_rather_than_reporting_an_empty_archive() throws IOException {
        // The design gate: reaching a bound may not look like a complete answer. The value is absent either way, but
        // "we stopped looking" is a different fact from "this archive declares nothing", and the caller is told which.
        // The payload is incompressible, so the archive's own footprint - what the walk bound budgets - really does
        // run past the ceiling before the manifest behind it is reached.
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("src/payload", incompressible(200_000));
        entries.put("manifest", "root");
        byte[] archive = zip(entries);
        assertThat(archive.length).as("the payload did not simply deflate away").isGreaterThan(100_000);
        ArchiveWalk.Found<String> found = read(archive, 4096);
        assertThat(found.orNull()).isNull();
        assertThat(found.truncated()).isTrue();
    }

    @Test
    void a_truncated_walk_carries_no_declaration_at_all_not_the_one_it_passed_on_the_way() throws IOException {
        // the earlier first behaviour change, and the reason it is a change worth making: the gate's old walk handed back
        // the best entry it had found WITH the truncation flag set, and every orNull() caller - Composer, CocoaPods,
        // Conda, the Maven licence read - takes the value and drops the flag. So a crafted archive could file a decoy
        // manifest at the front, bury the real one past the ceiling, and choose what the screen saw. Here the decoy
        // is a fallback-ranked manifest (rank 1, so the walk keeps looking) sitting before an incompressible payload;
        // the walk finds it, then the bound stops it, and the answer is NOTHING rather than the decoy.
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("prefix/manifest", "decoy");
        entries.put("src/payload", incompressible(200_000));
        entries.put("manifest", "the real one");
        ArchiveWalk.Found<String> found = read(zip(entries), 4096);
        assertThat(found.truncated()).as("the bound really did stop this walk").isTrue();
        assertThat(found.orNull())
                .as("a cut-off walk declares nothing: what it passed on the way is what an attacker chose to put "
                        + "there, so handing it back would let the archive pick its own screen")
                .isNull();
    }

    @Test
    void an_archive_that_ends_exactly_on_the_bound_is_exhausted_rather_than_truncated() throws IOException {
        // the earlier second behaviour change. The gate's old capped stream flipped its flag the moment a read was
        // attempted with the budget spent - whether or not the source also ended right there - so an archive whose
        // walk footprint was exactly the ceiling reported as cut off. An identity-bearing caller then refused a
        // package it had in fact seen whole, and an optional one logged a truncation that never happened; a bound
        // that cries wolf is one callers learn to ignore. The free screen looks exactly one byte ahead before it
        // decides, so "the last byte I was allowed to read was the last byte there was" is a complete walk.
        //
        // The fixture is an archive cut to exactly its entry data - the zip walk draws every one of those bytes and
        // then reaches for the next local header, which is the read that used to be misread as a truncation.
        byte[] archive = zip(Map.of("prefix/manifest", "nested"));
        int entryData = centralDirectory(archive);
        byte[] exact = Arrays.copyOf(archive, entryData);

        ArchiveWalk.Found<String> found = read(exact, entryData);
        assertThat(found.truncated())
                .as("the walk drew the whole archive and the archive ended there - nothing was cut off").isFalse();
        assertThat(found.exhausted()).isTrue();
        assertThat(found.orNull()).as("...so the declaration it found stands").isEqualTo("nested");
    }

    /** Where a zip's central directory starts - i.e. one past the last byte of entry data the entry walk draws. */
    private static int centralDirectory(byte[] archive) {
        byte[] signature = {0x50, 0x4b, 0x01, 0x02};
        for (int index = 0; index + signature.length <= archive.length; index++) {
            if (Arrays.equals(archive, index, index + signature.length, signature, 0, signature.length)) {
                return index;
            }
        }
        throw new IllegalStateException("the fixture archive carries no central directory");
    }

    @Test
    void an_entry_past_the_manifest_tier_declares_nothing_rather_than_a_truncated_manifest() throws IOException {
        byte[] archive = zip(Map.of("manifest", "x".repeat(4096)));
        ArchiveWalk.Found<String> found = BoundedArchive.zipEntry("Test artifact",
                new ByteArrayInputStream(archive), ArchiveWalk.largestWalk(),
                name -> name.equals("manifest") ? 0 : -1,
                (_, entry) -> Optional.ofNullable(ArchiveInflation.entry(entry, 128).orNull()).map(String::new));
        assertThat(found.orNull()).as("the over-tier entry is never parsed from its prefix").isNull();
    }

    @Test
    void an_identity_bearing_walk_refuses_an_archive_that_declares_nothing() throws IOException {
        // The other side of the same result. A walk whose entry carries the artifact's COORDINATE has no path-derived
        // fallback to degrade to, so "this archive carries no such member" must fail closed: degrading it would admit
        // an artifact that nothing screened at all. Same Found, different accessor - the role of the read decides.
        ArchiveWalk.Found<String> found = read(zip(Map.of("src/code", "payload")), ArchiveWalk.largestWalk());
        assertThat(found.orNull()).as("the optional-declaration side still degrades").isNull();
        assertThatThrownBy(() -> BoundedArchive.required(found, "Test artifact", "manifest"))
                .as("and the gate re-words it into its OWN refusal type, which the screens hold fail-closed on")
                .isInstanceOf(MalformedArtifactException.class)
                .hasMessage("Test artifact carries no manifest");
    }

    @Test
    void an_identity_bearing_walk_says_the_bound_stopped_it_rather_than_blaming_the_archive() throws IOException {
        // And it says WHICH of the two happened: an archive that genuinely carries no manifest and one whose manifest
        // sat behind the walk bound are different operator-facing facts, so the refusal must not conflate them - and
        // it names the key an operator would raise.
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("src/payload", incompressible(200_000));
        entries.put("manifest", "root");
        ArchiveWalk.Found<String> found = read(zip(entries), 4096);
        assertThat(found.truncated()).isTrue();
        assertThatThrownBy(() -> BoundedArchive.required(found, "Test artifact", "manifest"))
                .isInstanceOf(MalformedArtifactException.class)
                .hasMessageContaining("does not reach its manifest")
                .hasMessageContaining(ArchiveWalk.LARGEST_WALK_KEY);
    }

    @Test
    void an_identity_bearing_walk_that_found_its_manifest_simply_yields_it() throws IOException {
        assertThat(BoundedArchive.required(read(zip(Map.of("manifest", "root")), ArchiveWalk.largestWalk()),
                "Test artifact", "manifest")).isEqualTo("root");
    }

    @Test
    void a_body_that_is_not_a_zip_at_all_is_refused_as_malformed() {
        byte[] garbage = "this is not an archive".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> read(garbage, ArchiveWalk.largestWalk()))
                .isInstanceOf(MalformedArtifactException.class)
                .hasMessageContaining("Test artifact is not a readable zip archive");
    }

    @Test
    void an_empty_body_is_refused_as_malformed_rather_than_read_as_an_empty_archive() {
        assertThatThrownBy(() -> read(new byte[0], ArchiveWalk.largestWalk()))
                .isInstanceOf(MalformedArtifactException.class);
    }

    @Test
    void a_reader_that_refuses_its_entry_keeps_the_malformed_signal() throws IOException {
        // A reader may decide the entry it claimed is itself unparseable (a .nuspec that is not XML). That verdict is
        // the artifact-level refusal the screens fail closed on and must not be swallowed into "declares nothing" -
        // nor into a truncation, since the walk still had budget when the reader spoke.
        byte[] archive = zip(Map.of("manifest", "root"));
        assertThatThrownBy(() -> BoundedArchive.zipEntry("Test artifact", new ByteArrayInputStream(archive),
                ArchiveWalk.largestWalk(), name -> name.equals("manifest") ? 0 : -1,
                (_, _) -> {
                    throw new MalformedArtifactException("Test artifact manifest is not readable");
                }))
                .isInstanceOf(MalformedArtifactException.class)
                .hasMessageContaining("manifest is not readable");
    }

    @Test
    void a_directory_entry_is_never_offered_to_the_reader_however_it_is_named() throws IOException {
        // A hostile archive can name a DIRECTORY entry so that it ranks; reading it would yield an empty "manifest"
        // that shadows the real one behind it.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest"));
            zip.write("root".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ArchiveWalk.Found<String> found = BoundedArchive.zipEntry("Test artifact",
                new ByteArrayInputStream(out.toByteArray()), ArchiveWalk.largestWalk(),
                name -> name.startsWith("manifest") ? 0 : -1,
                (_, entry) -> Optional.ofNullable(ArchiveInflation.entry(entry).orNull())
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8)));
        assertThat(found.orNull()).isEqualTo("root");
    }

    /** The shared walk under test: {@code manifest} at the root is preferred, one directory deep is the fallback. */
    private static ArchiveWalk.Found<String> read(byte[] archive, long walkLimit)
            throws MalformedArtifactException {
        return BoundedArchive.zipEntry("Test artifact", new ByteArrayInputStream(archive), walkLimit,
                name -> name.equals("manifest") ? 0 : name.endsWith("/manifest") ? 1 : -1,
                (_, entry) -> Optional.ofNullable(ArchiveInflation.entry(entry).orNull())
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8)));
    }

    /** A deterministic, deflate-resistant filler, so a test archive's compressed footprint is genuinely its size. */
    private static String incompressible(int length) {
        Random random = new Random(20260808L);
        StringBuilder filler = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            filler.append((char) ('!' + random.nextInt(90)));
        }
        return filler.toString();
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** A stream that records how many bytes a bounded read actually pulled out of it. */
    private abstract static class Counting extends FilterInputStream {

        long count;

        Counting(InputStream in) {
            super(in);
        }
    }
    @Test
    void the_inspection_tiers_are_operator_settable_and_the_readers_follow_them() throws IOException {
        assertThat(QualityInspector.prefixInspectionLimit()).isEqualTo(QualityInspector.PREFIX_INSPECTION_LIMIT);
        assertThat(QualityInspector.fullBodyInspectionLimit()).isEqualTo(QualityInspector.FULL_BODY_INSPECTION_LIMIT);

        Features.configure(key -> QualityInspector.PREFIX_INSPECTION_LIMIT_KEY.equals(key) ? "64" : null);

        assertThat(QualityInspector.prefixInspectionLimit()).isEqualTo(64);
        assertThat(QualityInspector.fullBodyInspectionLimit())
                .as("the full-body tier is defined as twice the prefix tier, so it moves with it")
                .isEqualTo(128);

        // The point of the dial is the READS, not the accessor: a reader holding the compiled constant would answer
        // every assertion above and still hand a 32 MiB prefix to an inspector whose operator asked for 64 bytes.
        assertThat(BoundedBodyReader.completeArtifact(new byte[64]))
                .as("a body filling the configured window is not a body read whole").isFalse();
        assertThat(BoundedBodyReader.completeArtifact(new byte[63])).isTrue();
        assertThat(BoundedBodyReader.readPrefix(new QualityInspector.Content() {
            @Override
            public long size() {
                return Long.MAX_VALUE;
            }

            @Override
            public InputStream open() {
                return new ByteArrayInputStream(new byte[4096]);
            }
        })).hasSize(64);
    }

    @Test
    void the_full_body_tier_can_be_decoupled_from_the_prefix_tier_outright() {
        Features.configure(key -> switch (key) {
            case QualityInspector.PREFIX_INSPECTION_LIMIT_KEY -> "64";
            case QualityInspector.FULL_BODY_INSPECTION_LIMIT_KEY -> "1024";
            default -> null;
        });

        // Set outright, the full-body tier is what it says rather than twice the prefix - the deployment that wants a
        // cheap prefix screen and a generous whole-artifact scanner is exactly why the two are separate keys.
        assertThat(QualityInspector.prefixInspectionLimit()).isEqualTo(64);
        assertThat(QualityInspector.fullBodyInspectionLimit()).isEqualTo(1024);
    }

    @Test
    void a_mistyped_tier_refuses_rather_than_leaving_the_operator_on_the_old_one() {
        Features.configure(key -> QualityInspector.PREFIX_INSPECTION_LIMIT_KEY.equals(key) ? "32MiB" : null);

        assertThatThrownBy(QualityInspector::prefixInspectionLimit)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(QualityInspector.PREFIX_INSPECTION_LIMIT_KEY);
    }

}
