package build.jenesis.repository.cli;

import module java.base;

/**
 * The build-cache project verbs: list and inspect the projects on the volume, create one, edit its well-known cache
 * values, and start a sweep.
 *
 * <p><b>Why these exist at all.</b> They were the console's alone. Creating a project, pointing a build at it and
 * forcing an eviction could be done by clicking and by nothing else, so a CI job could not provision its own cache
 * and an operator could not script a reclaim - which is what "one capability, three surfaces" is there to prevent.
 * The API twin these call is itself a thin layer over the same {@code CacheService} the console screens use, so all
 * three surfaces reach one implementation rather than three that agree for now.
 *
 * <p><b>An eviction starts a pass; it does not finish one.</b> The sweep walks the project's entries off the
 * request path, so the answer is whether this call started it - {@code started:false} means one was already
 * running, not that anything failed - and the counts that follow are read back from the project's stored stats.
 */
final class CacheCommands {

    private CacheCommands() {
    }

    /** The passes a project accepts, named as the server routes them. */
    private static final Set<String> PASSES = Set.of("size", "ttl", "clear");

    static int projects(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            return print(CliSupport.client(home).buildCache().cacheProjects());
        }
        return switch (args[1]) {
            case "show" -> print(CliSupport.client(home).buildCache().cacheProject(name(args, "show <project>")));
            case "create" -> create(args, home);
            case "evict" -> evict(args, home);
            case "recount" -> print(CliSupport.client(home).buildCache().recountCache(name(args, "recount <project>")));
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
        return print(CliSupport.client(home).buildCache().evictCache(project, args[3]));
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
        return print(CliSupport.client(home).buildCache().deleteCacheProject(project));
    }

    private static int create(String[] args, Path home) throws Exception {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        Map<String, String> settings = CliSupport.sets(rest);
        String project = name(rest.toArray(String[]::new), "create <project> [--set <key>=<value>]...");
        return print(CliSupport.client(home).buildCache().createCacheProject(project, settings));
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
