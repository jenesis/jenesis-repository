package build.jenesis.repository.metadata.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionError;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.metadata.State;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The document value type's reader tolerance and section-level carry (§5.2): a total read never throws, an
 * unrecognised or newer section round-trips through a mutate byte-for-structure verbatim (the property at the
 * section level), the tri-state envelope serialises and re-reads, and a newer-format document is rendered but never
 * downgrade-rewritten (§5.3).
 */
class MetadataDocumentTest {

    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    private static SectionMutation set(Section section) {
        return current -> section;
    }

    private static MetadataDocument roundTrip(MetadataDocument document) {
        return MetadataDocument.read(document.serialize());
    }

    @Test
    void a_torn_or_foreign_object_reads_as_empty_not_thrown() {
        assertThat(MetadataDocument.read("not json at all".getBytes(StandardCharsets.UTF_8)).tags()).isEmpty();
        assertThat(MetadataDocument.read("[1,2,3]".getBytes(StandardCharsets.UTF_8)).tags()).isEmpty();
        assertThat(MetadataDocument.read(new byte[0]).tags()).isEmpty();
    }

    @Test
    void the_tri_state_envelope_round_trips() {
        SequencedMap<String, SectionMutation> mutations = new LinkedHashMap<>();
        mutations.put("derived", set(Section.derived("derived", 2, NOW, Signal.of(Severity.HIGH), null)));
        mutations.put("empty", set(Section.empty("empty", 1, NOW)));
        mutations.put("broken", set(Section.error("broken", 3, NOW, new SectionError("parse", "bad bytes"))));
        MetadataDocument document = roundTrip(MetadataDocument.empty().mutate(mutations));

        assertThat(document.section("derived").orElseThrow().signal().severity()).isEqualTo(Severity.HIGH);
        assertThat(document.section("empty").orElseThrow().state()).isEqualTo(State.EMPTY);
        Section broken = document.section("broken").orElseThrow();
        assertThat(broken.state()).isEqualTo(State.ERROR);
        assertThat(broken.error().kind()).isEqualTo("parse");
        assertThat(broken.error().message()).isEqualTo("bad bytes");
    }

    @Test
    void an_unknown_section_is_carried_through_a_mutate_verbatim() {
        // A newer/custom writer's section this reader does not recognise, held as its raw envelope.
        byte[] stored = ("""
                {"format":1,"sections":{
                  "com.acme.future":{"schema":9,"updated":"2026-07-25T10:00:00Z","state":"derived",
                    "signal":{"severity":"CRITICAL"},"data":{"nested":{"a":[1,2,3]},"flag":true}},
                  "licenses":{"schema":1,"updated":"2026-07-25T10:00:00Z","state":"empty","data":{}}
                }}""").getBytes(StandardCharsets.UTF_8);
        MetadataDocument document = MetadataDocument.read(stored);
        String rawBefore = document.raw("com.acme.future").toString();

        // A mutate that names only the known section must carry the unknown one untouched.
        MetadataDocument mutated = document.mutate(new LinkedHashMap<>(Map.of(
                "licenses", set(Section.derived("licenses", 1, NOW, Signal.NEUTRAL, null)))));
        MetadataDocument reread = roundTrip(mutated);

        assertThat(reread.raw("com.acme.future").toString())
                .as("the unrecognised section round-trips byte-for-structure verbatim").isEqualTo(rawBefore);
        Section survived = reread.section("com.acme.future").orElseThrow();
        assertThat(survived.schema()).isEqualTo(9);
        assertThat(survived.signal().severity()).isEqualTo(Severity.CRITICAL);
        assertThat(survived.payload().orElseThrow().path("nested").path("a").path(2).asInt()).isEqualTo(3);
    }

    @Test
    void a_removed_section_drops_and_the_rest_carry() {
        SequencedMap<String, SectionMutation> seed = new LinkedHashMap<>();
        seed.put("a", set(Section.derived("a", 1, NOW, Signal.NEUTRAL, null)));
        seed.put("b", set(Section.derived("b", 1, NOW, Signal.NEUTRAL, null)));
        MetadataDocument document = MetadataDocument.empty().mutate(seed);

        MetadataDocument mutated = document.mutate(new LinkedHashMap<>(Map.of("a", current -> null)));
        assertThat(mutated.tags()).containsExactly("b");
    }

    @Test
    void a_newer_format_document_is_rendered_but_refused_for_mutation() {
        byte[] newer = ("{\"format\":2,\"sections\":{\"future\":{\"schema\":1,\"updated\":\""
                + NOW + "\",\"state\":\"derived\",\"data\":{}}}}").getBytes(StandardCharsets.UTF_8);
        MetadataDocument document = MetadataDocument.read(newer);

        assertThat(document.newerThanKnown()).isTrue();
        assertThat(document.section("future")).as("it still renders what it recognises").isPresent();
        assertThatThrownBy(() -> document.mutate(new LinkedHashMap<>(Map.of(
                "x", set(Section.derived("x", 1, NOW, Signal.NEUTRAL, null))))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_mutation_returning_a_mismatched_tag_is_rejected() {
        assertThatThrownBy(() -> MetadataDocument.empty().mutate(new LinkedHashMap<>(Map.of(
                "a", set(Section.derived("b", 1, NOW, Signal.NEUTRAL, null))))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
