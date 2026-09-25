package build.jenesis.repository.hooks.testkit;

/**
 * The coordinate {@link HookTestFormat} resolves a kit request path to - restated on the fixture side so a hold record
 * can be seeded and read back at exactly the key the hook writes it to, without depending on a module whose package is
 * exported to one named module only.
 */
public final class Coordinates {

    private Coordinates() {
    }

    /** The coordinate for a {@code /kit/...} request path. */
    public static String of(String path) {
        return path.startsWith(HookTestFormat.PREFIX)
                ? path.substring(HookTestFormat.PREFIX.length()).replace('/', ':')
                : path;
    }
}
