package build.jenesis.repository.format.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.net.PrivateHosts;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSRF classifier against the special-purpose address registries: one address in every entry of IANA's IPv4 and
 * IPv6 registries, each answered as the registry's "globally reachable" column says, the blocks that carry an IPv4
 * address judged by the one they carry, and the range boundaries pinned where a hand-written check once stood. Each
 * address is a literal parsed without DNS, so the answers are the classifier's rather than a resolver's.
 *
 * <p>The remaining cases pin {@link PrivateHosts#resolvesToPrivate} directly - the host-string SSRF entry point the
 * import trigger and the fetcher's redirect screen actually call - across its three contractual answers: a
 * {@code null}/blank host is not a vector ({@code false}); a host that resolves to any private/loopback/link-local
 * address is refused ({@code true}); and, most importantly, a host that does not resolve at all is <em>not</em>
 * refused ({@code false}) - it is unreachable, so the caller's own attempt fails naturally rather than this screen
 * masking an honest "no such host". Literal IPs are parsed without DNS, so the private/public assertions stay
 * deterministic.
 */
class PrivateHostsTest {

    private static boolean isPrivate(String literal) throws UnknownHostException {
        return PrivateHosts.isPrivate(address(literal));
    }

    /** A literal as an address, an IPv4-mapped one kept IPv6 - the JDK would hand it back as IPv4. */
    private static InetAddress address(String literal) throws UnknownHostException {
        InetAddress parsed = InetAddress.getByName(literal);
        if (parsed instanceof Inet4Address && literal.contains(":")) {
            byte[] mapped = new byte[16];
            mapped[10] = (byte) 0xFF;
            mapped[11] = (byte) 0xFF;
            System.arraycopy(parsed.getAddress(), 0, mapped, 12, 4);
            return Inet6Address.getByAddress(null, mapped, -1);
        }
        return parsed;
    }

    /** One address per registry entry, with whether a screen refuses it. */
    private static final List<Map.Entry<String, Boolean>> REGISTRY = List.of(
            // IPv4 Special-Purpose Address Registry, and multicast
            Map.entry("0.1.2.3", true), // "This network"
            Map.entry("0.0.0.0", true), // "This host on this network"
            Map.entry("10.1.2.3", true), // Private-Use
            Map.entry("100.64.1.1", true), // Shared Address Space
            Map.entry("127.0.0.2", true), // Loopback
            Map.entry("169.254.169.254", true), // Link Local
            Map.entry("172.16.5.4", true), // Private-Use
            Map.entry("192.0.0.100", true), // IETF Protocol Assignments
            Map.entry("192.0.0.1", true), // IPv4 Service Continuity Prefix
            Map.entry("192.0.0.8", true), // IPv4 dummy address
            Map.entry("192.0.0.9", false), // Port Control Protocol Anycast
            Map.entry("192.0.0.10", false), // Traversal Using Relays around NAT Anycast
            Map.entry("192.0.0.170", true), // NAT64/DNS64 Discovery
            Map.entry("192.0.0.171", true), // NAT64/DNS64 Discovery
            Map.entry("192.0.2.1", true), // Documentation (TEST-NET-1)
            Map.entry("192.31.196.1", false), // AS112-v4
            Map.entry("192.52.193.1", false), // AMT
            Map.entry("192.88.99.1", true), // Deprecated (6to4 Relay Anycast)
            Map.entry("192.88.99.2", true), // 6a44-relay anycast address
            Map.entry("192.168.1.1", true), // Private-Use
            Map.entry("192.175.48.1", false), // Direct Delegation AS112 Service
            Map.entry("198.18.0.1", true), // Benchmarking
            Map.entry("198.19.255.254", true), // Benchmarking, the far end of the /15
            Map.entry("198.51.100.7", true), // Documentation (TEST-NET-2)
            Map.entry("203.0.113.7", true), // Documentation (TEST-NET-3)
            Map.entry("240.0.0.1", true), // Reserved
            Map.entry("255.255.255.255", true), // Limited Broadcast
            Map.entry("224.0.0.1", true), // Multicast
            Map.entry("239.255.255.250", true), // Multicast
            Map.entry("8.8.8.8", false), // an internet host
            Map.entry("198.20.0.1", false), // just past Benchmarking
            // IPv6 Special-Purpose Address Registry
            Map.entry("::1", true), // Loopback Address
            Map.entry("::", true), // Unspecified Address
            Map.entry("::ffff:127.0.0.1", true), // IPv4-mapped, carrying loopback
            Map.entry("::ffff:8.8.8.8", false), // IPv4-mapped, carrying an internet host
            Map.entry("64:ff9b::a9fe:a9fe", true), // NAT64, carrying the metadata service
            Map.entry("64:ff9b::808:808", false), // NAT64, carrying an internet host
            Map.entry("64:ff9b:1:a00:0:100::", true), // local-use NAT64, carrying 10.0.0.1 around the u octet
            Map.entry("64:ff9b:1:808:8:800::", false), // local-use NAT64, carrying 8.8.8.8
            Map.entry("100::1", true), // Discard-Only Address Block
            Map.entry("100:0:0:1::1", true), // Dummy IPv6 Prefix
            Map.entry("2001:0:4136:e378:8000:63bf:3fff:fdd2", true), // TEREDO
            Map.entry("2001:1::1", false), // Port Control Protocol Anycast
            Map.entry("2001:1::2", false), // Traversal Using Relays around NAT Anycast
            Map.entry("2001:1::3", false), // DNS-SD Service Registration Protocol Anycast
            Map.entry("2001:1::4", true), // IETF Protocol Assignments
            Map.entry("2001:2::1", true), // Benchmarking
            Map.entry("2001:3::1", false), // AMT
            Map.entry("2001:4:112::1", false), // AS112-v6
            Map.entry("2001:10::1", true), // Deprecated (previously ORCHID)
            Map.entry("2001:20::1", false), // ORCHIDv2
            Map.entry("2001:30::1", false), // Drone Remote ID Protocol Entity Tags (DETs) Prefix
            Map.entry("2001:db8::1", true), // Documentation
            Map.entry("2002:a00:1::", true), // 6to4, carrying 10.0.0.1
            Map.entry("2002:808:808::", false), // 6to4, carrying 8.8.8.8
            Map.entry("2620:4f:8000::1", false), // Direct Delegation AS112 Service
            Map.entry("3fff::1", true), // Documentation
            Map.entry("5f00::1", true), // Segment Routing (SRv6) SIDs
            Map.entry("fc00::1", true), // Unique-Local
            Map.entry("fe80::1", true), // Link-Local Unicast
            // outside global unicast and every block
            Map.entry("fec0::1", true), // the deprecated site-local block
            Map.entry("ff02::1", true), // multicast
            Map.entry("::a00:1", true), // IPv4-compatible
            Map.entry("4000::1", true), // the IETF's reserve
            Map.entry("2001:4860:4860::8888", false)); // an internet host

    @Test
    void every_special_purpose_block_is_answered_as_the_registry_says() throws UnknownHostException {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : REGISTRY) {
            if (isPrivate(entry.getKey()) != entry.getValue()) {
                wrong.add(entry.getKey() + (entry.getValue() ? " was admitted" : " was refused"));
            }
        }

        assertThat(wrong).isEmpty();
    }

    @Test
    void a_null_or_blank_host_is_not_a_vector() {
        assertThat(PrivateHosts.resolvesToPrivate(null)).as("null host").isFalse();
        assertThat(PrivateHosts.resolvesToPrivate("")).as("empty host").isFalse();
        assertThat(PrivateHosts.resolvesToPrivate("   ")).as("blank host").isFalse();
    }

    @Test
    void a_host_resolving_to_a_private_loopback_or_link_local_address_is_refused() {
        assertThat(PrivateHosts.resolvesToPrivate("127.0.0.1")).as("IPv4 loopback").isTrue();
        assertThat(PrivateHosts.resolvesToPrivate("::1")).as("IPv6 loopback").isTrue();
        assertThat(PrivateHosts.resolvesToPrivate("169.254.169.254")).as("the link-local cloud metadata service").isTrue();
        assertThat(PrivateHosts.resolvesToPrivate("10.0.0.1")).as("a site-local (private) address").isTrue();
        assertThat(PrivateHosts.resolvesToPrivate("192.168.1.1")).as("another site-local address").isTrue();
        assertThat(PrivateHosts.resolvesToPrivate("localhost")).as("the name that resolves to loopback").isTrue();
    }

    @Test
    void a_host_resolving_to_a_public_address_is_allowed() {
        assertThat(PrivateHosts.resolvesToPrivate("8.8.8.8")).as("an ordinary public v4 address").isFalse();
        assertThat(PrivateHosts.resolvesToPrivate("2001:4860:4860::8888")).as("a public v6 address").isFalse();
    }

    @Test
    void an_unresolvable_host_is_not_refused_so_the_natural_failure_can_surface() {
        // The javadoc calls this out explicitly: a host that does not resolve is no SSRF vector (nothing to reach), so
        // the screen must pass it through as public rather than refuse it - letting the caller's own connection attempt
        // fail with an honest "no such host" instead of this guard masking it. .invalid is reserved never to resolve.
        assertThat(PrivateHosts.resolvesToPrivate("no-such-host.invalid")).as("an unresolvable host").isFalse();
        assertThat(PrivateHosts.resolvesToPrivate("nothing.here.invalid")).isFalse();
    }

    @Test
    void the_cgnat_range_is_private_at_and_within_its_boundaries() throws UnknownHostException {
        assertThat(isPrivate("100.64.0.0")).as("the low boundary of 100.64/10").isTrue();
        assertThat(isPrivate("100.64.0.1")).isTrue();
        assertThat(isPrivate("100.127.255.254")).isTrue();
        assertThat(isPrivate("100.127.255.255")).as("the high boundary of 100.64/10").isTrue();
    }

    @Test
    void an_address_just_outside_the_cgnat_range_is_public() throws UnknownHostException {
        assertThat(isPrivate("100.63.255.255")).as("just below the CGNAT block").isFalse();
        assertThat(isPrivate("100.128.0.0")).as("just above the CGNAT block").isFalse();
        assertThat(isPrivate("8.8.8.8")).as("an ordinary public v4 address").isFalse();
    }

    @Test
    void the_ipv6_unique_local_range_is_private() throws UnknownHostException {
        assertThat(isPrivate("fc00::1")).as("the fc00::/8 half of fc00::/7").isTrue();
        assertThat(isPrivate("fd00::1")).as("the fd00::/8 half (the locally-assigned form)").isTrue();
        assertThat(isPrivate("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")).isTrue();
    }

    @Test
    void a_public_ipv6_address_is_not_private() throws UnknownHostException {
        assertThat(isPrivate("2001:4860:4860::8888")).as("a public v6 address (Google DNS)").isFalse();
        assertThat(isPrivate("2606:4700:4700::1111")).as("another public v6 address (Cloudflare)").isFalse();
    }
}
