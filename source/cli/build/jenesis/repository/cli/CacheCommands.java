package build.jenesis.repository.cli;

import module java.base;

/**
 * The build-cache project verbs: list and inspect the projects on the volume, create one, edit its well-known cache
 * values, and start a sweep.
 *
 * <p>An eviction starts a pass off the request path; the answer is whether this call started it -
 * {@code started:false} means one was already running - and the counts are read back from the project's stats.
 * With {@code --refresh} an eviction, a recount or a deletion is watched from its start until the pass ends, read
 * from the project's own detail, which carries the pass state; a deletion ends when the project is gone.
 */
final class CacheCommands {

    private CacheCommands() {
    }

    /** How fast a project's pass moves: a walk of its entries, seconds for a small project and minutes for a large
     *  one - the cadence a bare {@code --refresh} watches it at. */
    private static final Duration PASS = Duration.ofSeconds(5);

    /** The passes a project accepts, named as the server routes them. */
    private static final Set<String> PASSES = Set.of("size", "ttl", "clear");

    static int projects(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            return print(CliSupport.client(home).buildCache().cacheProjects());
        }
        return switch (args[1]) {
            case "show" -> print(CliSupport.client(home).buildCache().cacheProject(name(args, "show <project>")));
            case "create" -> create(args, home);
            case "describe" -> describe(args, home);
            case "evict" -> evict(args, home);
            case "recount" -> {
                String project = name(args, "recount <project>");
                BuildCacheClient cache = CliSupport.client(home).buildCache();
                yield watched(cache, project, false, () -> cache.recountCache(project));
            }
            case "delete" -> delete(args, home);
            case "settings" -> settings(args, home);
            default -> throw new IllegalArgumentException("Unknown projects action '" + args[1] + "'");
        };
    }

    private static int settings(String[] args, Path home) throws Exception {
        SettingsClient settings = CliSupport.client(home).settings();
        return AdminCommands.objectSettings("projects", args, new AdminCommands.ObjectSettings() {
            @Override
            public List<SettingsClient.Setting> list(String name) throws IOException, InterruptedException {
                return settings.projectSettings(name);
            }

            @Override
            public void set(String name, String key, String value) throws IOException, InterruptedException {
                settings.setProjectSetting(name, key, value);
            }

            @Override
            public void clear(String name, String key) throws IOException, InterruptedException {
                settings.clearProjectSetting(name, key);
            }
        });
    }

    private static int evict(String[] args, Path home) throws Exception {
        String project = name(args, "evict <project> <size|ttl|clear>");
        if (args.length < 4 || !PASSES.contains(args[3])) {
            throw new IllegalArgumentException("Usage: projects evict <project> <size|ttl|clear>");
        }
        BuildCacheClient cache = CliSupport.client(home).buildCache();
        return watched(cache, project, false, () -> cache.evictCache(project, args[3]));
    }

    private static int delete(String[] args, Path home) throws Exception {
        String project = name(args, "delete <project> [--yes]");
        if (!Arrays.asList(args).subList(3, args.length).contains("--yes") && !AdminCommands.confirmed(project,
                "Deleting build-cache project " + project + " removes every cached entry and its cache settings; "
                        + "the next builds run without them. Grants naming it stay on their credentials. "
                        + "This cannot be undone.")) {
            System.out.println("Nothing was deleted.");
            return 1;
        }
        BuildCacheClient cache = CliSupport.client(home).buildCache();
        return watched(cache, project, true, () -> cache.deleteCacheProject(project));
    }

    /** What starts a project's pass and answers what the server said. */
    @FunctionalInterface
    private interface Start {
        String start() throws Exception;
    }

    /**
     * Start a pass and print the answer; under {@code --refresh}, watch it until it ends. The first reading is the
     * start, so a watched pass and a single answer are the same sequence of requests.
     */
    private static int watched(BuildCacheClient cache, String project, boolean deleting, Start start)
            throws Exception {
        AtomicBoolean first = new AtomicBoolean(true);
        Refresh.Poll poll = () -> {
            if (first.getAndSet(false)) {
                System.out.println(start.start());
                return Refresh.Poll.State.running();
            }
            return passState(cache.projectPass(project), project, deleting);
        };
        return Refresh.on() ? Refresh.until(PASS, poll) : poll.once().code();
    }

    /** Print one reading of a project's pass, and say whether there is any point asking again. */
    private static Refresh.Poll.State passState(Optional<BuildCacheClient.ProjectPass> read, String project,
                                                boolean deleting) {
        if (read.isEmpty()) {
            System.out.println(deleting ? "Project " + project + " is gone." : "There is no project " + project + ".");
            return Refresh.Poll.State.done(deleting ? 0 : 1);
        }
        BuildCacheClient.ProjectPass pass = read.get();
        if (pass.running()) {
            System.out.println("Project " + project + ": " + (pass.action().isEmpty() ? "a pass" : pass.action())
                    + " running; --refresh watches it finish.");
            return Refresh.Poll.State.running();
        }
        System.out.println("Project " + project + ": " + pass.action() + " " + pass.outcome() + "; "
                + pass.entries() + " entries, " + pass.bytes() + " bytes.");
        // A deletion that left the project behind stopped part way; deleting it again carries on.
        return Refresh.Poll.State.done(pass.failed() || deleting ? 1 : 0);
    }

    private static int create(String[] args, Path home) throws Exception {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        Map<String, String> settings = CliSupport.sets(rest);
        if (rest.size() < 4) {
            throw new IllegalArgumentException(
                    "Usage: projects create <project> <type> [description] [--set <key>=<value>]...");
        }
        return print(CliSupport.client(home).buildCache().createCacheProject(rest.get(2), rest.get(3),
                String.join(" ", rest.subList(4, rest.size())), settings));
    }

    private static int describe(String[] args, Path home) throws Exception {
        String project = name(args, "describe <project> [description]");
        return print(CliSupport.client(home).buildCache().describeCacheProject(project,
                String.join(" ", Arrays.asList(args).subList(3, args.length))));
    }

    private static String name(String[] args, String usage) {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: projects " + usage);
        }
        return args[2];
    }

    private static int print(String body) {
        System.out.println(body);
        return 0;
    }
}
