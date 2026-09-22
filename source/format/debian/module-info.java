/**
 * The Debian/apt format as a plugin module: it provides {@link build.jenesis.repository.format.RepositoryFormat}
 * for the {@code /debian/...} layout - a {@code .deb} upload ({@code PUT /debian/<suite>/pool/<component>/<file>.deb}),
 * the binary {@code Packages} and {@code Release} indexes, stored listings the push maintains, the pool downloads, plus
 * pull-through proxying of an upstream apt repository (deb.debian.org). The {@code .deb} {@code ar} archive and its
 * {@code control.tar} are read with Commons Compress ({@code org.apache.commons.compress}) rather than hand-parsed;
 * the control tar is decompressed as gzip, {@code org.tukaani.xz} for {@code .xz} (the modern Debian {@code dpkg}
 * default) or {@code com.github.luben.zstd_jni} for {@code .zst} (Ubuntu's default), all through Commons Compress's
 * compressor streams. A hosted {@code Release} is
 * OpenPGP-signed with Bouncy Castle ({@code org.bouncycastle.pg}) when a key
 * is provisioned, since the JDK has no OpenPGP (only the raw RSA and digest primitives), and is otherwise unsigned
 * (apt trusts it with {@code [trusted=yes]}). A proxied upstream keeps its own signed {@code InRelease}, passed
 * through unchanged. Discovered through {@code provides}.
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
    exports build.jenesis.repository.format.debian to
            build.jenesis.repository.compliance.debian,
            build.jenesis.repository.gateway.test, build.jenesis.repository.gateway.census.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.debian.DebianFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.debian.DebianListingObserver;
}
