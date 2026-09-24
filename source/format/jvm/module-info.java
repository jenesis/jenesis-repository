/**
 * Describes the JVM layouts' one runtime dial to the settings catalogue: the Maven format's
 * metadata-computation opt-in, contributed through a {@link build.jenesis.repository.settings.SettingsContributor} so
 * it surfaces exactly when an image ships this module beside the layouts.
 *
 * <p>It deliberately {@code requires} <em>neither</em> layout implementation. The layouts themselves -
 * the Maven layout ({@code build.jenesis.repository.format.maven}) and the Jenesis module layout
 * ({@code build.jenesis.repository.format.jenesis}) - ride into an image from the distribution that names them
 * ({@code source/bundle} lists them beside every other format) and are discovered through {@code ServiceLoader}
 * exactly like the language formats; a shell (server / ui / combined) still names no format. A describing module
 * taking a compile-time edge to the described implementation is what made every Maven or module-layout commit
 * rebuild the images, so the only thing this module reads is the settings SPI and the agreed key spelling, with a
 * test - not a {@code requires} - holding that spelling to the constant.
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
