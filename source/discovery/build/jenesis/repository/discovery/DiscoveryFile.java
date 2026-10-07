package build.jenesis.repository.discovery;

import module java.base;

/**
 * One domain's {@code /.well-known/java-repository.properties}, read and checked as the discovery proposal defines it:
 * a {@code java.util.Properties} document in UTF-8 whose keys are {@code module} and {@code maven} - where a module's or
 * a Maven group's files are, a location - and {@code moduletomaven} - which Maven artifact a module is, a
 * coordinate - each optionally with {@code .since}, {@code .suffixes} and {@code .latest} beside it, and {@code stop}.
 *
 * <p>Every refusal the proposal lists fails the read with a {@link DiscoveryException} naming the file: a key without
 * a value, a suffix that is not one word of letters and digits, a {@code stop} other than {@code true} or
 * {@code false}, a placeholder the key does not know, a coordinate naming no artifact, a coordinate in {@code module} or
 * {@code maven} and a location in {@code moduletomaven}, a latest link beside a root or a coordinate, and a location or
 * a link that is not {@code https}. A key the proposal does not name is ignored, so the format can grow.
 */
public record DiscoveryFile(String domain, Map<Key, Entry> entries, boolean stop) {

    /** Where the file of {@code domain} is published: a convention, never a setting. */
    public static URI address(String domain) {
        return URI.create("https://" + domain + "/.well-known/java-repository.properties");
    }

    /** The three keys. */
    public enum Key {
        /** Where a module's files are: a location. */
        MODULE("module", Set.of("module", "-suffix", "version", "-classifier", "type")),
        /** Which Maven artifact a module is: a coordinate. */
        MODULE_TO_MAVEN("moduletomaven", Set.of("module", "-suffix")),
        /** Where a Maven group's artifacts are: a location. */
        MAVEN("maven", Set.of("groupId", "groupPath", "artifactId", "version", "-classifier", "type"));

        private final String spelled;
        private final Set<String> placeholders;

        Key(String spelled, Set<String> placeholders) {
            this.spelled = spelled;
            this.placeholders = placeholders;
        }

        /** The key as the file spells it. */
        public String spelled() {
            return spelled;
        }

        /** The placeholders a value of this key may name. */
        public Set<String> placeholders() {
            return placeholders;
        }
    }

    /**
     * One key's entry: its {@code value} - a location, or for {@link Key#MODULE_TO_MAVEN} a coordinate - and what
     * restricts it: the first version it serves ({@code since}, or {@code null}), the version qualifiers it serves
     * ({@code suffixes}, empty for every one) and the link naming its newest version ({@code latest}, or {@code null}).
     */
    public record Entry(String value, String since, List<String> suffixes, String latest) {

        public Entry {
            suffixes = List.copyOf(suffixes);
        }

        /** Whether the value is a template - names a placeholder - rather than a root or a plain coordinate. */
        public boolean template() {
            return value.indexOf('{') >= 0;
        }
    }

    /** The placeholder pattern: a name between braces. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

    /** A suffix: one word of letters and digits. */
    private static final Pattern SUFFIX = Pattern.compile("[A-Za-z0-9]+");

    /** The longest file read, bounding what a domain can make a node hold. */
    public static final int MOST_BYTES = 64 * 1024;

    public DiscoveryFile {
        Objects.requireNonNull(domain, "domain");
        entries = Map.copyOf(entries);
    }

    /** The entry of {@code key}, or empty where the file does not hold it. */
    public Optional<Entry> entry(Key key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * The file {@code domain} published, read from {@code text}.
     *
     * @throws DiscoveryException naming the file, for any refusal the proposal lists
     */
    public static DiscoveryFile parse(String domain, String text) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException unreadable) {
            throw refused(domain, "it is not a properties file: " + unreadable.getMessage());
        }
        for (String name : properties.stringPropertyNames()) {
            if (known(name) && properties.getProperty(name).isBlank()) {
                throw refused(domain, "the key '" + name + "' has no value");
            }
        }
        boolean stop = true;
        String stopping = properties.getProperty("stop");
        if (stopping != null) {
            switch (stopping.strip()) {
                case "true" -> stop = true;
                case "false" -> stop = false;
                default -> throw refused(domain, "stop is '" + stopping.strip() + "', neither true nor false");
            }
        }
        Map<Key, Entry> entries = new EnumMap<>(Key.class);
        for (Key key : Key.values()) {
            String value = properties.getProperty(key.spelled());
            if (value == null) {
                for (String modifier : List.of(".since", ".suffixes", ".latest")) {
                    if (properties.getProperty(key.spelled() + modifier) != null) {
                        throw refused(domain, "'" + key.spelled() + modifier + "' stands beside no '"
                                + key.spelled() + "'");
                    }
                }
                continue;
            }
            entries.put(key, entry(domain, key, value.strip(), properties));
        }
        return new DiscoveryFile(domain, entries, stop);
    }

    private static Entry entry(String domain, Key key, String value, Properties properties) {
        boolean location = value.contains("://");
        if (key == Key.MODULE_TO_MAVEN) {
            if (location) {
                throw refused(domain, "moduletomaven names a location, '" + value + "', where it takes a coordinate");
            }
            String[] parts = value.split(":", -1);
            if (parts.length < 2 || parts.length > 4 || parts[0].isBlank() || parts[1].isBlank()) {
                throw refused(domain, "moduletomaven names no artifact: '" + value + "'");
            }
        } else {
            if (!location) {
                throw refused(domain, key.spelled() + " names a coordinate, '" + value + "', where it takes a location");
            }
            https(domain, key.spelled(), value);
        }
        placeholders(domain, key.spelled(), value, key.placeholders());
        String since = modifier(properties, key, ".since");
        List<String> suffixes = new ArrayList<>();
        String listed = modifier(properties, key, ".suffixes");
        if (listed != null) {
            for (String suffix : listed.split(",", -1)) {
                String word = suffix.strip();
                if (!SUFFIX.matcher(word).matches()) {
                    throw refused(domain, key.spelled() + ".suffixes names '" + word
                            + "', not one word of letters and digits");
                }
                suffixes.add(word.toLowerCase(Locale.ROOT));
            }
        }
        String latest = modifier(properties, key, ".latest");
        if (latest != null) {
            if (key == Key.MODULE_TO_MAVEN || !value.contains("{")) {
                throw refused(domain, key.spelled() + ".latest stands beside a "
                        + (key == Key.MODULE_TO_MAVEN ? "coordinate" : "root")
                        + ", which lists its versions itself");
            }
            https(domain, key.spelled() + ".latest", latest);
            Set<String> linked = new HashSet<>(key.placeholders());
            linked.remove("version");
            placeholders(domain, key.spelled() + ".latest", latest, linked);
        }
        return new Entry(value, since, suffixes, latest);
    }

    private static String modifier(Properties properties, Key key, String modifier) {
        String value = properties.getProperty(key.spelled() + modifier);
        return value == null ? null : value.strip();
    }

    private static boolean known(String name) {
        if (name.equals("stop")) {
            return true;
        }
        for (Key key : Key.values()) {
            for (String modifier : List.of("", ".since", ".suffixes", ".latest")) {
                if (name.equals(key.spelled() + modifier)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void https(String domain, String name, String value) {
        URI uri;
        try {
            uri = URI.create(PLACEHOLDER.matcher(value).replaceAll("x"));
        } catch (IllegalArgumentException malformed) {
            throw refused(domain, name + " is not a URI: '" + value + "'");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw refused(domain, name + " is not an https location: '" + value + "'");
        }
        if (uri.getUserInfo() != null) {
            throw refused(domain, name + " carries a credential: '" + value + "'");
        }
    }

    private static void placeholders(String domain, String name, String value, Set<String> allowed) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) {
                throw refused(domain, name + " names the placeholder {" + matcher.group(1) + "}, which it does not know");
            }
        }
        String bare = PLACEHOLDER.matcher(value).replaceAll("");
        if (bare.indexOf('{') >= 0 || bare.indexOf('}') >= 0) {
            throw refused(domain, name + " holds an unbalanced brace: '" + value + "'");
        }
    }

    private static DiscoveryException refused(String domain, String reason) {
        return new DiscoveryException("The discovery file " + address(domain) + " is refused: " + reason);
    }
}
