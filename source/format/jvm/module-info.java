/**
 * Describes the JVM layouts' one runtime dial to the settings catalogue: the Maven format's metadata-computation
 * opt-in, contributed through a {@link build.jenesis.repository.settings.SettingsContributor}.
 *
 * <p>It requires neither layout: the Maven and Jenesis module layouts reach an image through the bundle that names
 * them and are discovered through {@code ServiceLoader}, and a compile-time edge from a describing module to the
 * described implementation would rebuild every image on every change to that format. A test, not a {@code requires},
 * holds the key's spelling to the format's constant.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.jvm {
    requires build.jenesis.repository.settings;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.format.jvm.MavenMetadataSettingsContributor;
}
