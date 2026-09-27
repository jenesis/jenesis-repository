package build.jenesis.repository.server.spi;

import module java.base;

/**
 * Which address a request came from, and whether an address lies in a listed range: the two questions a source-IP
 * allowlist and a trusted reverse proxy both ask.
 *
 * <p>A range is a {@code network/bits} CIDR or a plain address matched in full, IPv4 or IPv6. Both sides are parsed
 * with {@link InetAddress#getByName}, which accepts a numeric literal without a DNS lookup - exactly the inputs an
 * allowlist and a client address carry - and anything that does not parse matches nothing.
 */
public final class ClientAddresses {

    private ClientAddresses() {
    }

    /** The real client address for a request given its TCP {@code peer} and any {@code X-Forwarded-For}. A forwarded
     *  header is trusted only when {@code peer} is itself one of {@code trustedProxies}: then the rightmost forwarded
     *  hop that is not also a trusted proxy is the client (walking back through the proxy chain). Otherwise the peer is
     *  the client - a forwarded header from an untrusted source is ignored, so the source-IP allowlist cannot be
     *  spoofed by a client that sets its own {@code X-Forwarded-For}. */
    public static String resolve(String peer, String forwardedFor, List<String> trustedProxies) {
        if (!trustedProxy(peer, trustedProxies)) {
            return peer;
        }
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return peer;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !withinAny(hop, trustedProxies)) {
                return hop;
            }
        }
        return peer;
    }

    /** Whether {@code peer} is one of the deployment's trusted reverse proxies - the one condition under which any
     *  header the peer forwarded ({@code X-Forwarded-For}, {@code X-Forwarded-Proto}, {@code X-Forwarded-Host}) is
     *  believed. An empty or absent list trusts nobody. */
    public static boolean trustedProxy(String peer, List<String> trustedProxies) {
        return peer != null && trustedProxies != null && !trustedProxies.isEmpty() && withinAny(peer, trustedProxies);
    }

    /** Whether a comma-separated allowlist admits {@code address}: it falls in a listed CIDR or matches a listed
     *  plain address. A {@code null} address is admitted by nothing. */
    static boolean admits(String allowlist, String address) {
        if (address == null) {
            return false;
        }
        for (String cidr : allowlist.split(",")) {
            if (inRange(address.trim(), cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean withinAny(String address, List<String> cidrs) {
        for (String cidr : cidrs) {
            if (inRange(address, cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code address} lies within {@code cidr}, comparing the {@code bits} most-significant bits of the
     *  network and address bytes. IPv4 and IPv6 never match each other (their byte lengths differ); a malformed
     *  CIDR, a bad prefix length, or an unparseable/blank address never matches. A bare address is a full-length
     *  (/32 or /128) match. */
    private static boolean inRange(String address, String cidr) {
        try {
            if (address == null || cidr == null || cidr.isBlank()) {
                return false;
            }
            int slash = cidr.indexOf('/');
            String networkText = (slash < 0 ? cidr : cidr.substring(0, slash)).trim();
            if (networkText.isEmpty()) {
                return false;
            }
            byte[] network = InetAddress.getByName(networkText).getAddress();
            byte[] target = InetAddress.getByName(address.trim()).getAddress();
            if (network.length != target.length) {
                return false;                     // an IPv4 range never covers an IPv6 address, or vice versa
            }
            int maxBits = network.length * 8;
            int bits = slash < 0 ? maxBits : Integer.parseInt(cidr.substring(slash + 1).trim());
            if (bits < 0 || bits > maxBits) {
                return false;
            }
            int fullBytes = bits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != target[i]) {
                    return false;
                }
            }
            int remainingBits = bits % 8;
            if (remainingBits != 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((network[fullBytes] & mask) != (target[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException | UnknownHostException failure) {
            return false;
        }
    }
}
