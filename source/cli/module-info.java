/**
 * The Jenesis repository command-line client: log in with a key stored under {@code ~/.jenesis}, then review and
 * change a deployment over the repository's HTTP API - the same {@code /api/*} surface the console drives, so the
 * command line, the console and the API stay equal. Runs on the built-in HTTP client, with Jackson for JSON.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cli {
    requires java.net.http;
    requires build.jenesis.repository.scope;
    requires tools.jackson.databind;
    exports build.jenesis.repository.cli;
    opens build.jenesis.repository.cli to tools.jackson.databind;
}
