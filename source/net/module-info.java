/**
 * The address ranges a deployment must not be steered into: loopback, link-local, site-local, multicast,
 * carrier-grade NAT and the IPv6 unique-local block.
 *
 * <p>It is its own module, and a tiny one, because the callers that need it sit on both sides of a boundary
 * neither should have to cross. {@code PrivateHosts} lived in the format SPI, so the webhook, forwarding and
 * emulator legs in {@code settings} could not reach it without dragging that SPI - eighty-nine test modules of
 * reach - along for a range table. The alternative they took instead was a second private copy of the same
 * ranges, and that is not a stylistic duplication: the inline copy that predated {@code PrivateHosts} omitted
 * carrier-grade NAT and multicast, and consolidating is what closed a real SSRF gap. A second table re-opens it
 * the same way, because a range added to one and not the other is invisible until something reaches the wrong
 * half.
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
