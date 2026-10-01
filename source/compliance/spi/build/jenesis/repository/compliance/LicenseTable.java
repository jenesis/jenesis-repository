package build.jenesis.repository.compliance;

import module java.base;

/**
 * The licence identification table: the rows that resolve a declared licence name and URL to an SPDX identifier and a
 * category. It is the built-in rows ({@value #TABLE_RESOURCE}, read once) with the rows an operator configured under
 * {@value #KEY} in front of them, so a configured row wins over a built-in one - for a licence of their own, or to
 * recategorise or rename a known one.
 *
 * <p>Every identification goes through a table built from settings ({@link #of}): the gate's licence dimension and
 * the retroactive sweep from the configuration they are built with, a maintenance pass from its own, and a reader
 * with no configuration of its own - a count or a document built behind a request - from {@link ComplianceSettings},
 * the lookup the composition root wires. {@value #KEY} is deployment-wide, so all of them resolve a declaration the
 * same way.
 *
 * <h2>How a declaration resolves</h2>
 * <ol>
 * <li>A bare declaration - one word, as npm, Cargo and a gemspec declare it ({@code MIT}, {@code GPL-2.0-only}) - is
 *     matched against the rows' identifiers exactly, ignoring case. {@code X+} is the SPDX "or later" operator, so it
 *     resolves to {@code X-or-later} where a row has that identifier, else to {@code X}.</li>
 * <li>Otherwise the rows are tried in order against {@code "<name> <url>"}, lower-cased and with "licence" read as
 *     "license"; the first row one of whose names or URLs appears in it <em>as a word of its own</em> decides. A token
 *     is a short name - {@code mpl}, {@code bsd}, {@code gpl} - and inside another word it is no licence at all: "The
 *     Example Corporation Licence" names no Mozilla licence, and a disclaimer is not ISC. A digit may still follow, so
 *     {@code MPL2} and {@code gpl3} match. Order is therefore what makes the specific win: LGPL and AGPL rows come
 *     before GPL rows, a licence's "-or-later" and "-only" rows before its plain version, and names that carry no
 *     version at all last.</li>
 * <li>An SPDX {@code X WITH exception} that no row knows as a whole resolves as {@code X} - the exception refines
 *     the licence rather than replacing it.</li>
 * </ol>
 * Nothing matched is {@link License#UNKNOWN}.
 *
 * <h2>The configured rows</h2>
 * <p>{@value #KEY} holds one row per line, or rows separated by {@code ;}, each
 * {@code <identifier> | <category> | <name or URL> | <name or URL> ...}: the identifier a bare declaration names (no
 * whitespace, as an SPDX identifier), the category a policy matches on and the inventory counts under, and any number
 * of names and URLs the licence is published under. A URL's scheme is dropped, so {@code http} and {@code https}
 * match alike. The category is a lowercase word: the built-in ones are permissive, weak-copyleft, strong-copyleft and
 * network-copyleft, and an operator may name another - {@code proprietary}, say - which a policy's allow and deny lists
 * then match like any other. {@code unknown} is refused: it is what a licence nothing identifies counts as, and a
 * licence a row identifies is not that.
 */
public final class LicenseTable {

    /** The setting carrying an operator's own rows. */
    public static final String KEY = "license-definitions";

    /** {@value #KEY}'s default: no rows beyond the built-in ones. */
    public static final String DEFAULT = "";

    /** The classpath resource holding the built-in rows, most specific first. */
    private static final String TABLE_RESOURCE = "/spdx-licenses.tsv";

    /** What a licence nothing identifies counts as, which is why a configured row may not claim it. */
    private static final String UNKNOWN_CATEGORY = "unknown";

    /** A token shorter than this matches inside too many declarations to identify anything. */
    private static final int SHORTEST_TOKEN = 3;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+-]*");

    private static final Pattern CATEGORY = Pattern.compile("[a-z][a-z0-9-]*");

    private static final LicenseTable BUILT_IN = new LicenseTable(load());

    private final List<Row> rows;

    private LicenseTable(List<Row> rows) {
        this.rows = List.copyOf(rows);
    }

    /** The built-in rows alone - what a deployment that configured none identifies with. */
    public static LicenseTable defaults() {
        return BUILT_IN;
    }

    /**
     * The table a deployment identifies with: the rows configured under {@value #KEY} in {@code config}, then the
     * built-in ones. A value that does not parse throws, naming the row, so a live settings rebuild rejects it and a
     * pass that reads it fails visibly rather than identifying with a table the operator did not write.
     */
    public static LicenseTable of(UnaryOperator<String> config) {
        return configured(config.apply(KEY));
    }

    /** {@link #of} over the setting's value itself; {@code null} or blank is the built-in table. */
    public static LicenseTable configured(String value) {
        List<Row> configured = parse(value);
        if (configured.isEmpty()) {
            return BUILT_IN;
        }
        List<Row> rows = new ArrayList<>(configured);
        rows.addAll(BUILT_IN.rows);
        return new LicenseTable(rows);
    }

    /** Why {@code value} cannot be stored under {@value #KEY}, naming the row at fault, or empty when it can. */
    public static Optional<String> refusal(String value) {
        try {
            parse(value);
            return Optional.empty();
        } catch (IllegalArgumentException malformed) {
            return Optional.of(malformed.getMessage());
        }
    }

    /** Every row, configured ones first, in the order they are tried. */
    public List<Row> rows() {
        return rows;
    }

    /** Resolve a declared licence name and URL, either of which may be {@code null}. */
    public License identify(String name, String url) {
        License whole = resolve(name, url);
        if (whole.identified() || name == null) {
            return whole;
        }
        int with = name.indexOf(" WITH ");
        return with > 0 ? resolve(name.substring(0, with), url) : whole;
    }

    private License resolve(String name, String url) {
        String bare = bare(name);
        if (bare != null) {
            License exact = exact(bare);
            if (exact == null && bare.length() > 1 && bare.endsWith("+")) {
                String base = bare.substring(0, bare.length() - 1);
                exact = exact(base + "-or-later");
                if (exact == null) {
                    exact = exact(base);
                }
            }
            if (exact != null) {
                return exact;
            }
        }
        String text = normalise((name == null ? "" : name) + " " + (url == null ? "" : url));
        if (text.isBlank()) {
            return License.UNKNOWN;
        }
        for (Row row : rows) {
            for (String token : row.tokens()) {
                if (containsWord(text, token)) {
                    return row.license();
                }
            }
        }
        return License.UNKNOWN;
    }

    /** The first row whose identifier is {@code id}, ignoring case, or {@code null}. */
    private License exact(String id) {
        for (Row row : rows) {
            if (row.license().spdxId().equalsIgnoreCase(id)) {
                return row.license();
            }
        }
        return null;
    }

    /** {@code name} stripped when it is one word, else {@code null}. */
    private static String bare(String name) {
        if (name == null) {
            return null;
        }
        String stripped = name.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        for (int i = 0; i < stripped.length(); i++) {
            if (Character.isWhitespace(stripped.charAt(i))) {
                return null;
            }
        }
        return stripped;
    }

    /** Lower-cased with "licence" read as "license", the form both a declaration and a token are compared in. */
    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replace("licence", "license");
    }

    /** A configured or built-in name or URL in the form it is compared in: normalised, a URL without its scheme or a
     *  trailing slash. */
    private static String token(String raw) {
        String token = normalise(raw.strip());
        for (String scheme : List.of("https://", "http://")) {
            if (token.startsWith(scheme)) {
                token = token.substring(scheme.length());
            }
        }
        if (token.startsWith("www.")) {
            token = token.substring("www.".length());
        }
        while (token.endsWith("/")) {
            token = token.substring(0, token.length() - 1);
        }
        return token;
    }

    /**
     * Whether {@code text} carries {@code token} as a word of its own: where the token begins or ends with a letter,
     * the character beside it in the text must not be one.
     */
    static boolean containsWord(String text, String token) {
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + 1)) {
            int end = at + token.length();
            boolean startOk = !Character.isLetter(token.charAt(0)) || at == 0 || !Character.isLetter(text.charAt(at - 1));
            boolean endOk = !Character.isLetter(token.charAt(token.length() - 1)) || end == text.length()
                    || !Character.isLetter(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
        }
        return false;
    }

    /**
     * One row: the licence a match resolves to, the names it is published under and the URLs of its text, each in the
     * form it is compared in. A configured row's tokens are names unless they carried a URL scheme.
     */
    public record Row(License license, List<String> names, List<String> urls) {

        public Row {
            names = List.copyOf(names);
            urls = List.copyOf(urls);
        }

        /** The names, then the URLs - everything a declaration is matched against. */
        public List<String> tokens() {
            List<String> tokens = new ArrayList<>(names);
            tokens.addAll(urls);
            return tokens;
        }
    }

    /** The configured rows {@code value} holds, in order; throws naming the row a malformed one is. */
    private static List<Row> parse(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int number = 0;
        for (String line : value.split("[\\n;]")) {
            number++;
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            String[] fields = text.split("\\|", -1);
            if (fields.length < 2) {
                throw malformed(number, text, "expected '<identifier> | <category> | <name or URL> ...'");
            }
            String id = fields[0].strip();
            if (!IDENTIFIER.matcher(id).matches()) {
                throw malformed(number, text, "'" + id + "' is not an identifier: one word of letters, digits, "
                        + "'.', '-' and '+'");
            }
            if (!seen.add(id.toLowerCase(Locale.ROOT))) {
                throw malformed(number, text, "'" + id + "' is defined by an earlier row");
            }
            String category = fields[1].strip().toLowerCase(Locale.ROOT);
            if (!CATEGORY.matcher(category).matches()) {
                throw malformed(number, text, "'" + fields[1].strip() + "' is not a category: a lowercase word such as "
                        + "permissive, weak-copyleft, strong-copyleft or network-copyleft");
            }
            if (UNKNOWN_CATEGORY.equals(category)) {
                throw malformed(number, text, "'unknown' is what an unidentified licence counts as, so a row that "
                        + "identifies one cannot claim it");
            }
            List<String> names = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            for (int field = 2; field < fields.length; field++) {
                String raw = fields[field].strip();
                String token = token(raw);
                if (token.length() < SHORTEST_TOKEN) {
                    String what = raw.isEmpty() ? "an empty name"
                            : "'" + raw + "' is shorter than " + SHORTEST_TOKEN + " characters";
                    throw malformed(number, text, what + ", which would match inside too many declarations");
                }
                (raw.contains("://") ? urls : names).add(token);
            }
            rows.add(new Row(new License(id, category), names, urls));
        }
        return List.copyOf(rows);
    }

    private static IllegalArgumentException malformed(int number, String row, String reason) {
        return new IllegalArgumentException("row " + number + " of " + KEY + " ('" + row + "'): " + reason);
    }

    /** Parse the built-in rows, skipping blank and {@code #} comment lines. A missing or malformed resource is a
     *  packaging error, so it fails at class initialisation rather than identifying every licence as unknown. */
    private static List<Row> load() {
        InputStream in = LicenseTable.class.getResourceAsStream(TABLE_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("license identification table not found on the classpath: "
                    + TABLE_RESOURCE);
        }
        List<Row> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                if (fields.length != 4) {
                    throw new IllegalStateException("malformed license row (expected 4 tab-separated fields): " + line);
                }
                rows.add(new Row(new License(fields[0].strip(), fields[1].strip()), tokens(fields[2]),
                        tokens(fields[3])));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the license identification table " + TABLE_RESOURCE, e);
        }
        return List.copyOf(rows);
    }

    private static List<String> tokens(String field) {
        List<String> tokens = new ArrayList<>();
        for (String raw : field.split("\\|")) {
            if (!raw.isBlank()) {
                tokens.add(token(raw));
            }
        }
        return tokens;
    }
}
