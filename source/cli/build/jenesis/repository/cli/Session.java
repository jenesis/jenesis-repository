package build.jenesis.repository.cli;

import module java.base;

/**
 * A logged-in session: the repository URL and the key the client sends, persisted under {@code ~/.jenesis} so a
 * later invocation reuses them. The file is written owner-only where the filesystem supports it, since it holds a
 * credential. The home directory is {@code ~/.jenesis} unless {@code JENREG_CLI_HOME} overrides it (which a test
 * uses to stay out of the real home, as an environment variable or a system property).
 */
public final class Session {

    private final URI url;
    private final String key;

    public Session(URI url, String key) {
        if (url == null) {
            throw new IllegalArgumentException("A repository URL is required");
        }
        this.url = url;
        this.key = key;
    }

    public URI url() {
        return url;
    }

    public String key() {
        return key;
    }

    public static Path home() {
        String override = System.getenv("JENREG_CLI_HOME");
        if (override == null || override.isBlank()) {
            override = System.getProperty("JENREG_CLI_HOME");
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".jenesis");
    }

    /** The stored session, or {@code null} when none was saved. */
    public static Session load(Path home) throws IOException {
        Path file = home.resolve("credentials");
        if (!Files.exists(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        String url = properties.getProperty("url");
        if (url == null) {
            return null;
        }
        return new Session(URI.create(url), properties.getProperty("key"));
    }

    public void save(Path home) throws IOException {
        Files.createDirectories(home);
        Properties properties = new Properties();
        properties.setProperty("url", url.toString());
        if (key != null) {
            properties.setProperty("key", key);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, "jenesis repository CLI");
        Path file = home.resolve("credentials");
        Files.deleteIfExists(file);
        try {
            // Create the file owner-only from the start, so the key it holds is never momentarily group/world
            // readable in the window an after-the-fact chmod would leave open on a shared host.
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                channel.write(ByteBuffer.wrap(bytes.toByteArray()));
            }
        } catch (UnsupportedOperationException _) {
            // a non-POSIX filesystem cannot set creation permissions; write plainly (best effort)
            Files.write(file, bytes.toByteArray());
        }
    }

    public static void clear(Path home) throws IOException {
        Files.deleteIfExists(home.resolve("credentials"));
    }
}
