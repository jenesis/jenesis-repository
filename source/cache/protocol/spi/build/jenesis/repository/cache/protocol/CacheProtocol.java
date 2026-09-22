package build.jenesis.repository.cache.protocol;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * One build tool's cache wire protocol: which request paths it owns, and how to read a request of its shape as an
 * address in the shared cache.
 *
 * <p>A protocol translates and nothing else. Every protocol this product serves funnels into one read and one store
 * over the same cache, so what actually differs between them is the URL shape, where the project and the credential
 * are presented, and what a write to an address that already holds bytes should do. An implementation therefore
 * answers those three questions and is handed the rest.
 *
 * <h2>Contract</h2>
 *
 * <ol>
 *   <li><b>A name is stable and lower case</b> - {@code jenesis}, {@code gradle}, {@code bazel} - because it is
 *       what a meter, a log line and an operator's toggle spell. Two implementations answering one name is a
 *       composition error and is refused when they are resolved, not when a request arrives.</li>
 *   <li><b>{@link #handles} is a pure function of the path</b>: no store read, no configuration lookup, no
 *       allocation a caller can observe. It is asked for every protocol on every cache request until one answers
 *       {@code true}, so it decides on the shape of the path and nothing else.</li>
 *   <li><b>Path ownership does not overlap, and {@link #RESERVED} is how.</b> A path at most one protocol claims
 *       is what makes dispatch order irrelevant - no protocol is asked to be more specific than another, and
 *       nothing has to be tried in sequence. The shared {@code /cache/} space makes that a real constraint rather
 *       than a hope: a foreign tool's layout is rooted at {@code /cache/<its name>/}, and one of them - Gradle's
 *       {@code /cache/gradle/<key>} - is shape-identical to the native {@code /cache/<step>/<inputs>}. So the
 *       first segment of a native address may not be a reserved one, every foreign protocol's name is listed
 *       here, and a protocol claiming a path another owns is a composition error rather than a question of who
 *       wins.</li>
 *   <li><b>{@link #address} answers empty for a request it cannot read</b>, and the caller answers {@code 400}.
 *       It never throws for a malformed path: a client's bad request is an answer, not an exception. It may still
 *       throw for a genuinely broken composition.</li>
 *   <li><b>An address is complete or absent.</b> A protocol that can read the path but finds no project or no
 *       credential answers the address with those fields {@code null} rather than an empty optional, because the
 *       caller distinguishes "I could not read this request" from "this request presented no credential" and
 *       answers them differently.</li>
 *   <li><b>{@link Existing} is the protocol's statement about its own address space</b>, not an operator's
 *       preference, so it is answered per request rather than configured. An address that is a digest of the
 *       bytes may deduplicate; one that is a digest of anything else must not, or the first result an action ever
 *       produced is pinned and served forever.</li>
 *   <li><b>An implementation holds no per-request state</b> and is safe to call from many threads. One instance
 *       serves the life of the node.</li>
 *   <li><b>No dependency on a server.</b> An implementation reads the request through {@link Request} and answers
 *       a record; it does not reach a servlet, a framework or the cache itself. That is what lets a protocol be
 *       tested by calling it and lets an edition ship a subset of them.</li>
 * </ol>
 */
public interface CacheProtocol {

    /**
     * Every protocol on this node's module path, in name order, with the answer held for the life of the JVM.
     *
     * <p>Held deliberately, which is the exception rather than this codebase's habit: dispatch asks for this on
     * every cache request, and a cache request is the hottest path the product serves. What makes holding safe
     * here is that the answer cannot change without the module path changing - a protocol declares no
     * configuration and has no enablement of its own, so there is nothing a reconfiguring test could invalidate.
     * An edition ships the protocols it sells by putting them on the path, which is the whole selection.
     */
    static List<CacheProtocol> installed() {
        return Installed.PROTOCOLS;
    }

    /** Holder, so the discovery runs on first use rather than at class initialisation of the interface. */
    final class Installed {

        private static final List<CacheProtocol> PROTOCOLS = Providers.all("cache-protocol",
                ServiceLoader.load(CacheProtocol.class), CacheProtocol::name, _ -> true, Optional::of);

        private Installed() {
        }
    }

    /**
     * The header the product's own cache presentation names a project in, used by the native protocol and by the
     * node's own admin surface - one definition, because two spellings of one wire constant is how they drift.
     * A foreign tool's protocol has no say in this: its own format decides where its identity rides.
     */
    String PROJECT_HEADER = "Jenesis-Cache-Project";

    /** The header the product's own cache presentation names a credential in, beside the repository's own. */
    String KEY_HEADER = "Jenesis-Cache-Key";

    /**
     * The first segments under {@code /cache/} that root a foreign tool's own layout rather than naming a build
     * step, so the native protocol declines them and the claims stay disjoint (clause 3).
     *
     * <p>A constant rather than a question put to the installed set, because {@link #handles} is a pure function
     * of the path (clause 2) and must answer the same way on a node that ships one protocol as on a node that
     * ships four - otherwise removing a module would silently widen what another one claims. Each foreign
     * protocol's name appears here, which is an invariant its own suite pins.
     */
    Set<String> RESERVED = Set.of("maven", "gradle", "bazel");

    /** This protocol's stable, lower-case name - what a meter, a log line and an operator's toggle spell. */
    String name();

    /** Whether this protocol owns the given request path. A pure function of the path (clause 2). */
    boolean handles(String path);

    /** Read the request as an address in the shared cache, or empty when the path is one this protocol owns but
     *  cannot parse - which the caller answers as a bad request (clause 4). */
    Optional<Address> address(Request request);

    /** What a write to an address that already holds bytes does. */
    enum Existing {

        /** Keep what is stored; correct where the address is a digest of the bytes. */
        DEDUPE,

        /** Overwrite it; correct where the address is a digest of something else, such as an action's identity. */
        REWRITE
    }

    /**
     * The request as a protocol reads it: the path and method it dispatches on, the headers its own wire format
     * names, and the project and credential the caller has already derived from the presentation every protocol
     * here shares.
     *
     * <p>Both are offered because the protocols genuinely differ: the native one names a project in a header of
     * its own, while a foreign layout has nowhere to put it and presents it as the user half of ordinary HTTP
     * authentication. A protocol takes whichever its own specification says, and neither is computed twice.
     */
    interface Request {

        /** The request path, from the leading slash. */
        String path();

        /** The HTTP method, upper case. */
        String method();

        /** One request header, or {@code null} when it is absent. */
        String header(String name);

        /** The project the caller derived from the shared presentation, or {@code null} when none was presented. */
        String project();

        /** The credential the caller derived from the shared presentation, or {@code null} when none was. */
        String presentedKey();
    }

    /**
     * Where a request lands in the shared cache.
     *
     * @param step        the address's first segment - a build step, a shard, a package
     * @param inputs      the address's second segment - the digest or key within the step
     * @param project     the cache project, or {@code null} when the request presented none
     * @param key         the presented credential, or {@code null} when the request presented none
     * @param existing    what a write to an address that already holds bytes does
     */
    record Address(String step, String inputs, String project, String key, Existing existing) {

        public Address {
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(existing, "existing");
        }
    }
}
