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

    static int cache(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: cache <projects|show|create|config|evict|recount> [...]");
        }
        return switch (args[1]) {
            case "projects" -> print(CliSupport.client(home).cacheProjects());
            case "show" -> print(CliSupport.client(home).cacheProject(name(args, "show <project>")));
            case "create" -> print(CliSupport.client(home).createCacheProject(name(args, "create <project>")));
            case "config" -> config(args, home);
            case "evict" -> evict(args, home);
            case "recount" -> print(CliSupport.client(home).recountCache(name(args, "recount <project>")));
            default -> throw new IllegalArgumentException("Unknown cache command '" + args[1] + "'");
        };
    }

    private static int config(String[] args, Path home) throws Exception {
        String project = name(args, "config <project> [--size <cap>] [--lru <true|false>] [--ttl <duration>]");
        String size = null, lru = null, ttl = null;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--size" -> size = CliSupport.flag(args, ++i);
                case "--lru" -> lru = CliSupport.flag(args, ++i);
                case "--ttl" -> ttl = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException("Usage: cache config <project> [--size <cap>] "
                        + "[--lru <true|false>] [--ttl <duration>]");
            }
        }
        return print(CliSupport.client(home).saveCacheConfig(project, size, lru, ttl));
    }

    private static int evict(String[] args, Path home) throws Exception {
        String project = name(args, "evict <project> <size|ttl|clear>");
        if (args.length < 4 || !PASSES.contains(args[3])) {
            throw new IllegalArgumentException("Usage: cache evict <project> <size|ttl|clear>");
        }
        return print(CliSupport.client(home).evictCache(project, args[3]));
    }

    private static String name(String[] args, String usage) {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: cache " + usage);
        }
        return args[2];
    }

    private static int print(String body) {
        System.out.println(body);
        return 0;
    }
}
