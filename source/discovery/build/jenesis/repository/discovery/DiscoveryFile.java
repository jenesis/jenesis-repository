package build.jenesis.repository.discovery;

import module java.base;

/**
 * One domain's {@code /.well-known/java-repository.properties}, read and checked as the discovery proposal defines it:
 * a {@code java.util.Properties} document in UTF-8 whose keys are {@code module} and {@code maven} - where a module's or
 * a Maven artifact's files are, a location - {@code moduletomaven} - which Maven artifact a module is, a coordinate -
 * and {@code sources} - where the source archive of a release is, a template naming {@code {version}}. A key may name
 * what it is for in brackets, {@code module[build.jenesis]}, or the start of such a name followed by {@code *},
 * {@code maven[build.jenesis.repository.*]}; one without serves every name below the domain. Each key optionally has
 * {@code .since}, {@code .suffixes} and {@code .latest} beside it, carrying its selector, and the file may say
 * {@code delegate}.
 *
 * <p>Every refusal the proposal lists fails the read with a {@link DiscoveryException} naming the file: a key without
 * a value, a selector that is not a name or the start of one followed by {@code *}, a suffix that is not one word of
 * letters and digits, a {@code delegate} other than {@code true} or {@code false}, a placeholder the key does not know,
 * a coordinate naming no artifact, a coordinate in {@code module}, {@code maven} or {@code sources} and a location in
 * {@code moduletomaven}, a {@code sources} that names no {@code {version}}, a latest link beside a value naming no
 * {@code {version}}, and a location or a link that is not {@code https}. A key the proposal does not name, and a
 * modifier beside no key, is ignored, so the format can grow.
 */
public record DiscoveryFile(String domain, List<Entry> entries, boolean delegate) {

    /** Where the file of {@code domain} is published: a convention, never a setting. */
    public static URI address(String domain) {
        return URI.create("https://" + domain + "/.well-known/java-repository.properties");
    }

    /** The four keys. */
    public enum Key {
        /** Where a module's files are: a location, selected by module name. */
        MODULE("module", Set.of("module", "-suffix", "version", "-classifier", "type")),
        /** Which Maven artifact a module is: a coordinate, selected by module name. */
        MODULE_TO_MAVEN("moduletomaven", Set.of("module", "-suffix")),
        /** Where a Maven group's artifacts are: a location, selected by artifact ID. */
        MAVEN("maven", Set.of("groupId", "groupPath", "artifactId", "version", "-classifier", "type")),
        /** Where the source archive of a release is: a template naming {@code {version}}, selected by module name or
         *  artifact ID. */
        SOURCES("sources", Set.of("module", "-suffix", "groupId", "groupPath", "artifactId", "version"));

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
     * One key's entry: the {@code key}, the name or name prefix it is for ({@code selector}, ending in {@code *} for a
     * prefix, or {@code null} for every name), its {@code value} - a location, or for {@link Key#MODULE_TO_MAVEN} a
     * coordinate - and what restricts it: the first version it serves ({@code since}, or {@code null}), the version
     * qualifiers it serves ({@code suffixes}, empty for every one) and the link naming its newest version
     * ({@code latest}, or {@code null}).
     */
    public record Entry(Key key, String selector, String value, String since, List<String> suffixes, String latest) {

        public Entry {
            Objects.requireNonNull(key, "key");
            suffixes = List.copyOf(suffixes);
        }

        /** The key as the file spells it, with its selector. */
        public String spelled() {
            return selector == null ? key.spelled() : key.spelled() + "[" + selector + "]";
        }

        /** Whether the value is a template - names a placeholder - rather than a root or a plain coordinate. */
        public boolean template() {
            return value.indexOf('{') >= 0;
        }

        /** Whether the latest link names a {@code maven-metadata.xml}, whose release is the newest version, rather
         *  than a link whose redirect or header names it. */
        public boolean listsVersions() {
            return latest != null && latest.endsWith("/maven-metadata.xml");
        }

        /** How well this entry selects {@code name}: empty where it does not, else higher the more specific - a
         *  selector naming {@code name} exactly above every prefix, a longer prefix above a shorter, and any selector
         *  above none. */
        public OptionalInt selects(String name) {
            if (selector == null) {
                return OptionalInt.of(-1);
            }
            if (!selector.endsWith("*")) {
                return selector.equals(name) ? OptionalInt.of(Integer.MAX_VALUE) : OptionalInt.empty();
            }
            String prefix = selector.substring(0, selector.length() - 1);
            return name.startsWith(prefix) ? OptionalInt.of(prefix.length()) : OptionalInt.empty();
        }
    }

    /** A known key's name: the key, its selector in brackets, and a modifier. */
    private static final Pattern NAME = Pattern.compile("([a-z]+)(?:\\[([^\\]]*)])?(\\.since|\\.suffixes|\\.latest)?");

    /** A selector: a module name or an artifact ID, or the start of one followed by {@code *}. */
    private static final Pattern SELECTOR = Pattern.compile("[A-Za-z0-9_.-]+\\*?");

    /** The placeholder pattern: a name between braces. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

    /** A suffix: one word of letters and digits. */
    private static final Pattern SUFFIX = Pattern.compile("[A-Za-z0-9]+");

    /** The longest file read, bounding what a domain can make a node hold. */
    public static final int MOST_BYTES = 64 * 1024;

    public DiscoveryFile {
        Objects.requireNonNull(domain, "domain");
        entries = List.copyOf(entries);
    }

    /** The entry of {@code key} that answers for {@code name} - the one selecting it most specifically, or the one
     *  for every name - or empty where the file holds none. */
    public Optional<Entry> entry(Key key, String name) {
        Entry best = null;
        int score = Integer.MIN_VALUE;
        for (Entry entry : entries) {
            if (entry.key() != key) {
                continue;
            }
            OptionalInt selects = entry.selects(name);
            if (selects.isPresent() && selects.getAsInt() > score) {
                best = entry;
                score = selects.getAsInt();
            }
        }
        return Optional.ofNullable(best);
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
        boolean delegate = false;
        String delegating = properties.getProperty("delegate");
        if (delegating != null) {
            switch (delegating.strip()) {
                case "true" -> delegate = true;
                case "false" -> delegate = false;
                case "" -> throw refused(domain, "the key 'delegate' has no value");
                default -> throw refused(domain, "delegate is '" + delegating.strip() + "', neither true nor false");
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (String name : new TreeSet<>(properties.stringPropertyNames())) {
            Matcher matcher = NAME.matcher(name);
            if (!matcher.matches() || matcher.group(3) != null) {
                continue;
            }
            Optional<Key> key = Arrays.stream(Key.values()).filter(known -> known.spelled().equals(matcher.group(1)))
                    .findFirst();
            if (key.isEmpty()) {
                continue;
            }
            String selector = matcher.group(2);
            if (selector != null && !SELECTOR.matcher(selector).matches()) {
                throw refused(domain, "'" + name + "' selects '" + selector + "', where a selector is a module name or"
                        + " an artifact ID, or the start of one followed by *");
            }
            entries.add(entry(domain, key.get(), selector, name, properties));
        }
        return new DiscoveryFile(domain, entries, delegate);
    }

    private static Entry entry(String domain, Key key, String selector, String name, Properties properties) {
        String value = value(domain, properties, name);
        boolean location = value.contains("://");
        if (key == Key.MODULE_TO_MAVEN) {
            if (location) {
                throw refused(domain, name + " names a location, '" + value + "', where it takes a coordinate");
            }
            String[] parts = value.split(":", -1);
            if (parts.length < 2 || parts.length > 4 || Arrays.stream(parts).anyMatch(String::isBlank)) {
                throw refused(domain, name + " names no artifact: '" + value
                        + "', where it takes <groupId>:<artifactId>[:<extension>[:<classifier>]]");
            }
        } else {
            if (!location) {
                throw refused(domain, name + " names a coordinate, '" + value + "', where it takes a location");
            }
            https(domain, name, value);
            if (key == Key.SOURCES && !value.contains("{version}")) {
                throw refused(domain, name + " names no {version}, where it takes the source archive of a version");
            }
        }
        placeholders(domain, name, value, key.placeholders());
        String since = modifier(domain, properties, name + ".since");
        List<String> suffixes = new ArrayList<>();
        String listed = modifier(domain, properties, name + ".suffixes");
        if (listed != null) {
            for (String suffix : listed.split(",", -1)) {
                String word = suffix.strip();
                if (!SUFFIX.matcher(word).matches()) {
                    throw refused(domain, name + ".suffixes names '" + word
                            + "', not one word of letters and digits");
                }
                suffixes.add(word.toLowerCase(Locale.ROOT));
            }
        }
        String latest = modifier(domain, properties, name + ".latest");
        if (latest != null) {
            if (key == Key.MODULE_TO_MAVEN || !value.contains("{version}")) {
                throw refused(domain, name + ".latest stands beside "
                        + (key == Key.MODULE_TO_MAVEN ? "a coordinate" : "a value naming no {version}")
                        + ", which lists its versions itself");
            }
            https(domain, name + ".latest", latest);
            Set<String> linked = new HashSet<>(key.placeholders());
            linked.remove("version");
            placeholders(domain, name + ".latest", latest, linked);
        }
        return new Entry(key, selector, value, since, suffixes, latest);
    }

    private static String modifier(String domain, Properties properties, String name) {
        return properties.getProperty(name) == null ? null : value(domain, properties, name);
    }

    private static String value(String domain, Properties properties, String name) {
        String value = properties.getProperty(name).strip();
        if (value.isEmpty()) {
            throw refused(domain, "the key '" + name + "' has no value");
        }
        return value;
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
