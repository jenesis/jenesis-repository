package build.jenesis.repository.compliance.signatures;

import module java.base;

/**
 * Where the OpenPGP Web Key Directory keeps a key for an e-mail address: the local part lower-cased, hashed with
 * SHA-1 and spelled in z-base-32, under the domain's {@code .well-known/openpgpkey} - the advanced method on the
 * {@code openpgpkey} subdomain first, the direct method on the domain itself second, as the draft orders them. The
 * document served is a binary transferable public key; the caller armours it before keeping it.
 */
final class WebKeyDirectory {

    private static final String ALPHABET = "ybndrfg8ejkmcpqxot1uwisza345h769";

    private WebKeyDirectory() {
    }

    /** The two lookup URLs for an address, advanced method first, or none for a string that is not an address. */
    static List<URI> lookups(String address) {
        int at = address == null ? -1 : address.lastIndexOf('@');
        if (at <= 0 || at == address.length() - 1) {
            return List.of();
        }
        String local = address.substring(0, at);
        String domain = address.substring(at + 1).toLowerCase(Locale.ROOT);
        String hash = hash(local);
        String query = "?l=" + URLEncoder.encode(local, StandardCharsets.UTF_8);
        return List.of(
                URI.create("https://openpgpkey." + domain + "/.well-known/openpgpkey/" + domain + "/hu/" + hash + query),
                URI.create("https://" + domain + "/.well-known/openpgpkey/hu/" + hash + query));
    }

    /** The direct-method lookup rooted at one host for every domain - a stub, or an internal directory. */
    static URI lookup(String base, String address) {
        int at = address.lastIndexOf('@');
        String local = address.substring(0, at);
        return URI.create(base.replaceAll("/+$", "") + "/.well-known/openpgpkey/hu/" + hash(local)
                + "?l=" + URLEncoder.encode(local, StandardCharsets.UTF_8));
    }

    /** The z-base-32 SHA-1 of the lower-cased local part - {@code Joe.Doe} is {@code iy9q119eutrkn8s1mk4r39qejnbu3n5q}. */
    static String hash(String local) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(local.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder();
            int buffer = 0, bits = 0;
            for (byte octet : digest) {
                buffer = (buffer << 8) | (octet & 0xff);
                bits += 8;
                while (bits >= 5) {
                    encoded.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
                    bits -= 5;
                }
            }
            if (bits > 0) {
                encoded.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
