/**
 * The Debian/apt format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for
 * {@code /debian/...} - {@code .deb} uploads ({@code PUT /debian/<suite>/pool/<component>/<file>.deb}), the stored
 * {@code Packages} and {@code Release} indexes, pool downloads, and pull-through of an upstream apt repository. The
 * {@code ar} archive and its {@code control.tar} are read with Commons Compress, with {@code org.tukaani.xz} for
 * {@code .xz} and {@code com.github.luben.zstd_jni} for {@code .zst}. A hosted {@code Release} is OpenPGP-signed with
 * Bouncy Castle when a key is provisioned.
 *
 * @jenesis.release 25
 *
 * @jenesis.alias org.bouncycastle.pg org.bouncycastle/bcpg-jdk18on
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.debian {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.format.signing;
    requires org.slf4j;
    requires org.bouncycastle.pg;
    requires org.tukaani.xz;
    requires org.apache.commons.compress;
    requires com.github.luben.zstd_jni;
    // The keyring's store key and cache namespace, which a verifier must spell as the format does; agreeing on a key is
    // not access to the implementation, so the export is unqualified.
    exports build.jenesis.repository.format.debian.keys;
    exports build.jenesis.repository.format.debian;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.debian.DebianFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.debian.DebianListingObserver;
}
