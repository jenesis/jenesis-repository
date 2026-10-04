package build.jenesis.repository.demo.web;

import module java.base;

import build.jenesis.repository.demo.Demo;
import build.jenesis.repository.demo.DemoContributor;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.store.Requests;

/**
 * The core's own demo content, contributed through the seam another module adds its own by.
 *
 * <ul>
 * <li><b>Hosted repositories</b> of Maven and npm, where those formats are installed, with first-party packages
 *     published into them as a client publishes: two versions of a small Java library, a second library the deny list
 *     names - switched to hold for review first, so it lands in the quarantine queue - and an npm package.</li>
 * <li><b>A proxy of each installed format's public registry</b> that suggests demo artifacts
 *     ({@link RepositoryFormat#demoArtifacts()} over {@link ProxyFormat#defaultUpstream()}), and those artifacts - old
 *     releases with known vulnerabilities - read through it, so they are cached, recorded and screened.</li>
 * <li><b>A walk of the store</b> asked for once the content is in, so the walks screen reports a run over it.</li>
 * <li><b>The OSV advisory feed</b> switched on, where its module is installed, and the scheduled scan asked for, which
 *     reads the feed at once, so the vulnerability screens fill with what the proxies read. The gate screens with the
 *     feed from the next restart, since a feed owns a client and is resolved as the node boots. It screens fail-closed
 *     - a publish it would screen while the database is unreachable is held - so it is switched off again when no
 *     registry answered a read, since an offline deployment would otherwise hold every publish once restarted.</li>
 * </ul>
 */
public final class StarterDemo implements DemoContributor {

    /** The settings this content switches on. */
    static final String DENY_LIST = "deny-list";
    static final String DENY_LIST_ACTION = "deny-list-action";
    static final String OSV = "osv";

    /** The pass that re-scans what the repositories hold against the advisory feeds. */
    static final String SCAN = "scan";

    /** Settings are re-read by every node on a cadence of half a minute by default, and the scan resolves its feeds
     *  from them, so it is asked for once the feed switched on has reached the node that runs it. */
    static final Duration SCAN_AFTER = Duration.ofMinutes(1);

    /** The walk is asked for once the demo's own writes have settled, so it walks what the demo left. */
    static final Duration WALK_AFTER = Duration.ofSeconds(30);

    static final String HOSTED_MAVEN = "demo-maven";
    static final String HOSTED_NPM = "demo-npm";

    static final Samples.Library GREETING = new Samples.Library("org.jenesis.demo", "greeting", "1.0.0",
            "A greeting, the first release of a first-party library published by the Jenesis demo.", "Apache-2.0");
    static final Samples.Library GREETING_NEXT = new Samples.Library("org.jenesis.demo", "greeting", "1.1.0",
            "A greeting, the second release of a first-party library published by the Jenesis demo.", "Apache-2.0");
    /** The library the deny list names, so its publish is held for review. */
    static final Samples.Library HELD = new Samples.Library("org.jenesis.demo", "legacy-crypto", "0.9.0",
            "A library the Jenesis demo's deny list names, so its publish waits for review.", "Apache-2.0");
    static final Samples.NpmPackage PACKAGE = new Samples.NpmPackage("jenesis-demo-greeting", "1.0.0",
            "A greeting, a first-party npm package published by the Jenesis demo.", "MIT");

    private static final String MAVEN = "maven";
    private static final String NPM = "npm";

    /** The installed formats with a public registry and demo artifacts to read through a proxy of it. Fixed for the
     *  life of the process, as what is installed is. */
    private static final List<Proxied> PROXIED = proxied();

    /** One format's proxy: the format, the repository's name, the registry it fetches from and what is read. */
    private record Proxied(RepositoryType type, String repository, URI registry, List<String> paths) {
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Plan plan() {
        List<String> offerable = RepositoryType.offerable();
        List<Repository> repositories = new ArrayList<>();
        SequencedMap<String, String> settings = new LinkedHashMap<>();
        List<String> reaches = new ArrayList<>();
        List<String> vulnerable = new ArrayList<>();
        if (offerable.contains(MAVEN)) {
            repositories.add(new Repository(HOSTED_MAVEN, MAVEN, "Demo: first-party Java libraries, published here.",
                    Optional.empty()));
            settings.put(DENY_LIST, HELD.coordinate() + " added, so its publish is held for review");
            settings.put(DENY_LIST_ACTION, "Hold for review, so a denied coordinate waits for a decision rather than "
                    + "being refused");
        }
        if (offerable.contains(NPM)) {
            repositories.add(new Repository(HOSTED_NPM, NPM, "Demo: first-party npm packages, published here.",
                    Optional.empty()));
        }
        for (Proxied proxied : PROXIED) {
            repositories.add(new Repository(proxied.repository(), proxied.type().name(), "Demo: a proxy of "
                    + proxied.registry() + ", caching what is read through it.", Optional.of(proxied.registry())));
            reaches.add(proxied.registry().toString());
            vulnerable.add(String.join(", ", proxied.paths().stream().map(StarterDemo::file).toList()) + ", from "
                    + proxied.registry());
        }
        if (Labels.catalogued(OSV) && !PROXIED.isEmpty()) {
            settings.put(OSV, "on, so the scan asks the advisory database about every version the proxies read "
                    + "and the gate screens with it from the next restart - and off again if no registry answers");
            reaches.add("the OSV vulnerability database (the endpoint the osv-endpoint setting names, "
                    + "api.osv.dev by default)");
        }
        return repositories.isEmpty() ? Plan.NONE : new Plan(repositories, List.of(), settings, reaches, vulnerable);
    }

    @Override
    public void load(Demo demo) throws IOException {
        Optional<RepositoryType> maven = offered(MAVEN);
        if (maven.isPresent()) {
            String denied = demo.setting(DENY_LIST);
            Set<String> listed = new LinkedHashSet<>();
            for (String coordinate : denied.split(",")) {
                if (!coordinate.isBlank()) {
                    listed.add(coordinate.trim());
                }
            }
            listed.add(HELD.coordinate());
            SequencedMap<String, String> holding = new LinkedHashMap<>();
            holding.put(DENY_LIST, String.join(",", listed));
            holding.put(DENY_LIST_ACTION, "QUARANTINE");
            demo.settings(holding);
            for (Samples.Library library : List.of(GREETING, GREETING_NEXT, HELD)) {
                demo.publish(HOSTED_MAVEN, maven.get().servedPath(library.pom()),
                        new ByteArrayInputStream(Samples.pom(library)));
                demo.publish(HOSTED_MAVEN, maven.get().servedPath(library.jar()),
                        new ByteArrayInputStream(Samples.jar(library)));
            }
        } else {
            demo.skipped("The first-party Java libraries", "No Maven format is installed in this deployment.");
        }
        Optional<RepositoryType> npm = offered(NPM);
        if (npm.isPresent()) {
            demo.publish(HOSTED_NPM, npm.get().servedPath(PACKAGE.path()),
                    new ByteArrayInputStream(Samples.publish(PACKAGE)));
        } else {
            demo.skipped("The first-party npm package", "No npm format is installed in this deployment.");
        }
        boolean screening = Labels.catalogued(OSV) && !PROXIED.isEmpty() && demo.settings(Map.of(OSV, "true"));
        int answered = 0;
        for (Proxied proxied : PROXIED) {
            for (String path : proxied.paths()) {
                Demo.Outcome read = demo.fetch(proxied.repository(), proxied.type().servedPath(path));
                answered += read == Demo.Outcome.DONE || read == Demo.Outcome.HELD ? 1 : 0;
            }
        }
        demo.request(Requests.WALK, "the demo filled its repositories, and a walk over them is what the walks "
                + "screen reports", WALK_AFTER);
        if (!screening) {
            return;
        }
        if (answered == 0) {
            demo.settings(Map.of(OSV, "false"));
            demo.skipped("Switch on " + Labels.of(OSV), "Switched off again: nothing was read from the public "
                    + "registries, and the advisory database is reached the same way. The feed screens fail-closed, "
                    + "so left on while it cannot be reached it would hold every publish.");
        } else {
            demo.request(SCAN, "the demo read versions with known vulnerabilities through its proxies", SCAN_AFTER);
        }
    }

    /** The type {@code name}, where a repository can be created as it here. */
    private static Optional<RepositoryType> offered(String name) {
        return RepositoryType.offerable().contains(name) ? RepositoryType.installed(name) : Optional.empty();
    }

    /** The last segment of a path: the file a reader recognises. */
    private static String file(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static List<Proxied> proxied() {
        List<String> offerable = RepositoryType.offerable();
        List<Proxied> proxied = new ArrayList<>();
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            if (!format.offered() || format.demoArtifacts().isEmpty() || !offerable.contains(format.name())
                    || !(format instanceof ProxyFormat proxy)) {
                continue;
            }
            Optional<URI> registry = proxy.defaultUpstream();
            Optional<RepositoryType> type = RepositoryType.installed(format.name());
            if (registry.isPresent() && type.isPresent()) {
                proxied.add(new Proxied(type.get(), "demo-" + format.name() + "-proxy", registry.get(),
                        format.demoArtifacts()));
            }
        }
        proxied.sort(Comparator.comparing(each -> each.type().name()));
        return List.copyOf(proxied);
    }
}
