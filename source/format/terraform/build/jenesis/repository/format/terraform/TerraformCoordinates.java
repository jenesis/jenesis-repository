package build.jenesis.repository.format.terraform;

import module java.base;

import build.jenesis.repository.format.Checksums;

/**
 * The two coordinate shapes Terraform's registry protocols address, and the store keys this format gives them.
 *
 * <p>They are genuinely two, which is why this exists rather than one parser with a flag. A <b>module</b> is
 * {@code <namespace>/<name>/<system>} - three segments, the last naming the target system ({@code aws},
 * {@code google}) rather than a platform - and a <b>provider</b> is {@code <namespace>/<type>}, two segments, whose
 * releases are per-platform binaries. A client writes the first in a {@code module} block's {@code source} and the
 * second in {@code required_providers}, and neither address can be read as the other.
 */
final class TerraformCoordinates {

    /** The root every key this format writes lives under. */
    static final String ROOT = "terraform/";

    private TerraformCoordinates() {
    }

    /**
     * A module version's archive, the object {@code X-Terraform-Get} points a client at.
     *
     * <p>Outside the {@code v1/} namespace deliberately: {@code v1} is Terraform's <em>protocol</em> space, and the
     * protocol says a download URL is whatever the registry chooses. Storing artifacts under a foreign
     * specification's version prefix would tie a stored key to a protocol revision it has nothing to do with.
     */
    static String moduleArchive(String repo, String namespace, String name, String system, String version) {
        return ROOT + repo + "/modules/" + namespace + "/" + name + "/" + system + "/" + version + ".tar.gz";
    }

    /** The digest a git source's archive was recorded with on its first fetch, keyed on the digest of its host,
     *  repository and ref - which may run longer than a key segment, and hold characters none may. */
    static String gitDigest(String repo, String identity) {
        return ROOT + repo + "/git/" + Checksums.sha256(identity.getBytes(StandardCharsets.UTF_8));
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
