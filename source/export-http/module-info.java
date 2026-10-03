/**
 * Sending to another repository over HTTP, the way a format's own client publishes to it: the screened client, which
 * follows no redirect; a response read back only as far as the message a registry refuses with; and the guard that
 * keeps a relative path under the URL it is resolved against. An export sends through it, and so does anything else
 * that pushes what this repository holds to another one.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.export.http {
    requires transitive build.jenesis.repository.format;
    requires build.jenesis.repository.net.http;
    exports build.jenesis.repository.export.http;
}
