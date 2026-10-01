/**
 * The address ranges a deployment must not be steered into: every block the special-purpose address registries do not
 * call globally reachable - loopback, link-local, private-use, shared, benchmarking, documentation, reserved, multicast
 * and the IPv6 unique-local block among them - with the translation prefixes judged by the address they carry.
 *
 * <p>A tiny module of its own because its callers sit on both sides of a boundary: the format SPI's legs and the
 * outbound delivery guards in {@code settings} share the table without either depending on the other. A second copy of
 * the ranges would be a real SSRF gap the day one copy missed a block.
 *
 * <p>The table is shared; the policy is not: what a caller does about a host that will not resolve, or a URI without a
 * host, differs deliberately and stays with the caller. Nothing but {@code java.base}, so anything may require it.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.net {
    exports build.jenesis.repository.net;
}
