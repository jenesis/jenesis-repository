package build.jenesis.repository.format.go;

import module java.base;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.Features;

/**
 * The Go checksum database as this repository reaches it: the service that answers "what is the {@code h1:} dirhash of
 * module M at version V", the only place a GOPROXY mirror learns what a proxied {@code .mod} or {@code .zip} should
 * hash to.
 *
 * <p>It is configured as an upstream of its own because the GOPROXY protocol advertises no digest, and the canonical
 * public proxy does not mirror the database under {@code /sumdb/}. It is read directly first, then through the
 * upstream's mirror: the direct route is cheaper and stronger, since a digest from an origin independent of the one
 * that served the bytes cannot be made to agree by a compromised GOPROXY.
 *
 * <p>The {@code lookup} record is read and compared; the note's Ed25519 signature and the tile inclusion proof are not
 * verified, the signature binding a tree head rather than a line and the proof needing a transparency-log client. So
 * this is a digest check against the database, and {@link GoFormat} relays {@code /go/sumdb/...} so a client can run
 * the full check itself.
 *
 * <p>{@code jenrepo.go.sumdb} is the database's base URL, {@code https://sum.golang.org/} by default and {@code off} to
 * disable: an egress decision made where the process starts, so an air-gapped deployment sets it off. It is screened as
 * an operator-configured outbound target: {@link #base(boolean)} throws on a value that is no http(s) base URL, naming
 * the key, and refuses cleartext without the opt-in, since an intermediary answering every lookup in cleartext would
 * choose both the module bytes and the digest they are checked against. The screen is
 * {@link OutboundTargets#configuredRefusal}, the proxy upstream's rule under the same {@link ProxyLeg#ALLOW_INTERNAL}
 * dial: the transport half only, since a database on an internal address is a legitimate deployment.
 */
public final class GoChecksumDatabase {

    /** The key an operator points at their own checksum database, or sets to {@code off}. */
    public static final String DATABASE_KEY = "jenrepo.go.sumdb";

    /** The ecosystem's own default, and the value {@code GOSUMDB} carries when nothing sets it. */
    private static final String DEFAULT_DATABASE = "https://sum.golang.org/";

    /** The operations the GOPROXY protocol defines under {@code /sumdb/<name>/}; nothing else is relayed, so a client
     *  path never becomes an arbitrary request. */
    private static final List<String> OPERATIONS = List.of("supported", "latest", "lookup/", "tile/");

    /** How many lines of a lookup response are read: the record is three lines and a signed tree head follows a blank
     *  one. */
    private static final int MAX_RECORD_LINES = 64;

    private GoChecksumDatabase() {
        throw new UnsupportedOperationException("GoChecksumDatabase is a static utility");
    }

    /** The two dirhashes a lookup publishes for one module version, the {@code .zip}'s and the {@code go.mod}'s; either
     *  may be {@code null}. */
    public record Dirhashes(String zip, String mod) {
    }

    /**
     * The configured database's base URL, or {@code null} when turned off, in which case a proxied module is cached
     * unverified, as {@link GoFormat} declares.
     *
     * @param allowInternal the deployment's {@link ProxyLeg#ALLOW_INTERNAL} dial, threaded from the exchange
     * @throws IllegalArgumentException when the key is not an {@code http}/{@code https} URL, or is cleartext without
     *     the opt-in: a configuration fault, so it fails fast rather than leaving an operator believing verification
     *     runs
     */
    public static URI base(boolean allowInternal) {
        String configured = Features.lookup().apply(DATABASE_KEY);
        String value = configured == null || configured.isBlank() ? DEFAULT_DATABASE : configured.trim();
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false")) {
            return null;
        }
        URI base;
        try {
            base = URI.create(value.endsWith("/") ? value : value + "/");
        } catch (IllegalArgumentException cause) {
            throw new IllegalArgumentException(DATABASE_KEY + " must be the base URL of a Go checksum database "
                    + "(or 'off'), not '" + configured + "'", cause);
        }
        String scheme = base.getScheme();
        if (base.getAuthority() == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(DATABASE_KEY + " must be an http(s) base URL of a Go checksum database "
                    + "(or 'off'), not '" + configured + "'");
        }
        // The proxy upstream's screen, one rule and one dial for both operator-configured roots.
        String refusal = OutboundTargets.configuredRefusal(base, allowInternal);
        if (refusal != null) {
            throw new IllegalArgumentException(DATABASE_KEY + " names a checksum database this deployment refuses to "
                    + "reach: " + refusal + ". A cleartext lookup lets an active intermediary answer every integrity "
                    + "question itself, and a proxied module is then held to the digest that intermediary chose. Use "
                    + "an https database, set the key to 'off', or set " + ProxyLeg.ALLOW_INTERNAL
                    + " to permit an internal or http target.");
        }
        return base;
    }

    /** The name the configured database answers to in a {@code /sumdb/<name>/} path, its authority; {@code null} when
     *  off. */
    public static String name(boolean allowInternal) {
        URI base = base(allowInternal);
        return base == null ? null : base.getAuthority();
    }

    /** Whether {@code operation} is one the GOPROXY protocol defines under {@code /sumdb/<name>/}. */
    public static boolean relayable(String operation) {
        for (String allowed : OPERATIONS) {
            if (allowed.endsWith("/") ? operation.startsWith(allowed) : operation.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    /** Read one checksum-database path ({@code supported}, {@code latest}, {@code lookup/...}, {@code tile/...}) from
     *  the configured database directly where the name matches it, else from the configured GOPROXY's mirror; empty
     *  when neither answers. {@code database} is only ever spliced into the operator-configured upstream's path, never
     *  a host, and the direct leg is taken only for the configured name. The bodies are small, so the buffered leg is
     *  right. */
    public static Optional<ProxyFormat.Fetched> read(ProxyFormat.Fetcher fetcher, URI upstream, String database,
                                              String operation, boolean allowInternal) throws IOException {
        return route(fetcher, upstream, database, operation, allowInternal).answer();
    }

    /** The two-route read of {@link #read}, keeping why it produced nothing. A route answering {@code 404}/{@code 410}
     *  is the database saying it has no such record, which declares no digest; an unreachable route or a
     *  {@code 429}/{@code 5xx}/ challenge said nothing, and must not become an unverified fill. The digest is
     *  unreadable only when neither route answered. */
    static Route route(ProxyFormat.Fetcher fetcher, URI upstream, String database, String operation,
                       boolean allowInternal) throws IOException {
        URI base = base(allowInternal);
        boolean answered = false;
        String unreadable = null;
        if (base != null && database.equalsIgnoreCase(base.getAuthority())) {
            URI direct = base.resolve(operation);
            Optional<ProxyFormat.Fetched> response = fetcher.fetch(direct, Map.of());
            if (response.isPresent() && response.get().status() == 200) {
                return new Route(response, null);
            }
            answered = response.isPresent() && miss(response.get().status());
            unreadable = response.isEmpty()
                    ? "the checksum database at " + direct + " could not be reached"
                    : "the checksum database at " + direct + " answered " + response.get().status();
        }
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        URI mirror = URI.create(root + "sumdb/" + database + "/" + operation);
        Optional<ProxyFormat.Fetched> mirrored = fetcher.fetch(mirror, Map.of());
        if (mirrored.isPresent() && mirrored.get().status() == 200) {
            return new Route(mirrored, null);
        }
        if (answered || (mirrored.isPresent() && miss(mirrored.get().status()))) {
            return new Route(Optional.empty(), null);   // a route answered: the database carries no such record
        }
        return new Route(Optional.empty(), mirrored.isEmpty()
                ? (unreadable == null ? "the upstream's checksum-database mirror at " + mirror
                        + " could not be reached" : unreadable)
                : "the upstream's checksum-database mirror at " + mirror + " answered " + mirrored.get().status());
    }

    /** Whether a status is a route's own "no such record", the one absence read as a fact. */
    private static boolean miss(int status) {
        return status == 404 || status == 410;
    }

    /**
     * What a two-route read produced: the {@code 200} answer, or nothing and why.
     *
     * @param answer the {@code 200} response, or empty
     * @param unreadable why no route could be read, or {@code null} when a route answered, a "no such record" included
     */
    record Route(Optional<ProxyFormat.Fetched> answer, String unreadable) {
    }

    /**
     * The dirhashes the checksum database publishes for one module version. {@code escapedModule} and
     * {@code escapedVersion} are the request path's {@code !lower} spelling, which a lookup URL uses; the record spells
     * them unescaped, so the unescaped pair is compared.
     *
     * <p>The three states of {@code ProxyFormat} clause 5: it declares nothing ({@code null}
     * {@link Advertised#dirhashes()}, the fill unverified) when the database is off, an escape is malformed, or the
     * database answered it has no such record; it is {@linkplain Advertised#unreadable() unreadable} when neither route
     * could be read.
     */
    public static Advertised lookup(ProxyFormat.Fetcher fetcher, URI upstream, String escapedModule,
                                      String escapedVersion, boolean allowInternal) throws IOException {
        String database = name(allowInternal);
        if (database == null) {
            return new Advertised(null, null);   // turned off: no digest is advertised to this repository at all
        }
        String module = unescape(escapedModule);
        String version = unescape(escapedVersion);
        if (module == null || version == null) {
            return new Advertised(null, null);   // a malformed escape names no module the database could carry
        }
        Route route = route(fetcher, upstream, database, "lookup/" + escapedModule + "@" + escapedVersion,
                allowInternal);
        if (route.unreadable() != null) {
            return new Advertised(null, route.unreadable());
        }
        if (route.answer().isEmpty()) {
            return new Advertised(null, null);   // the database answered: it carries no record for this version
        }
        Dirhashes hashes = parse(route.answer().get().body(), module, version);
        return new Advertised(hashes.zip() == null && hashes.mod() == null ? null : hashes, null);
    }

    /** What a lookup learned: the published dirhashes ({@code null} when none), or why the database could not be read
     *  ({@code null} when it was). */
    public record Advertised(Dirhashes dirhashes, String unreadable) {
    }

    /** The {@code h1:} lines of a lookup response for one module version: a record id, one
     *  {@code <module> <version> h1:...} line per hashed thing ({@code <version>} for the zip, {@code <version>/go.mod}
     *  for the module file), a blank line and the tree head. Only lines naming exactly this version are read. */
    private static Dirhashes parse(byte[] body, String module, String version) {
        String zip = null;
        String mod = null;
        int line = 0;
        for (String record : new String(body, StandardCharsets.UTF_8).split("\n", -1)) {
            if (record.isEmpty() || ++line > MAX_RECORD_LINES) {
                break;   // the blank line ends the records; the signed tree head below it names no module
            }
            String[] parts = record.split(" ");
            if (parts.length != 3 || !parts[0].equals(module) || !parts[2].startsWith(GoDirhash.PREFIX)) {
                continue;
            }
            if (parts[1].equals(version)) {
                zip = parts[2];
            } else if (parts[1].equals(version + "/go.mod")) {
                mod = parts[2];
            }
        }
        return new Dirhashes(zip, mod);
    }

    /** The unescaped form of a module path or version: the {@code go} client escapes every upper-case letter as
     *  {@code !} and its lower case, since module paths are case-sensitive and file systems may not be. {@code null}
     *  for a malformed escape, which names no module rather than being repaired. */
    public static String unescape(String escaped) {
        StringBuilder unescaped = new StringBuilder(escaped.length());
        for (int index = 0; index < escaped.length(); index++) {
            char character = escaped.charAt(index);
            if (character != '!') {
                if (character >= 'A' && character <= 'Z') {
                    return null;   // an unescaped upper-case letter is not a legal escaped path
                }
                unescaped.append(character);
                continue;
            }
            if (++index >= escaped.length()) {
                return null;
            }
            char escapedCharacter = escaped.charAt(index);
            if (escapedCharacter < 'a' || escapedCharacter > 'z') {
                return null;
            }
            unescaped.append((char) (escapedCharacter - ('a' - 'A')));
        }
        return unescaped.toString();
    }
}
