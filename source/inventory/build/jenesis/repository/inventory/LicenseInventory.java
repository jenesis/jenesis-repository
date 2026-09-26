package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.DocumentTurns;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;

/**
 * The per-coordinate declared-license facts: the licenses an artifact <em>declares</em> (a name and/or URL, exactly as
 * the publishing gate's quality inspector read them out of the artifact's own metadata), consolidated into the
 * {@code licenses} section of the unified per-coordinate metadata document ({@link MetadataKey#version}) that replaces
 * the standalone {@code licenses/} sidecar. It records the <em>declared</em> form, not a resolved SPDX id, so the one
 * place that categorises (the search sweep's {@code License.identify}) stays authoritative and a later
 * categorisation-table change re-derives cleanly from stored truth rather than from a frozen verdict.
 *
 * <p>The distinction between a <em>present but empty</em> record (the gate inspected the artifact and it declared no
 * license) and an <em>absent</em> one (never inspected) is load-bearing: the sweep indexes an empty record as the
 * unknown-license bucket and does not re-parse, but backfills an absent one from stored metadata. The section envelope
 * carries this as {@link build.jenesis.repository.metadata.State#EMPTY} versus a section absent from the document.
 *
 * <p><strong>One layout at a time.</strong> With the consolidated metadata store installed, reads and writes are the
 * document's {@code licenses} section and nothing else. With no store installed at all (an optional-module absence,
 * §3), both stay on the {@code licenses/} sidecar, so a deployment that does not carry {@code metadata.store} degrades
 * gracefully rather than losing its license facts. What is deliberately absent is a fall-through between the two: a
 * document with no licenses section means <em>not recorded</em>, and answering that from a sidecar key would make an
 * absent section indistinguishable from a stale one.
 */
public final class LicenseInventory {

    private static final String HEADER = "jenesis-licenses 1";

    private final ArtifactStore store;

    /** The consolidated metadata store this repository's document lives in, or {@code null} when no
     *  {@link MetadataProvider} is installed (the graceful-absence path stays on the sidecar). */
    private final MetadataStore metadata;

    public LicenseInventory(ArtifactStore store) {
        this.store = store;
        this.metadata = MetadataProvider.installed().map(provider -> provider.over(store)).orElse(null);
    }

    /** A license as declared by the artifact: a name and/or URL, either of which may be {@code null}, but not both. */
    public record Declared(String name, String url) {
    }

    /**
     * Record the licenses a coordinate version declares, <em>unioned</em> into any already recorded for the version: a
     * version's license set is the union of what its artifacts declare, so a sibling publish (or a re-record) only ever
     * ADDS to the set, never replaces it. An empty declaration therefore contributes nothing - it can neither erase a
     * license a sibling already recorded nor un-mark the version. A version first seen with no license still writes the
     * present-but-empty record ("inspected, none declared"), distinct from an absent one; a later real declaration then
     * backfills it. Idempotent and crash-safe: the union converges to the same set on re-record.
     *
     * <p>When the consolidated metadata store is installed the union folds into the document's {@code licenses} section
     * and, for a published member, the {@code identity/rollup} is re-folded once that document write has committed:
     * from the fingerprint of the section the write replaced to the one it wrote - the transition the compare-and-set
     * made linear, so two records of one version telescope (old to a, a to b) rather than cancel. The re-fold used to
     * be grouped into the document's batch as one unretried compare-and-set on the first attempt only, and was dropped
     * whenever the document or the rollup conflicted, which under concurrent publishers was most of the time.
     */
    public void record(String ecosystem, String coordinate, String version, List<Declared> licenses)
            throws IOException {
        if (metadata == null) {
            recordSidecar(ecosystem, coordinate, version, licenses);
            return;
        }
        Instant now = Clocks.now();
        // The transition the landing try made, or null when the union added nothing already recorded.
        String key = MetadataKey.version(ecosystem, coordinate, version);
        Transition made = DocumentTurns.take(store, key, () -> Retries.decide(store, key, current -> {
            MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                    .orElseGet(MetadataDocument::empty);
            Optional<Section> before = document.section(LicenseSection.TAG);
            List<Declared> beforeDeclared = LicenseSection.declared(before);
            // doc.mutate applies the union and carries every other section verbatim; it throws loudly on a
            // newer-format document rather than downgrade-rewriting it, exactly as the store's mutate would.
            MetadataDocument next = document.mutate(single(LicenseSection.union(licenses, now)));
            List<Declared> afterDeclared = LicenseSection.declared(next.section(LicenseSection.TAG));
            if (before.isPresent() && new LinkedHashSet<>(beforeDeclared).equals(new LinkedHashSet<>(afterDeclared))) {
                return Retries.Verdict.keep(null);          // the union added nothing already recorded - a no-op
            }
            return Retries.Verdict.write(next.serialize(), new Transition(before.map(_ -> beforeDeclared),
                    afterDeclared, PublishedSection.facts(next.section(PublishedSection.TAG))));
        }));
        if (made != null) {
            refold(ecosystem, coordinate, version, made);
        }
    }

    /** What a license record changed: the declared set the document carried (absent when it had no section), the
     *  set it carries now, and the member's publish facts as that document states them. */
    private record Transition(Optional<List<Declared>> before, List<Declared> after,
                              Optional<PublishedSection.Facts> facts) {
    }

    /** Re-fold a published member's identity contribution from the set it was folded with to the set just recorded.
     *  Only a published member is folded: an unpublished coordinate is folded when it publishes, with the section its
     *  publish commits. The set the rollup reflects for the member is the one the document carried - absent means it
     *  was folded with no license fingerprint - which is what makes the out-member correct. */
    private void refold(String ecosystem, String coordinate, String version, Transition made) throws IOException {
        if (made.facts().isPresent() && made.facts().get().at() != null) {
            new InventoryIdentity(store).refold(
                    InventoryIdentity.member(ecosystem, coordinate, version, LicenseSection.fingerprintOf(made.before())),
                    InventoryIdentity.member(ecosystem, coordinate, version,
                            LicenseSection.fingerprintOf(Optional.of(made.after()))),
                    made.facts().get().at());
        }
    }

    private static SequencedMap<String, SectionMutation> single(SectionMutation mutation) {
        SequencedMap<String, SectionMutation> one = new LinkedHashMap<>();
        one.put(LicenseSection.TAG, mutation);
        return one;
    }

    /** The sidecar write, taken only on the graceful-absence path (no consolidated store installed). */
    private void recordSidecar(String ecosystem, String coordinate, String version, List<Declared> licenses)
            throws IOException {
        // The transition the landing try made - the set the sidecar carried to the set written, read back as the
        // rebuild will read it - or null when the union added nothing.
        Transition made = Retries.decide(store, key(ecosystem, coordinate, version), current -> {
            LinkedHashSet<Declared> union = new LinkedHashSet<>();
            current.ifPresent(versioned -> union.addAll(parse(versioned.content())));
            int recorded = union.size();
            for (Declared license : licenses) {
                if (!blank(license)) {
                    union.add(normalize(license));
                }
            }
            if (current.isPresent() && union.size() == recorded) {
                return Retries.Verdict.keep(null);
            }
            byte[] written = serialize(new ArrayList<>(union));
            return Retries.Verdict.write(written, new Transition(current.map(versioned -> parse(versioned.content())),
                    parse(written), Optional.empty()));
        });
        if (made != null) {
            // The same re-fold the document path makes after its commit, for a published member; the sidecar carries
            // no publish facts, so membership is asked of the inventory.
            if (new StoreRepositoryInventory(store).membership(ecosystem, coordinate, version)
                    instanceof Known.Present<PublishedSection.Facts> present) {
                refold(ecosystem, coordinate, version, new Transition(made.before(), made.after(),
                        Optional.of(present.value())));
            }
        }
    }

    /**
     * The licenses a coordinate version declares, or empty when none has been recorded for it yet (so the sweep knows
     * to backfill from stored metadata rather than treat the artifact as license-free). Reads the document's
     * {@code licenses} section when the consolidated store is installed, and the {@code licenses/} sidecar when none
     * is - one layout or the other, never one falling through to the other. A read never writes (§10).
     */
    public Optional<List<Declared>> read(String ecosystem, String coordinate, String version) throws IOException {
        if (metadata != null) {
            return metadata.read(ecosystem, coordinate, version)
                    .filter(document -> document.has(LicenseSection.TAG))
                    .map(document -> LicenseSection.declared(document.section(LicenseSection.TAG)));
        }
        return store.readVersioned(key(ecosystem, coordinate, version)).map(versioned -> parse(versioned.content()));
    }

    static byte[] serialize(List<Declared> licenses) {
        StringBuilder body = new StringBuilder(HEADER).append('\n');
        for (Declared license : licenses) {
            if ((license.name() == null || license.name().isBlank())
                    && (license.url() == null || license.url().isBlank())) {
                continue;                                            // nothing to record for a wholly blank declaration
            }
            body.append("license ").append(encode(license.name())).append(' ').append(encode(license.url()))
                    .append('\n');
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    static List<Declared> parse(byte[] bytes) {
        List<Declared> licenses = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] token = line.split(" ", -1);
            if (token.length == 3 && token[0].equals("license")) {
                String name = decode(token[1]);
                String url = decode(token[2]);
                licenses.add(new Declared(name.isEmpty() ? null : name, url.isEmpty() ? null : url));
            }
        }
        return licenses;
    }

    private static boolean blank(Declared license) {
        return (license.name() == null || license.name().isBlank())
                && (license.url() == null || license.url().isBlank());
    }

    private static Declared normalize(Declared license) {
        String name = license.name() == null || license.name().isBlank() ? null : license.name();
        String url = license.url() == null || license.url().isBlank() ? null : license.url();
        return new Declared(name, url);
    }

    /** The {@code licenses/} sidecar space's root, and this class is its single composer: the reconcile
     *  sweep that reaps the space names this constant rather than re-spelling the literal, which is the half of 
     *  that {@code overrides/} closed and this one had not. */
    static final String ROOT = "licenses";

    /** The store key one version's license record lives under - package-visible so an eviction
     *  ({@code StoreRepositoryInventory.evict}) reclaims it. The ecosystem and version are validated traversal-free,
     *  like every other sidecar key. */
    static String key(String ecosystem, String coordinate, String version) {
        return ROOT + "/" + ArtifactStore.segment(ecosystem) + "/" + encode(coordinate)
                + "/" + ArtifactStore.segment(version);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }
}
