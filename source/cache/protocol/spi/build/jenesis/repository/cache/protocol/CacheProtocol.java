package build.jenesis.repository.cache.protocol;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * One build tool's cache wire protocol: which request paths it owns, and how to read a request of its shape as an
 * address in the shared cache. Every protocol funnels into one read and one store over the same cache, so a protocol
 * answers only what differs - the URL shape, where project and credential are presented, and what a write to an
 * occupied address does.
 *
 * <h2>Contract</h2>
 *
 * <ol>
 *   <li><b>A name is stable and lower case</b> - {@code jenesis}, {@code gradle}, {@code bazel} - since a meter, a log
 *       line and a toggle spell it. Two implementations answering one name are refused at resolution.</li>
 *   <li><b>{@link #handles} is a pure function of the path</b>: no store read, no configuration, no observable
 *       allocation. It is asked of every protocol on every cache request until one answers {@code true}.</li>
 *   <li><b>Path ownership does not overlap</b>, so dispatch order is irrelevant. A tenant's cache is one shared space
 *       and a foreign tool's layout is rooted at {@code /<its name>/}, but Gradle's {@code /gradle/<key>} has the shape
 *       of the native {@code /<step>/<inputs>} - so a native address's first segment may not be one of
 *       {@link #RESERVED}, which lists every foreign protocol's name, and a protocol claiming another's path is a
 *       composition error.</li>
 *   <li><b>{@link #address} answers empty for a request it cannot read</b>, which the caller answers {@code 400}; a
 *       malformed path never throws, though a broken composition may.</li>
 *   <li><b>An address is complete or absent.</b> A readable path with no project or credential answers an address with
 *       those fields {@code null}, so the caller tells "could not read this request" from "no credential
 *       presented".</li>
 *   <li><b>{@link Existing} is the protocol's statement about its own address space</b>, answered per request. An
 *       address that digests the bytes may deduplicate; one that digests anything else must not, or an action's first
 *       result is served forever.</li>
 *   <li><b>An implementation holds no per-request state</b> and is thread-safe; one instance serves the node's
 *       life.</li>
 *   <li><b>No dependency on a server.</b> It reads the request through {@link Request} and answers a record - no
 *       servlet, framework or cache - so it is tested by calling it and a composition can carry a subset.</li>
 * </ol>
 */
public interface CacheProtocol {

    /** Every protocol on the module path, in name order, held for the life of the JVM. Held because dispatch asks on
     *  every cache request, the hottest path, and safe because the answer changes only with the module path: a protocol
     *  declares no configuration or enablement, so a composition serves the protocols it carries. */
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

    /** The header the product's own cache presentation names a project in, for the native protocol and the node's admin
     *  surface - one definition of a wire constant. A foreign protocol's format decides where its own identity
     *  rides. */
    String PROJECT_HEADER = "Jenesis-Cache-Project";

    /** The header the product's own cache presentation names a credential in, beside the repository's own. */
    String KEY_HEADER = "Jenesis-Cache-Key";

    /** The first segments of a tenant's cache that root a foreign tool's layout rather than naming a build step, so the
     *  native protocol declines them (clause 3). A constant rather than a question put to the installed set, because
     *  {@link #handles} must answer the same with one protocol shipped as with four - removing a module must not widen
     *  what another claims. Each foreign protocol's suite pins its name's presence here. */
    Set<String> RESERVED = Set.of("maven", "gradle", "bazel");

    /** This protocol's stable, lower-case name - what a meter, a log line and an operator's toggle spell. */
    String name();

    /** Where a client of this tool is pointed, as a path within a tenant's cache (after {@code /build/<tenant>}), with
     *  {@code <project>} for a project the tool can carry only in the path; empty where the tool is pointed at the
     *  cache itself. It is the root an operator configures, not an address. */
    String endpoint();

    /** Whether this protocol owns the given request path. A pure function of the path (clause 2). */
    boolean handles(String path);

    /** Read the request as an address in the shared cache, or empty when this protocol owns the path but cannot parse
     *  it - a bad request (clause 4). */
    Optional<Address> address(Request request);

    /** What a write to an address that already holds bytes does. */
    enum Existing {

        /** Keep what is stored; correct where the address is a digest of the bytes. */
        DEDUPE,

        /** Overwrite it; correct where the address is a digest of something else, such as an action's identity. */
        REWRITE
    }

    /** The request as a protocol reads it: path, method, the headers its wire format names, and the project and
     *  credential the caller derived from the shared presentation. Both are offered because the native protocol names a
     *  project in its own header while a foreign layout presents it as the user half of HTTP authentication; a protocol
     *  takes whichever its specification says. */
    interface Request {

        /** The request path within the tenant's cache, after {@code /build/<tenant>}, from the leading slash. */
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
