package build.jenesis.repository.store.gcs;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import build.jenesis.repository.store.Clocks;
import com.google.auth.ServiceAccountSigner;

/**
 * A V4 signed URL for one object: the {@code GOOG4-RSA-SHA256} scheme GCS documents, a {@code GET} over the
 * fully-qualified object valid for a bounded time, signed by whatever the deployment's credential can sign with - a
 * service-account key locally, or the IAM signing service when the credential is the metadata server's, both behind
 * the auth library's one {@link ServiceAccountSigner} face. The string to sign is composed here because the API
 * client has no signed-URL surface of its own; a deployment whose credential cannot sign gets no signer, and the
 * store then streams the bytes itself.
 */
final class GcsSignedUrl {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String ALGORITHM = "GOOG4-RSA-SHA256";

    private final ServiceAccountSigner signer;
    private final URI endpoint;

    GcsSignedUrl(ServiceAccountSigner signer, URI endpoint) {
        this.signer = signer;
        this.endpoint = endpoint;
    }

    /** The account whose key signs, for a caller that wants to say so. */
    String account() {
        return signer.getAccount();
    }

    URI sign(String bucket, String object, Duration ttl) {
        Instant now = Clocks.now();
        String stamp = STAMP.format(now);
        String scope = stamp.substring(0, 8) + "/auto/storage/goog4_request";
        String host = endpoint.getHost() + (endpoint.getPort() > 0 ? ":" + endpoint.getPort() : "");
        String path = "/" + encode(bucket, false) + "/" + encode(object, true);
        Map<String, String> query = new TreeMap<>();
        query.put("X-Goog-Algorithm", ALGORITHM);
        query.put("X-Goog-Credential", signer.getAccount() + "/" + scope);
        query.put("X-Goog-Date", stamp);
        query.put("X-Goog-Expires", Long.toString(ttl.toSeconds()));
        query.put("X-Goog-SignedHeaders", "host");
        StringBuilder canonicalQuery = new StringBuilder();
        query.forEach((name, value) -> canonicalQuery.append(canonicalQuery.isEmpty() ? "" : "&")
                .append(encode(name, false)).append('=').append(encode(value, false)));
        String canonicalRequest = "GET\n" + path + "\n" + canonicalQuery + "\nhost:" + host + "\n\nhost\nUNSIGNED-PAYLOAD";
        String stringToSign = ALGORITHM + "\n" + stamp + "\n" + scope + "\n" + hex(sha256(canonicalRequest));
        String signature = hex(signer.sign(stringToSign.getBytes(StandardCharsets.UTF_8)));
        return URI.create(endpoint.getScheme() + "://" + host + path + "?" + canonicalQuery + "&X-Goog-Signature=" + signature);
    }

    /** Percent-encoding as the signing scheme wants it: everything but the unreserved characters, and in a path
     *  the segment separators are kept. */
    private static String encode(String value, boolean path) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~'
                    || (path && c == '/')) {
                out.append(c);
            } else {
                out.append('%').append(HexFormat.of().withUpperCase().toHexDigits(b));
            }
        }
        return out.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
