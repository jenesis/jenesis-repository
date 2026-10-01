package build.jenesis.repository.upstream;

import module java.base;

/**
 * An upstream credential as an operator asks for one, the same on every surface: a {@code basic} username and password,
 * a {@code bearer} token, an arbitrary {@code header} for an API-key upstream, or {@code aws} - a token the
 * deployment's AWS identity is issued for the host, renewed as it expires. The API, the console and the CLI translate a
 * scheme into a header through this alone.
 */
public sealed interface UpstreamCredential {

    /** A header sent as it is. */
    record Header(String name, String value) implements UpstreamCredential {
    }

    /** A token minted by the named {@link UpstreamTokenIssuer}. */
    record Issued(String issuer) implements UpstreamCredential {
    }

    /** The schemes, as every surface offers them. */
    List<String> SCHEMES = List.of("basic", "bearer", "header", "aws");

    /** The credential {@code scheme} and its fields describe, or empty when a field the scheme needs is missing. */
    static Optional<UpstreamCredential> of(String scheme, String username, String password, String token,
                                           String header) {
        return Optional.ofNullable(switch (scheme == null ? "" : scheme.trim().toLowerCase(Locale.ROOT)) {
            case "basic" -> blank(username) || password == null
                    ? null
                    : new Header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                            (username + ":" + password).getBytes(StandardCharsets.UTF_8)));
            case "bearer" -> blank(token) ? null : new Header("Authorization", "Bearer " + token);
            case "header" -> blank(header) || blank(token) ? null : new Header(header.trim(), token);
            case "aws" -> new Issued("aws");
            default -> null;
        });
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
