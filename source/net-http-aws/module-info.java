/**
 * The AWS SDK's HTTP client over the product's own ({@code net.http}): what every AWS SDK client this product builds
 * is handed in place of the SDK's URL-connection client, so its requests carry the product's headers and its
 * connections are made the way every other outbound call's are.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.net.http.aws {
    requires transitive software.amazon.awssdk.http;
    requires build.jenesis.repository.net.http;
    exports build.jenesis.repository.net.http.aws;
}
