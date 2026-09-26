package build.jenesis.repository.net;

import module java.base;

/**
 * The one private-range classifier the SSRF screens share, so the import trigger (an operator-supplied URL) and the
 * {@code ProxyFormat.Fetcher} redirect chain (a 30x {@code Location} an upstream chooses) apply the same rule rather
 * than each carrying its own copy. A host is refused when it resolves to any address an unauthenticated caller must
 * not be able to aim the deployment at: a cloud metadata service ({@code 169.254.169.254}), the loopback control
 * plane ({@code 127.0.0.1}, {@code ::1}), or any address the special-purpose registries do not call globally
 * reachable ({@link #isPrivate}). A host that does not resolve at all is <em>not</em> refused: it cannot be
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
     * Whether {@code address} is one a screen must refuse: an address that is not an internet host's. The rule is
     * the one IANA's special-purpose address registries state, entry by entry, for both families - an address in a
     * block the registry does not mark globally reachable is refused, and one in a block it does is not - and the
     * table below is those registries as they stood on 2026-09-26, the most specific block deciding. Three kinds of
     * block say nothing about reach themselves and are judged by the IPv4 address they carry: an IPv4-mapped
     * address, the two NAT64 prefixes ({@code 64:ff9b::/96}, and the local-use {@code 64:ff9b:1::/48}, which puts
     * the IPv4 address either side of the octet RFC 6052 reserves), and 6to4 ({@code 2002::/16}) - since through a
     * translator or a relay, it is that IPv4 address a connection reaches. Teredo is refused: its IPv4 address is
     * obfuscated and names a client behind a NAT, which is never an upstream.
     *
     * <p>Outside every block, an IPv4 address is public, and an IPv6 address is public only within global unicast,
     * {@code 2000::/3}: the rest of that space is multicast, the deprecated site-local block, IPv4-compatible
     * addresses or the IETF's reserve, none of them an internet host. IPv4 multicast ({@code 224.0.0.0/4}) is in the
     * multicast registry rather than the special-purpose one, and is refused here with it.
     */
    public static boolean isPrivate(InetAddress address) {
        return refused(address.getAddress());
    }

    /** Whether an address in a block may be connected to: never, always, or as the IPv4 address it carries says. */
    private enum Reach { NEVER, ALWAYS, EMBEDDED }

    /**
     * One entry of a special-purpose registry.
     *
     * @param embedded for {@link Reach#EMBEDDED}, the indices of the carried IPv4 address's four octets
     */
    private record Block(byte[] prefix, int length, String name, Reach reach, int... embedded) {

        boolean contains(byte[] address) {
            if (address.length != prefix.length) {
                return false;
            }
            int whole = length / 8, rest = length % 8;
            for (int index = 0; index < whole; index++) {
                if (address[index] != prefix[index]) {
                    return false;
                }
            }
            return rest == 0 || ((address[whole] ^ prefix[whole]) & (0xFF << (8 - rest)) & 0xFF) == 0;
        }
    }

    private static final List<Block> BLOCKS = List.of(
            // IANA IPv4 Special-Purpose Address Registry
            block("0.0.0.0/8", "This network", Reach.NEVER),
            block("0.0.0.0/32", "This host on this network", Reach.NEVER),
            block("10.0.0.0/8", "Private-Use", Reach.NEVER),
            block("100.64.0.0/10", "Shared Address Space", Reach.NEVER),
            block("127.0.0.0/8", "Loopback", Reach.NEVER),
            block("169.254.0.0/16", "Link Local", Reach.NEVER),
            block("172.16.0.0/12", "Private-Use", Reach.NEVER),
            block("192.0.0.0/24", "IETF Protocol Assignments", Reach.NEVER),
            block("192.0.0.0/29", "IPv4 Service Continuity Prefix", Reach.NEVER),
            block("192.0.0.8/32", "IPv4 dummy address", Reach.NEVER),
            block("192.0.0.9/32", "Port Control Protocol Anycast", Reach.ALWAYS),
            block("192.0.0.10/32", "Traversal Using Relays around NAT Anycast", Reach.ALWAYS),
            block("192.0.0.170/32", "NAT64/DNS64 Discovery", Reach.NEVER),
            block("192.0.0.171/32", "NAT64/DNS64 Discovery", Reach.NEVER),
            block("192.0.2.0/24", "Documentation (TEST-NET-1)", Reach.NEVER),
            block("192.31.196.0/24", "AS112-v4", Reach.ALWAYS),
            block("192.52.193.0/24", "AMT", Reach.ALWAYS),
            block("192.88.99.0/24", "Deprecated (6to4 Relay Anycast)", Reach.NEVER),
            block("192.88.99.2/32", "6a44-relay anycast address", Reach.NEVER),
            block("192.168.0.0/16", "Private-Use", Reach.NEVER),
            block("192.175.48.0/24", "Direct Delegation AS112 Service", Reach.ALWAYS),
            block("198.18.0.0/15", "Benchmarking", Reach.NEVER),
            block("198.51.100.0/24", "Documentation (TEST-NET-2)", Reach.NEVER),
            block("203.0.113.0/24", "Documentation (TEST-NET-3)", Reach.NEVER),
            block("240.0.0.0/4", "Reserved", Reach.NEVER),
            block("255.255.255.255/32", "Limited Broadcast", Reach.NEVER),
            // IANA IPv4 Multicast Address Space Registry
            block("224.0.0.0/4", "Multicast", Reach.NEVER),
            // IANA IPv6 Special-Purpose Address Registry
            block("::1/128", "Loopback Address", Reach.NEVER),
            block("::/128", "Unspecified Address", Reach.NEVER),
            block("::ffff:0:0/96", "IPv4-mapped Address", Reach.EMBEDDED, 12, 13, 14, 15),
            block("64:ff9b::/96", "IPv4-IPv6 Translat.", Reach.EMBEDDED, 12, 13, 14, 15),
            block("64:ff9b:1::/48", "IPv4-IPv6 Translat.", Reach.EMBEDDED, 6, 7, 9, 10),
            block("100::/64", "Discard-Only Address Block", Reach.NEVER),
            block("100:0:0:1::/64", "Dummy IPv6 Prefix", Reach.NEVER),
            block("2001::/23", "IETF Protocol Assignments", Reach.NEVER),
            block("2001::/32", "TEREDO", Reach.NEVER),
            block("2001:1::1/128", "Port Control Protocol Anycast", Reach.ALWAYS),
            block("2001:1::2/128", "Traversal Using Relays around NAT Anycast", Reach.ALWAYS),
            block("2001:1::3/128", "DNS-SD Service Registration Protocol Anycast", Reach.ALWAYS),
            block("2001:2::/48", "Benchmarking", Reach.NEVER),
            block("2001:3::/32", "AMT", Reach.ALWAYS),
            block("2001:4:112::/48", "AS112-v6", Reach.ALWAYS),
            block("2001:10::/28", "Deprecated (previously ORCHID)", Reach.NEVER),
            block("2001:20::/28", "ORCHIDv2", Reach.ALWAYS),
            block("2001:30::/28", "Drone Remote ID Protocol Entity Tags (DETs) Prefix", Reach.ALWAYS),
            block("2001:db8::/32", "Documentation", Reach.NEVER),
            block("2002::/16", "6to4", Reach.EMBEDDED, 2, 3, 4, 5),
            block("2620:4f:8000::/48", "Direct Delegation AS112 Service", Reach.ALWAYS),
            block("3fff::/20", "Documentation", Reach.NEVER),
            block("5f00::/16", "Segment Routing (SRv6) SIDs", Reach.NEVER),
            block("fc00::/7", "Unique-Local", Reach.NEVER),
            block("fe80::/10", "Link-Local Unicast", Reach.NEVER));

    private static boolean refused(byte[] address) {
        Block match = null;
        for (Block block : BLOCKS) {
            if (block.contains(address) && (match == null || block.length() > match.length())) {
                match = block;
            }
        }
        if (match == null) {
            return address.length == 16 && (address[0] & 0xE0) != 0x20;
        }
        return switch (match.reach()) {
            case NEVER -> true;
            case ALWAYS -> false;
            case EMBEDDED -> {
                byte[] carried = new byte[4];
                for (int octet = 0; octet < 4; octet++) {
                    carried[octet] = address[match.embedded()[octet]];
                }
                yield refused(carried);
            }
        };
    }

    private static Block block(String cidr, String name, Reach reach, int... embedded) {
        int slash = cidr.indexOf('/');
        return new Block(literal(cidr.substring(0, slash)), Integer.parseInt(cidr.substring(slash + 1)), name, reach,
                embedded);
    }

    /** An address literal's bytes, an IPv6 one always sixteen - the JDK reads an IPv4-mapped literal as IPv4. */
    private static byte[] literal(String literal) {
        byte[] bytes;
        try {
            bytes = InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException malformed) {
            throw new IllegalArgumentException(literal, malformed);
        }
        if (bytes.length == 4 && literal.contains(":")) {
            byte[] mapped = new byte[16];
            mapped[10] = (byte) 0xFF;
            mapped[11] = (byte) 0xFF;
            System.arraycopy(bytes, 0, mapped, 12, 4);
            return mapped;
        }
        return bytes;
    }
}
