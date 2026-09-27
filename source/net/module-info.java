/**
 * The address ranges a deployment must not be steered into: every block the special-purpose address registries do
 * not call globally reachable - loopback, link-local, private-use, shared, benchmarking, documentation, reserved,
 * multicast and the IPv6 unique-local block among them - with the translation prefixes judged by the address they
 * carry.
 *
 * <p>It is its own module, and a tiny one, because the callers that need it sit on both sides of a boundary
 * neither should have to cross. In the format SPI, the webhook, forwarding and emulator legs in {@code settings}
 * could not reach {@code PrivateHosts} without dragging that SPI - dozens of test modules of reach - along for a
 * range table. The alternative is a second private copy of the same ranges, and that is not a stylistic
 * duplication: an inline copy that omits carrier-grade NAT or multicast is a real SSRF gap, and a range added to
 * one table and not the other is invisible until something reaches the wrong half.
 *
 * <p><b>The table is shared; the policy is not.</b> What the two sides do about a host that will not resolve, or
 * a URI with no host at all, genuinely differs - the format legs admit, the downstream guard refuses - and that
 * difference is deliberate rather than drift. It stays with each caller. Only the question "is this address in a
 * range nobody should be steered into" lives here, which is the half that must never disagree.
 *
 * <p>Nothing but {@code java.base}, so anything may require it.
 * @jenesis.release 25
 */
module build.jenesis.repository.net {
    exports build.jenesis.repository.net;
}
