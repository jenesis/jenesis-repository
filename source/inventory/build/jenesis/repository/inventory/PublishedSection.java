package build.jenesis.repository.inventory;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.metadata.State;

/**
 * The {@code published} section codec of the consolidated metadata document: the publish facts a coordinate version
 * carries - the instant it was first published, which retention orders and ages by and the release feed lists it
 * under, the instant a file of it was last published, the format-supplied {@code prerelease} flag the
 * prerelease-expiry rule reads, and the {@code pinned} force-keep marker. The section's presence <em>is</em> membership
 * of the published set: a version with a {@code published} section is a published release, and its absence is the
 * absent-from-the-set signal the retention enumeration, the rollup identity, and eviction all key off.
 *
 * <p>The {@code data} payload is {@code {"at":<instant>, "changed":<instant>, "prerelease":<bool>, "pinned":<bool>}}.
 * A version is many files on several formats - a jar and its POM, a wheel per platform, a build per subdir - which
 * arrive as separate uploads with no signal that the version is complete, so the version is published by its first
 * file and changed by each later one: {@code at} is set once, and {@code changed} moves. All methods are pure;
 * a mutation returns a fresh {@link Section} folded over the current one, preserving the fields it does not set (a pin
 * toggle keeps the publish instant; a re-publish keeps an existing pin) so no single writer clobbers another's fact.
 */
public final class PublishedSection {

    /** The section tag - the short built-in name the publish-facts subsystem owns in the document. */
    public static final String TAG = "published";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String AT_FIELD = "at";
    private static final String CHANGED_FIELD = "changed";
    private static final String PRERELEASE_FIELD = "prerelease";
    private static final String PINNED_FIELD = "pinned";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PublishedSection() {
    }

    /** The publish facts a section carries, or empty when the coordinate version is not a published member. */
    public static Optional<Facts> facts(Optional<Section> section) {
        if (section.isEmpty() || section.get().state() != State.DERIVED) {
            return Optional.empty();
        }
        return section.get().payload().map(data -> {
            Instant at = Section.instant(data.path(AT_FIELD));
            Instant changed = Section.instant(data.path(CHANGED_FIELD));
            return new Facts(at, data.path(PRERELEASE_FIELD).asBoolean(false), data.path(PINNED_FIELD).asBoolean(false),
                    changed == null ? at : changed);
        });
    }

    /** Whether a section marks the coordinate version as a published member of the inventory - a derived section with
     *  a real publish instant. A section carrying only a pin (an operator force-keep written ahead of a publish that
     *  never landed, {@code at == null}) is <em>not</em> a member: membership is being published, so the retention
     *  enumeration, the rollup identity and eviction all key off a present {@code at}. */
    public static boolean published(Optional<Section> section) {
        return facts(section).map(facts -> facts.at() != null).orElse(false);
    }

    /** The publish facts of a published coordinate version: when it was first published, whether it is a
     *  prerelease, whether it is pinned (force-kept, immune to retention), and when a file of it was last published. */
    public record Facts(Instant at, boolean prerelease, boolean pinned, Instant changed) {
    }

    /**
     * Record that a file of the version was published at {@code at}: the version's first publish sets its {@code at},
     * which no later one moves, and every one moves {@code changed}. The {@code prerelease} flag is refreshed and an
     * existing {@code pinned} marker kept, so a later file never silently unpins. Idempotent - re-recording the same
     * publish converges - and re-derivable each CAS attempt.
     */
    public static SectionMutation record(Instant at, boolean prerelease, Instant updated) {
        return current -> {
            Instant first = facts(current).map(Facts::at).orElse(null);
            return section(first == null ? at : first, prerelease, pinned(current), at, updated);
        };
    }

    /** Set or clear the pin, preserving the publish instant and prerelease flag; a no-op producing a bare pinned
     *  section only if the version was somehow never recorded (a pin ahead of its publish keeps the flag until the
     *  publish fills the facts in). */
    public static SectionMutation pinned(boolean pinned, Instant updated) {
        return current -> {
            Optional<Facts> facts = facts(current);
            Instant at = facts.map(Facts::at).orElse(null);
            boolean prerelease = facts.map(Facts::prerelease).orElse(false);
            return section(at, prerelease, pinned, facts.map(Facts::changed).orElse(null), updated);
        };
    }

    private static boolean pinned(Optional<Section> current) {
        return facts(current).map(Facts::pinned).orElse(false);
    }

    /** A published section for the given facts. */
    public static Section section(Instant at, boolean prerelease, boolean pinned, Instant changed, Instant updated) {
        ObjectNode data = JSON.createObjectNode();
        if (at == null) {
            data.putNull(AT_FIELD);
        } else {
            data.put(AT_FIELD, at.toString());
        }
        if (changed != null) {
            data.put(CHANGED_FIELD, changed.toString());
        }
        data.put(PRERELEASE_FIELD, prerelease);
        data.put(PINNED_FIELD, pinned);
        return Section.derived(TAG, SCHEMA, updated, Signal.NEUTRAL, data);
    }
}
