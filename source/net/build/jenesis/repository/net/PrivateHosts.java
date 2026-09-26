package build.jenesis.repository.net;

import module java.base;

/**
 * The one private-range classifier the SSRF screens share, so the import trigger (an operator-supplied URL) and the
 * {@code ProxyFormat.Fetcher} redirect chain (a 30x {@code Location} an upstream chooses) apply the same rule rather
 * than each carrying its own copy. A host is refused when it resolves to any address an unauthenticated caller must
 * not be able to aim the deployment at: a cloud metadata service ({@code 169.254.169.254}), the loopback control
 * plane ({@code 127.0.0.1}, {@code ::1}), or an internal host on a private, link-local, site-local, multicast,
 * CGNAT or IPv6 unique-local range. A host that does not resolve at all is <em>not</em> refused: it cannot be
 * reached, so it is no SSRF vector, and the caller's own connection attempt then fails naturally rather than this
 * screen masking an honest "no such host".
 *
 * <p><b>What a screen admitted, the connect holds.</b> A screen resolves a name and a client resolves it again when it
 * connects, and a name that rebinds between the two - public for the screen, private a moment later - reaches the
 * address the screen refused. So every screen resolves through {@link #addresses}, which remembers a host whose every
 * address was public, and the product's HTTP client asks {@link #connectable} when it connects: a host admitted in the
 * last {@value #HELD_MINUTES} minutes is held to its public addresses, and one that now answers only private ones is
 * not connected to at all. A host no screen admitted - an operator's own upstream, which may well be internal - is
 * left as it resolves. The memory is JVM-wide because DNS is: an admission made anywhere is a claim about the name
 * everywhere, and it is bounded to the most recent {@value #REMEMBERED} hosts.
 *
 * <p><b>The table is here; the policy is not.</b> This module carries nothing but {@code java.base}, deliberately,
 * so that anything above it may require it - which is the point: the classifier previously lived in the format SPI,
 * and a caller that could not reach that SPI kept a second copy of the same ranges instead. What each caller does
 * about a host that will not resolve, or a URI with no host at all, is theirs and genuinely differs - the format
 * legs admit, the downstream webhook and forwarding guards refuse. Only the range question lives here, because
 * that is the half where two answers is a defect rather than a decision.
 */
public final class PrivateHosts {

    /** How long an admission holds a host to public addresses: far longer than any screen-to-connect gap. */
    static final int HELD_MINUTES = 10;

    /** How many admitted hosts are remembered, the least recently admitted forgotten first. */
    static final int REMEMBERED = 65_536;

    private static final Map<String, Instant> ADMITTED = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
                    return size() > REMEMBERED;
                }
            });

    private PrivateHosts() {
    }

    /**
     * Resolve {@code host} for a screen, remembering it as admitted when every address it resolves to is public - the
     * one resolution every private-address screen goes through, so the connect can hold what the screen saw.
     *
     * @throws UnknownHostException when the host does not resolve
     */
    public static InetAddress[] addresses(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length > 0 && Arrays.stream(addresses).noneMatch(PrivateHosts::isPrivate)) {
            ADMITTED.put(key(host), Instant.now());
        }
        return addresses;
    }

    /**
     * The addresses a connection to {@code host} may use, of the ones it resolved to now: all of them for a host no
     * screen admitted recently, and only the public ones for a host one did - which is empty when the name has
     * rebound to private addresses since, and the caller then refuses the connection.
     */
    public static List<InetAddress> connectable(String host, List<InetAddress> resolved) {
        Instant admitted = ADMITTED.get(key(host));
        if (admitted == null || admitted.plus(Duration.ofMinutes(HELD_MINUTES)).isBefore(Instant.now())) {
            return resolved;
        }
        return resolved.stream().filter(address -> !isPrivate(address)).toList();
    }

    /** A host as the memory keys it: lower case, an IPv6 literal without its brackets. */
    private static String key(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return bare.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether {@code host} resolves to any address an SSRF screen must refuse. {@code true} when at least one of the
     * host's resolved addresses is {@link #isPrivate private}; {@code false} for a {@code null}/blank host or one
     * that does not resolve (unreachable, so not a vector - a caller lets the natural failure surface). A host this
     * answers {@code false} for is {@linkplain #addresses admitted}, so a client connecting through
     * {@link #connectable} cannot be rebound onto a private address afterwards.
     */
    public static boolean resolvesToPrivate(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        InetAddress[] addresses;
        try {
            addresses = addresses(host);
        } catch (UnknownHostException _) {
            return false;
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A private/loopback/wildcard/link-local/site-local/multicast/CGNAT/unique-local address an SSRF screen must not
     * reach. The JDK classifiers cover loopback, the wildcard, link-local ({@code 169.254/16}, {@code fe80::/10}),
     * site-local ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}) and multicast; CGNAT ({@code 100.64/10},
     * RFC 6598) and IPv6 unique-local ({@code fc00::/7}, RFC 4193) are checked by hand as the JDK does not recognise
     * them.
     */
    public static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF, second = bytes[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        }
        return (bytes[0] & 0xFE) == 0xFC;
    }
}
