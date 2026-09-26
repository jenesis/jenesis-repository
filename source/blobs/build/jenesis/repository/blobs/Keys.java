package build.jenesis.repository.blobs;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The front-door path-guard every pull-through format shares, held once so each format rejects a hostile coordinate
 * the same way at the request boundary - before it is ever woven into a blob key. A value is unsafe when it is empty,
 * {@code .} or {@code ..} (the traversal segments), or carries a {@code /}, a {@code \}, or a control character (which
 * would split it into extra segments or smuggle in a null). A format calls this to turn such a coordinate into a
 * {@code 400} at its own front door; {@link Blobs} re-applies an equivalent segment rule at the store boundary, so the
 * two layers stay independent - a format that forgets this guard still cannot inject a path, and this guard still
 * returns a clean {@code 400} rather than deferring to the store's exception. A {@code null} value is unsafe.
 *
 * <p>Two shapes, one definition of what a hostile character is - and that definition is no longer here. It is
 * {@link ArtifactStore#traversalFree}, which screens the traversal segments, the backslash and the C0
 * control characters together, and which the store's own write screen is stated in terms of. {@link #unsafe} judges a
 * <em>single name part</em> a format splices into a key (a package name, a version, an upstream-chosen filename), so
 * it adds the one question the core's path predicate cannot answer for a name part: a {@code /} is a separator in a
 * path and hostile in a name. {@link #unsafePath} judges a whole client-supplied <em>request path</em> and is now
 * exactly the core question, kept as a named seam because the shared request screen reads better asking
 * {@code Keys.unsafePath} at a request boundary than negating a store predicate.
 *
 * <p><b>This class used to carry its own copy of the character rule</b>, because the core screened neither the
 * backslash nor the control characters when it was written. The core has both now, so the copy was two statements of
 * one rule that could only drift - the &sect;2 shape - and the front door and the store boundary would then have
 * disagreed about which publishes are legal, each believing the other agreed.
 */
public final class Keys {

    private Keys() {
    }

    /** Whether {@code value} is unsafe to weave into a blob key - empty, {@code .}, {@code ..}, or bearing a
     *  {@code /}, a {@code \}, or a control character (a {@code null} counts as unsafe). */
    public static boolean unsafe(String value) {
        if (value == null || value.isEmpty() || value.equals(".") || value.equals("..")) {
            return true;
        }
        return value.indexOf('/') >= 0 || !ArtifactStore.traversalFree(value);
    }

    /**
     * Whether {@code path} is unsafe to route into a pull-through leg - the whole-request-path form of {@link #unsafe},
     * and the single screen {@link ProxyLeg} applies on every format's behalf before its {@code proxy} adapter sees a
     * request.
     *
     * <p>A path is unsafe when the core's {@link ArtifactStore#traversalFree} says so: when it is {@code null}, when
     * it carries a {@code .} or {@code ..} segment, or when it bears a {@code \} or a C0 control character anywhere.
     * A backslash means a separator on a Windows-hosted filesystem backend and a literal character on the three
     * object stores; a control character smuggles a null or a line break into a key and into every log line and
     * generated index that key later reaches. Neither is part of a legitimate coordinate in any of the fourteen
     * ecosystems, so refusing them costs nothing.
     *
     * <p>This method is now a pure delegation, and that is the point: the request seam and the store's write screen
     * ask one predicate, so they cannot refuse different shapes. It stays as a named method rather than being
     * inlined at its call site because {@code unsafePath} says what a request boundary is asking, and because a
     * future request-only rule - one that has no business in a store key screen - would land here.
     *
     * <p>An <em>empty</em> segment is deliberately not unsafe, exactly as {@link ArtifactStore#traversalFree} rules:
     * a trailing slash on a directory-style read and a doubled separator are legitimate request shapes, not traversals.
     */
    public static boolean unsafePath(String path) {
        return !ArtifactStore.traversalFree(path);
    }
}
