package build.jenesis.repository.dependency;

import module java.base;

/**
 * One licence a CycloneDX component declares. The standard allows a licence node to be spelled two ways and the
 * distinction is worth keeping: an {@code id} is an <em>SPDX identifier</em> the emitter recognised
 * ({@code Apache-2.0}) and is directly policy-comparable, while a {@code name} (with an optional {@code url}) is the
 * free-text form an emitter falls back to when the licence is not on the SPDX list. A document may also carry a
 * boolean SPDX {@code expression} ({@code MIT OR Apache-2.0}) instead of either, which is recorded in {@code id} as
 * the whole expression string - a consumer that cannot evaluate expressions then sees an identifier it does not
 * recognise, which is the honest outcome, rather than an arbitrarily chosen half of it.
 *
 * <p>This is deliberately a plain value in the SBOM model rather than the compliance SPI's
 * {@code ComplianceGate.DeclaredLicense}: the dependency module parses SBOMs for the reverse-dependency index and
 * the reachability engine as well as for the gate, and must not take an edge to the compliance SPI to do it. A
 * consumer that wants a gate subject maps the two fields onto whichever declaration shape it needs.
 */
public record DependencyLicense(String id, String name, String url) {

    /** An SPDX-identified licence (or an SPDX expression), with no free-text name or URL. */
    public static DependencyLicense of(String id) {
        return new DependencyLicense(id, null, null);
    }

    /** A free-text licence: a name, a URL, or both - the shape an emitter writes for a licence it could not match to
     *  an SPDX identifier. */
    public static DependencyLicense named(String name, String url) {
        return new DependencyLicense(null, name, url);
    }

    /** Whether this node carries nothing at all - a {@code <license/>} element or {@code {"license":{}}} object with
     *  no id, no name and no url declares no licence, and is dropped rather than recorded as a licence named
     *  {@code null}. */
    public boolean isEmpty() {
        return blank(id) && blank(name) && blank(url);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
