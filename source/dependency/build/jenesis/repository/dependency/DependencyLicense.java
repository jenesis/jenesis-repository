package build.jenesis.repository.dependency;

import module java.base;

/**
 * One licence a CycloneDX component declares. An {@code id} is an SPDX identifier the emitter recognised
 * ({@code Apache-2.0}) and is directly comparable; a {@code name} (with an optional {@code url}) is the free-text form
 * for a licence not on the SPDX list. An SPDX {@code expression} ({@code MIT OR Apache-2.0}) is recorded whole in
 * {@code id}, so a consumer that cannot evaluate it sees an identifier it does not recognise rather than half of it.
 *
 * <p>A plain value rather than the compliance SPI's {@code ComplianceGate.DeclaredLicense}: this module serves the
 * reverse-dependency index and the reachability engine too, and takes no edge to the compliance SPI.
 */
public record DependencyLicense(String id, String name, String url) {

    /** An SPDX-identified licence (or an SPDX expression), with no free-text name or URL. */
    public static DependencyLicense of(String id) {
        return new DependencyLicense(id, null, null);
    }

    /** A free-text licence: a name, a URL, or both. */
    public static DependencyLicense named(String name, String url) {
        return new DependencyLicense(null, name, url);
    }

    /** Whether this node carries nothing - an empty {@code <license/>} or {@code {"license":{}}} declares no licence
     *  and is dropped. */
    public boolean isEmpty() {
        return blank(id) && blank(name) && blank(url);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
