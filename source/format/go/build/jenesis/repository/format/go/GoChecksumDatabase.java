package build.jenesis.repository.format.go;

import module java.base;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.Features;

/**
 * The Go ecosystem's checksum database, as this repository reaches it - the service that answers "what is the
 * {@code h1:} dirhash of module M at version V" and therefore the <em>only</em> place a GOPROXY mirror can learn what a
 * proxied {@code .mod} or {@code .zip} is supposed to hash to.
 *
 * <h2>Why this is a second upstream, and not a corner of the first</h2>
 * The GOPROXY protocol advertises no digest at all: {@code <module>/@v/<version>.info}, {@code .mod} and {@code .zip}
 * are served with no checksum sibling, no digest header and no content-addressed reference. Go's integrity guarantee
 * lives in a separate service (the {@code GOSUMDB}, {@code sum.golang.org} by default), which the {@code go} client
 * consults itself. A proxy <em>may</em> mirror it under {@code <proxy>/sumdb/<name>/...}, and the protocol tells a
 * client to prefer that route - but the canonical public proxy does not implement it (a {@code /sumdb/.../supported}
 * against {@code proxy.golang.org} is a {@code 404}), so a repository that only ever spoke to its configured GOPROXY
 * could obtain no digest for anything. That is why the database is configured in its own right.
 *
 * <p><b>Direct first, the upstream's mirror second.</b> Reaching the database directly is both cheaper (a mirror that
 * does not implement it costs a wasted round trip) and <em>stronger</em>: the digest then comes from an origin
 * independent of the one that served the bytes, so a compromised or merely broken GOPROXY cannot make its own body and
 * its own digest agree. The mirror route is the fallback for the deployment whose only egress is its configured
 * upstream.
 *
 * <h2>What this check is, and is not</h2>
 * The record returned by a {@code lookup} is read out of the response and compared; the note's Ed25519 signature and
 * the tile-based inclusion proof that would bind that record to the signed tree head are <b>not</b> verified. Verifying
 * the signature alone would prove nothing about the record (it signs a tree head, not a line), and the proof needs the
 * whole transparency-log client. So this leg is a digest check against what the checksum database says, not a
 * transparency-log attestation - which is exactly why {@link GoFormat} also relays {@code /go/sumdb/...} so a client
 * can run the full check itself, against the same database, through this repository.
 *
 * <h2>Configuration, and the screen on it</h2>
 * {@code jenreg.go.sumdb} is the database's base URL, {@code https://sum.golang.org/} by default and {@code off} to
 * disable. It is deploy-time configuration in the {@code jenreg.<feature>.<property>} convention: which hosts a
 * deployment may reach is an egress decision made where the process is started, not a runtime dial, and an air-gapped
 * deployment sets it {@code off} so no fill waits on a name that cannot resolve.
 *
 * <p>It was also, until, the last <b>operator-configured outbound target in the product with no screen at all</b>.
 * {@link #base(boolean)} did the &sect;9 half well - a value that is not an http(s) base URL throws at read, naming the
 * key, so an operator who misspelt it is not left believing verification is running - and then admitted plain
 * {@code http}. No credential rides to the checksum database, so this is not the earlier credential-in-cleartext hazard;
 * it is the other one, and on this leg it is sharper. A cleartext {@code lookup} is an active intermediary's
 * opportunity to answer <em>every</em> integrity question itself, and this repository then holds a proxied {@code .zip}
 * to whatever that answer said - so one attacker on the path chooses both the module bytes (from a cleartext GOPROXY)
 * and the digest they are checked against, and the check reports success. The screen is therefore
 * {@link OutboundTargets#configuredRefusal}, the <em>same</em> rule the proxy upstream runs under the
 * <em>same</em> {@link ProxyLeg#ALLOW_INTERNAL} dial, rather than a second spelling of it.
 *
 * <p><b>The transport half only, for the earlier reason and one of this leg's own.</b> An operator running their own
 * checksum database on an internal address is a legitimate deployment - the same judgement made about an
 * internal upstream mirror - and the host half would put a DNS resolution on the path of every {@code lookup} to
 * re-decide a value that was fixed when the process started. The capability floor beneath it is not a policy question
 * and the dial does not lift it.
 */
public final class GoChecksumDatabase {

    /** The key an operator points at their own checksum database, or sets to {@code off}. */
    public static final String DATABASE_KEY = "jenreg.go.sumdb";

    /** The ecosystem's own default, and the value {@code GOSUMDB} carries when nothing sets it. */
    private static final String DEFAULT_DATABASE = "https://sum.golang.org/";

    /** The operations the GOPROXY protocol defines under {@code /sumdb/<name>/}; anything else is not relayed, so the
     *  path a client supplies can never become an arbitrary request against the database. */
    private static final List<String> OPERATIONS = List.of("supported", "latest", "lookup/", "tile/");

    /** How many lines of a lookup response are read before it stops being a record and starts being a body someone is
     *  feeding us; the record itself is three lines and a signed tree head follows a blank one. */
    private static final int MAX_RECORD_LINES = 64;

    private GoChecksumDatabase() {
        throw new UnsupportedOperationException("GoChecksumDatabase is a static utility");
    }

    /** The two dirhashes a lookup publishes for one module version: the {@code .zip}'s and the {@code go.mod}'s. Either
     *  may be {@code null} when the record does not carry it. */
    public record Dirhashes(String zip, String mod) {
    }

    /**
     * The configured database's base URL, or {@code null} when a deployment turned it off - in which case no digest is
     * advertised to this repository at all and a proxied module is cached unverified, which {@link GoFormat} declares.
     *
     * @param allowInternal the deployment's {@link ProxyLeg#ALLOW_INTERNAL} dial, threaded from the exchange exactly
     *                      as the enumeration walks thread it: this class has no {@code FormatExchange} to read
     *                      it from, and a second read of the dial would be a second chance for the two to disagree
     * @throws IllegalArgumentException when the key is set to something that is not an {@code http}/{@code https} URL,
     *         or to a cleartext one this deployment has not opted into - an operator who pointed at their own database
     *         and got the spelling (or the scheme) wrong must not be left believing the verification is running
     *         (&sect;9). It throws rather than declining because this is a <em>configuration</em> fault and not an
     *         upstream-chosen target: the same shape the malformed-value refusal beside it has always had
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
        // the screen this operator-configured outbound target never had, and it is the proxy upstream's screen
        // rather than a private one - one rule, one wording, one dial for both of the edition's configured roots.
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

    /** The name the configured database answers to in a {@code /sumdb/<name>/} path - its authority, which is what the
     *  {@code go} client spells there. {@code null} when the database is off. */
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

    /**
     * Read one checksum-database path ({@code supported}, {@code latest}, {@code lookup/...}, {@code tile/...}) - the
     * configured database directly where the name matches it, otherwise the configured GOPROXY's own mirror of it.
     * Empty when neither answers.
     *
     * <p>{@code database} is the name off the request path for the relay leg and the configured one for a verification
     * lookup. It is only ever spliced into the <em>path</em> of the operator-configured upstream, never into a host, so
     * a client cannot aim this at a host of its choosing; the direct leg is taken only for the one name the operator
     * configured. Small bodies throughout (a record, a signed tree head, a tile), so the buffered leg is the right one
     * and carries the transport's own response ceiling.
     */
    public static Optional<ProxyFormat.Fetched> read(ProxyFormat.Fetcher fetcher, URI upstream, String database,
                                              String operation, boolean allowInternal) throws IOException {
        return route(fetcher, upstream, database, operation, allowInternal).answer();
    }

    /**
     * The same two-route read as {@link #read}, but keeping <em>why</em> it produced nothing - which the relay leg does
     * not need and a verification lookup must not lose. A route that answered {@code 404}/{@code 410} is the
     * database saying it carries no such record, and a record it does not carry declares no digest; a route that could
     * not be reached, or that answered a {@code 429}/{@code 5xx}/challenge, said nothing at all, and treating that as
     * "the database publishes no dirhash for this module" is what turned a network blip into an unverified cache fill.
     *
     * <p>The verdict is over <em>both</em> routes: the digest is unreadable only when neither the direct database nor
     * the upstream's mirror of it answered the question. One route missing while the other says "no such record" is the
     * database answering.
     */
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

    /** Whether a status is the route's own answer that it carries no such record - the one absence a caller may read as
     *  a fact, mirroring {@code ProxyRelay.upstreamMiss} for the two routes this class owns. */
    private static boolean miss(int status) {
        return status == 404 || status == 410;
    }

    /** What a two-route checksum-database read produced: the {@code 200} answer, or nothing - and, when nothing, why.
     *
     *  @param answer     the {@code 200} response, or empty
     *  @param unreadable why no route could be read at all, or {@code null} when a route answered (including one that
     *                    answered "no such record") */
    record Route(Optional<ProxyFormat.Fetched> answer, String unreadable) {
    }

    /**
     * The dirhashes the checksum database publishes for one module version. {@code escapedModule} and
     * {@code escapedVersion} are the request path's own spelling (the {@code !lower} case escaping a {@code go} client
     * applies), which is also how a lookup URL spells them; the record body spells them back out unescaped, so the
     * comparison happens on the unescaped pair.
     *
     * <p>The three states are the ones {@code ProxyFormat} clause 5 needs kept apart. It <b>declares nothing</b>
     * ({@code null} {@link Advertised#dirhashes()}, so the fill goes ahead unverified, which {@link GoFormat} states)
     * for a deployment that turned the database {@code off}, a malformed escape naming no module, and a database that
     * <em>answered</em> that it carries no such record. It is {@linkplain Advertised#unreadable() unreadable} when
     * neither the database nor the upstream's mirror of it could be read at all - not a module the database does not
     * carry, and not a licence to cache the module unverified.
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

    /** What a lookup learned: the dirhashes the database publishes for the module version ({@code null} when it
     *  publishes none), or why the database could not be read at all ({@code null} when it was). */
    public record Advertised(Dirhashes dirhashes, String unreadable) {
    }

    /**
     * The {@code h1:} lines of a lookup response for one module version. The body is a signed note: a record id, then
     * one {@code <module> <version> h1:...} line per hashed thing ({@code <version>} for the zip,
     * {@code <version>/go.mod} for the module file), then a blank line and the tree head. Only lines naming exactly
     * this module version are read, so a record that also carries a neighbouring version contributes nothing.
     */
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

    /**
     * The unescaped form of a module path or version as a request path spells it: the {@code go} client escapes every
     * upper-case letter as {@code !} plus its lower-case form, because module paths are case-sensitive while many file
     * systems are not. {@code null} when the escaping is malformed (a trailing {@code !}, or one before something that
     * is not a lower-case letter) - which names no module rather than being silently repaired.
     */
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
