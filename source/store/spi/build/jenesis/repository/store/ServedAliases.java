package build.jenesis.repository.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The record that one served path is <em>the same file</em> as another, under a second name.
 *
 * <p>A cross-publish gives one uploaded artifact more than one served path: a modular jar published by Maven
 * coordinate is also linked at {@code /module/<name>/<version>/<name>.jar} and at {@code /module/<name>/<name>.jar},
 * over the same content-addressed blob. The pointers are ordinary links, so nothing in the store says the three names
 * belong to one artifact - and a reviewer who releases the coordinate leaves the module views held, because every
 * mechanism that could have lifted them is reasoning about something else.
 *
 * <p><b>Why the relation has to be recorded rather than inferred.</b> Two inferences were written and measured, and
 * both are unsound. Neither content hash nor coordinate version identifies an alias:
 *
 * <ul>
 *   <li><b>Same hash is not the same file.</b> Several distinct files of one version routinely share a hash - the
 *       conan and HuggingFace hold-contract fixtures publish a handful of empty files, all hashing to the SHA-1 of
 *       the empty string. Lifting "every sibling pointer whose body equals the released hash" therefore lifts holds
 *       nobody released.</li>
 *   <li><b>Same coordinate version is not the same file either.</b> Widening a release's exclusion set to every held
 *       path of the version clears a marker that a still-held sibling <em>file</em> of that version needs, which is
 *       the fail-open disclosure direction this whole class of guard is written against - and no test goes red.</li>
 * </ul>
 *
 * <p>So the relation is written where it is <em>created</em>, by the cross-publish that knows both names, and read
 * back by whatever must treat the group as one artifact. That is the only place the fact exists.
 *
 * <p><b>Both directions are stored,</b> because both are asked: a release of the origin needs its aliases, and a
 * release of an alias needs the group it belongs to. Each is a point read of one key ({@code alias/of<path>} and
 * {@code alias/group<origin>}), never an enumeration - a release is on the request path, and clause 1 does not
 * exempt it.
 *
 * <p><b>The group is a set, appended under compare-and-set.</b> Two versions of one module publishing at once, or a
 * rebuild pass meeting a publish, are peers on the same key at the same moment, which is exactly the case
 * {@link Retries#COMPARE_AND_SET} exists for. An append is idempotent: re-recording an alias already present rewrites
 * the same bytes, so a byte-identical republish and a repeated rebuild pass are both free.
 *
 * <p><b>Recording is best-effort and never fails the publish.</b> A missing alias record costs a reviewer a
 * second release - the state this class was written to improve on - while a failed publish costs the upload. The
 * caller therefore contains an {@link IOException} out of {@link #record} rather than propagating it, and a later
 * rebuild pass re-records what a crash lost.
 */
public final class ServedAliases {

    /** The root every alias record lives under, so a store sweep can find them as one subtree. */
    public static final String ROOT = "alias/";

    private static final String OF = ROOT + "of";

    private static final String GROUP = ROOT + "group";

    /** The key holding the origin {@code alias} belongs to - the single-valued side of the relation. Public because a
     *  repair pass, an inspection or a test reasoning about a half-written pair has to be able to address it. */
    public static String originKey(String alias) {
        return OF + alias;
    }

    /** The key holding the set of aliases recorded against {@code origin}; see {@link #originKey}. */
    public static String groupKey(String origin) {
        return GROUP + origin;
    }

    private ServedAliases() {
        throw new UnsupportedOperationException();
    }

    /**
     * Record that {@code alias} serves the same artifact as {@code origin}, in both directions.
     *
     * <p>The group entry goes first and the reverse entry second, which is the crash-safe order for the reader that
     * matters: {@link #group} resolves an alias to its origin and then reads the origin's group, so a crash between
     * the two leaves an origin whose group names an alias that cannot name it back - and the group read still returns
     * the whole set. The opposite order would leave an alias pointing at an origin whose group does not list it,
     * which reads as a group of one and silently drops the alias from a release.
     */
    public static void record(ArtifactStore store, String origin, String alias) throws IOException {
        if (origin == null || alias == null || origin.equals(alias)) {
            return;
        }
        append(store, GROUP + origin, alias);
        store.write(OF + alias, new ByteArrayInputStream(origin.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Every served path that names the same artifact as {@code path}, including {@code path} itself - so a caller
     * releasing one name can act on all of them without asking which name it was handed.
     *
     * <p>A path with no record answers a set of one, which is the overwhelmingly common case (nothing cross-publishes
     * a wheel or a {@code .deb}) and is why this is safe to call unconditionally on a release path.
     */
    public static Set<String> group(ArtifactStore store, String path) throws IOException {
        String origin = origin(store, path).orElse(path);
        Set<String> group = new LinkedHashSet<>();
        group.add(path);
        group.add(origin);
        group.addAll(aliases(store, origin));
        return group;
    }

    /** The origin {@code alias} was cross-published from, or empty when it is not an alias (it may be an origin). */
    public static Optional<String> origin(ArtifactStore store, String alias) throws IOException {
        return read(store, OF + alias);
    }

    /**
     * The aliases recorded against {@code origin}, or an empty set when it cross-publishes nothing.
     *
     * <p><b>Each is confirmed against its own reverse entry before it is returned,</b> and that check is the one that
     * makes this safe rather than the bookkeeping that writes it. A group entry only ever grows; a moving alias - the
     * "latest" view, which names whichever version published last - is taken off its previous origin by
     * {@link #reassign}, but a crash between the two writes, or any future writer that forgets, leaves the old
     * origin's group still naming it. Answering from the group alone would then hand a release of 1.0 an alias that
     * has been 2.0's since 2.0 published.
     *
     * <p>The reverse entry is the single-valued side - an alias has exactly one origin - so it is the side that can
     * settle a disagreement, and a stale group line simply drops out here. That makes the relation correct on read
     * whatever the write history was, which is the property worth having: the alternative is trusting that every
     * writer maintained a two-key invariant, on a path whose failure mode is lifting someone else's hold.
     */
    public static Set<String> aliases(ArtifactStore store, String origin) throws IOException {
        Optional<String> stored = read(store, GROUP + origin);
        if (stored.isEmpty()) {
            return Set.of();
        }
        Set<String> aliases = new TreeSet<>();
        for (String line : stored.get().split("\n")) {
            if (!line.isBlank() && origin(store, line).filter(origin::equals).isPresent()) {
                aliases.add(line);
            }
        }
        return aliases;
    }

    /**
     * Drop every record naming {@code path} - the leg an eviction runs, so no alias row outlives the artifact it
     * describes. Both directions go: the path's own group, and the reverse entry of each alias that group named.
     */
    public static void forget(ArtifactStore store, String path) throws IOException {
        for (String alias : aliases(store, path)) {
            store.delete(OF + alias);
        }
        store.delete(GROUP + path);
        Optional<String> origin = origin(store, path);
        store.delete(OF + path);
        if (origin.isPresent()) {
            remove(store, GROUP + origin.get(), path);
        }
    }

    /**
     * Record that {@code alias} names the same artifact as {@code origin} and <em>no other</em> - moving it off
     * whatever origin held it before.
     *
     * <p>This is the shape a "latest" view needs, and it is why recording an alias is not one operation. A
     * version-addressed pointer such as {@code /module/<name>/1.0/<name>.jar} belongs to version 1.0 for good, so it
     * simply accumulates. {@code /module/<name>/<name>.jar} does not: it names whichever version published last, so
     * publishing 2.0 must take it away from 1.0. Left as an append, releasing 1.0 would lift a view that has been
     * 2.0's for some time - and 2.0 may be held on its own account, which makes that a disclosure rather than an
     * untidy record.
     */
    public static void reassign(ArtifactStore store, String origin, String alias) throws IOException {
        if (origin == null || alias == null || origin.equals(alias)) {
            return;
        }
        Optional<String> previous = origin(store, alias);
        if (previous.isPresent() && previous.get().equals(origin)) {
            return;   // already ours: a republish of the same version, free
        }
        if (previous.isPresent()) {
            remove(store, GROUP + previous.get(), alias);
        }
        record(store, origin, alias);
    }

    /** Add one line to a stored set under compare-and-set, leaving it unchanged when the line is already there. */
    private static void append(ArtifactStore store, String key, String line) throws IOException {
        for (int attempt = 0; attempt < Retries.COMPARE_AND_SET; attempt++) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            Set<String> lines = new TreeSet<>();
            if (current.isPresent()) {
                for (String existing : new String(current.get().content(), StandardCharsets.UTF_8).split("\n")) {
                    if (!existing.isBlank()) {
                        lines.add(existing);
                    }
                }
            }
            if (!lines.add(line)) {
                return;   // already recorded: a repeat publish or a rebuild pass re-running, both free
            }
            if (store.writeVersioned(key, String.join("\n", lines).getBytes(StandardCharsets.UTF_8),
                    current.map(ArtifactStore.Versioned::token).orElse(null))) {
                return;
            }
            Retries.backoff(attempt);
        }
        throw new IOException("Could not record a served alias at " + key + " within " + Retries.COMPARE_AND_SET
                + " attempts; a peer publish held the key for the whole window");
    }

    /** Remove one line from a stored set under compare-and-set, deleting the key once it empties. */
    private static void remove(ArtifactStore store, String key, String line) throws IOException {
        for (int attempt = 0; attempt < Retries.COMPARE_AND_SET; attempt++) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            if (current.isEmpty()) {
                return;
            }
            Set<String> lines = new TreeSet<>();
            for (String existing : new String(current.get().content(), StandardCharsets.UTF_8).split("\n")) {
                if (!existing.isBlank()) {
                    lines.add(existing);
                }
            }
            if (!lines.remove(line)) {
                return;
            }
            if (lines.isEmpty()) {
                store.delete(key);
                return;
            }
            if (store.writeVersioned(key, String.join("\n", lines).getBytes(StandardCharsets.UTF_8),
                    current.get().token())) {
                return;
            }
            Retries.backoff(attempt);
        }
        throw new IOException("Could not drop a served alias from " + key + " within " + Retries.COMPARE_AND_SET
                + " attempts; a peer publish held the key for the whole window");
    }

    private static Optional<String> read(ArtifactStore store, String key) throws IOException {
        return store.readVersioned(key)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim())
                .filter(value -> !value.isEmpty());
    }
}
