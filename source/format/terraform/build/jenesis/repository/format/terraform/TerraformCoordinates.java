package build.jenesis.repository.format.terraform;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Checksums;

/**
 * The two coordinate shapes Terraform's registry protocols address, and the store keys this format gives them: a
 * <b>module</b> is {@code <namespace>/<name>/<system>}, the last naming the target system ({@code aws}), and a
 * <b>provider</b> {@code <namespace>/<type>}, whose releases are per-platform binaries. Neither address can be read as
 * the other.
 */
final class TerraformCoordinates {

    /** The root every key this format writes lives under. */
    static final String ROOT = "terraform/";

    private TerraformCoordinates() {
    }

    /** A module version's archive, the object {@code X-Terraform-Get} points a client at. Stored outside {@code v1/},
     *  Terraform's protocol space, since the protocol leaves a download URL to the registry and a stored key should not
     *  be tied to a protocol revision. */
    static String moduleArchive(String repo, String namespace, String name, String system, String version) {
        return ROOT + repo + "/modules/" + namespace + "/" + name + "/" + system + "/" + version + ".tar.gz";
    }

    /** The digest a git source's archive was recorded with on its first fetch, keyed on the digest of its host,
     *  repository and ref, which may be longer than a key segment and hold characters none may. */
    static String gitDigest(String repo, String identity) {
        return ROOT + repo + "/git/" + Checksums.sha256(identity.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The marker beside {@code archive} saying its bytes are a copy fetched from the upstream rather than a release
     * published here, so this registry's own version lists, {@code SHA256SUMS} and package documents leave it to the
     * upstream's, which a client verifies against the upstream's key. Kept in the repository's {@code cached/} space,
     * outside the {@code providers/} and {@code modules/} trees those documents are generated from.
     */
    static String cached(String archive) {
        String rest = archive.substring(ROOT.length());
        int slash = rest.indexOf('/');
        return ROOT + rest.substring(0, slash) + "/cached" + rest.substring(slash);
    }

    /** Whether the archive at {@code archive} is a copy fetched from the upstream ({@link #cached}). */
    static boolean isCached(ArtifactStore store, String archive) throws IOException {
        return store.readVersioned(cached(archive)).isPresent();
    }

    /** A provider version's per-platform zip, named as the protocol's {@code filename} field reports it. */
    static String providerArchive(String repo, String namespace, String type, String version, String file) {
        return ROOT + repo + "/providers/" + namespace + "/" + type + "/" + version + "/" + file;
    }

    /** The file name a provider release carries, which is also what its {@code SHA256SUMS} line names. */
    static String providerFile(String type, String version, String os, String arch) {
        return "terraform-provider-" + type + "_" + version + "_" + os + "_" + arch + ".zip";
    }

    /** The {@code <os>_<arch>} a provider zip's name ends in, or empty when the name is not one. */
    static Optional<String[]> platformOf(String type, String version, String file) {
        String prefix = "terraform-provider-" + type + "_" + version + "_";
        if (!file.startsWith(prefix) || !file.endsWith(".zip")) {
            return Optional.empty();
        }
        String[] platform = file.substring(prefix.length(), file.length() - ".zip".length()).split("_");
        return platform.length == 2 && !platform[0].isEmpty() && !platform[1].isEmpty()
                ? Optional.of(platform)
                : Optional.empty();
    }

    /** The coordinate a module version is screened and reported under. */
    static String moduleCoordinate(String namespace, String name, String system) {
        return namespace + "/" + name + "/" + system;
    }

    /** The coordinate a provider version is screened and reported under. */
    static String providerCoordinate(String namespace, String type) {
        return namespace + "/" + type;
    }
}
