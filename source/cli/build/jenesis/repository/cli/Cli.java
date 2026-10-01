package build.jenesis.repository.cli;

import module java.base;

/**
 * The {@code jenrepo} command-line entry point.
 *
 * <p>It is a thin dispatcher over {@link Session} (the stored login) and {@link RepositoryClient} (the HTTP API),
 * and that thinness is the design: the CLI holds no state and makes no decision the API does not, so what it offers
 * <em>is</em> the API and the two cannot drift. Anything an operator can do through the admin console is meant to be
 * reachable here, because the third audience for this tool is a program - a CI job or an agent - that needs the
 * whole product without a browser and without hand-assembling JSON bodies.
 *
 * <p>The verbs, their help and this dispatch table all come from {@link Commands}, so a documented verb exists and
 * an existing verb is documented. {@code help} renders it, {@code skill} summarises it for a program.
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.exit(fail(e, 2));
        } catch (Exception e) {
            System.exit(fail(e, 1));
        }
    }

    /** Report a failure in whichever shape the caller asked for, and hand back its exit code. */
    private static int fail(Exception e, int code) {
        if (Output.isJson()) {
            Output.utf8(System.err).println(error(e.getMessage(), null, code));
        } else {
            System.err.println("error: " + e.getMessage());
        }
        return code;
    }

    /** Dispatch one command line and return the process exit code, without exiting - the testable core that
     *  {@link #main} wraps with {@link System#exit}. */
    public static int run(String[] args) throws Exception {
        try {
            int code = dispatch(strip(args));
            if (Refresh.unwatched()) {
                // Said rather than swallowed: --refresh is global, so it is accepted on every command and means
                // something only where work outlives the request. A caller who asked to watch a point read has
                // been given a one-shot answer, and should hear that rather than assume the loop is running.
                System.err.println("--refresh: this command has nothing to watch; it answered once.");
            }
            return code;
        } finally {
            // One invocation's mode must not leak into the next: these run in the same JVM under test.
            Output.reset();
            Refresh.reset();
        }
    }

    /**
     * Take {@code --json} off the line before any handler sees it.
     *
     * <p>It is a mode rather than an argument - it changes how everything is reported, not what one command does -
     * and every handler rejects flags it does not know, which is the behaviour that catches a typo. Removing it
     * here keeps both of those true at once.
     */
    private static String[] strip(String[] args) {
        List<String> kept = new ArrayList<>(args.length);
        for (String arg : args) {
            if (arg.equals("--json")) {
                Output.json();
            } else if (arg.equals("--refresh") || arg.startsWith("--refresh=")) {
                // Global, like --json, and for the same reason: a command's own argument parsing should never
                // have to know about a mode that is not about what it does.
                Refresh.requested(arg);
            } else {
                kept.add(arg);
            }
        }
        return kept.toArray(String[]::new);
    }

    private static int dispatch(String[] args) throws Exception {
        if (args.length == 0) {
            overview(System.out);
            return 1;
        }
        String first = args[0];
        if (first.equals("help") || first.equals("--help") || first.equals("-h")) {
            if (args.length > 1) {
                return detail(args[1]);
            }
            overview(System.out);
            return 0;
        }
        if (first.equals("skill")) {
            skill();
            return 0;
        }
        Commands.Noun noun = Commands.BY_NAME.get(first);
        if (noun == null) {
            System.err.println("error: unknown command '" + first + "'");
            suggest(first).ifPresent(near -> System.err.println("did you mean '" + near + "'?"));
            System.err.println("run 'jenrepo help' for the commands, or 'jenrepo skill' for a briefing.");
            return 2;
        }
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                return detail(first);
            }
        }
        try {
            if (!Output.isJson()) {
                return noun.handler().run(args, Session.home());
            }
            // The human rendering is what --json replaces, so it is dropped rather than interleaved with the
            // document. Anything a command writes to stderr still goes to stderr.
            PrintStream real = System.out;
            int code;
            try {
                System.setOut(new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
                code = noun.handler().run(args, Session.home());
            } finally {
                System.setOut(real);
            }
            Output.flush(Output.utf8(real));
            return code;
        } catch (RepositoryClient.NotInstalled missing) {
            return report(3, "'" + noun.name() + "' is not served by this deployment.",
                    missing.getMessage() + " Run 'capabilities' to see what it carries.");
        }
    }

    /** An error, in whichever shape the caller asked for; always on stderr, so stdout stays parseable. */
    private static int report(int code, String message, String remedy) {
        if (Output.isJson()) {
            Output.utf8(System.err).println(error(message, remedy, code));
        } else {
            System.err.println("error: " + message);
            if (remedy != null) {
                System.err.println(remedy);
            }
        }
        return code;
    }

    /** One error document, assembled by the mapper rather than by concatenation. */
    private static String error(String message, String remedy, int code) {
        SequencedMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("error", message);
        if (remedy != null) {
            fields.put("remedy", remedy);
        }
        fields.put("exit", code);
        return Output.document(fields);
    }

    /** The nearest known command by edit distance, when it is near enough to be a typo rather than a guess. */
    private static Optional<String> suggest(String typed) {
        return Commands.BY_NAME.keySet().stream()
                .map(name -> Map.entry(name, distance(typed, name)))
                .filter(entry -> entry.getValue() <= Math.max(1, typed.length() / 3))
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    private static int distance(String one, String other) {
        int[] previous = new int[other.length() + 1];
        int[] current = new int[other.length() + 1];
        for (int j = 0; j <= other.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= one.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= other.length(); j++) {
                int substitution = previous[j - 1] + (one.charAt(i - 1) == other.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[other.length()];
    }

    /** One noun's full help: every action, with the exact form to type. */
    private static int detail(String name) {
        Commands.Noun noun = Commands.BY_NAME.get(name);
        if (noun == null) {
            System.err.println("error: unknown command '" + name + "'");
            return 2;
        }
        System.out.println(noun.name() + " - " + noun.summary());
        System.out.println();
        int width = noun.actions().stream().mapToInt(action -> action.form().length()).max().orElse(0);
        for (Commands.Action action : noun.actions()) {
            System.out.println("  " + pad(action.form(), width) + "  " + action.summary());
        }
        return 0;
    }

    /** The map of the product: every noun under its heading, with one line each. */
    private static void overview(PrintStream out) {
        out.println("jenrepo - operate a Jenesis repository from the command line");
        out.println();
        out.println("Usage: jenrepo <command> [action] [arguments]");
        out.println("       jenrepo help <command>   every action on one command");
        out.println("       jenrepo skill            a briefing for a program driving this tool");
        out.println();
        out.println("Global: --json               answer with the API's own JSON instead of the human rendering");
        out.println("        --refresh[=30s|5m]  where a command starts work that outlives it, reprint the state");
        out.println("                            until it finishes; the interval defaults to what it is watching");
        int width = Commands.BY_NAME.keySet().stream().mapToInt(String::length).max().orElse(0);
        for (Commands.Section section : Commands.SECTIONS) {
            out.println();
            out.println(section.title());
            for (Commands.Noun noun : section.nouns()) {
                out.println("  " + pad(noun.name(), width) + "  " + noun.summary());
            }
        }
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    /**
     * The briefing for a program.
     *
     * <p>An agent driving a CLI it has not seen fails in a small number of predictable ways: it invents flags, it
     * cannot tell a refusal from a breakage, and it does not know what it is allowed to do. This says those things
     * outright rather than making them inferable from the help.
     */
    private static void skill() {
        System.out.println("""
                jenrepo - a Jenesis artifact repository, driven from the command line.

                WHAT IT IS
                  Every command is one HTTP call to the repository's API - the same API the admin console and any
                  other client uses. There is nothing this tool can do that the API cannot, and the intent is that
                  there is nothing the console can do that this tool cannot. You never need to construct a JSON
                  body: arguments and flags carry everything.

                GETTING A SESSION
                  jenrepo login <url> --key <key>
                  The URL and key are stored under ~/.jenesis (override with JENREPO_CLI_HOME) and every later
                  command uses them. 'whoami' shows what is stored. Without a session every command that needs one
                  fails immediately and says so.

                FINDING YOUR WAY
                  help              every command, grouped by what it is about
                  help <command>    every action on one command, with the exact form to type
                  capabilities      what THIS deployment carries - formats, modules, features

                  Read 'capabilities' first. The product is assembled from modules, so a command can be absent from
                  a given server, and knowing that up front is cheaper than discovering it from an error.

                SHAPE OF A COMMAND
                  jenrepo <noun> [action] [arguments] [--flags]
                  The noun is the subject (a repository, the credentials, the findings). With no action a noun
                  reads: 'pins <repo>' lists the pins, 'pins pin <repo> ...' adds one. Reads never change anything.

                  A <repo> is a repository of the tenant your key belongs to; name another tenant's as
                  <tenant>/<repo>. A repository is served at /repository/<tenant>/<repo>/, container images at
                  /v2/<tenant>/<repo>/<image>.

                EXIT CODES
                  0  it worked
                  1  the server refused, or could not be reached - the message says which
                  2  the command line was wrong (unknown command, missing argument)
                  3  this deployment does not serve the command - the server said it has no route for it

                  Code 3 is the one worth handling: the request was well formed and this deployment simply does not
                  offer that capability. Do not retry it, and do not treat it as a bug in your arguments.

                OUTPUT
                  A command that starts work outliving the request returns as soon as the work is accepted; it
                  never holds the connection open, because that fails on exactly the deployment where the work
                  takes long enough to be worth watching. Pass --refresh to watch instead: the state is reprinted
                  until the work reaches a terminal state, and the exit code is that state's. The interval comes
                  from what is being watched - a requested walk is picked up within half a minute, an import's
                  counters move every few seconds - or say it outright with --refresh=10s. Under --json a
                  refreshing command still prints exactly one value: the last one, because a script wants the
                  outcome and a person wants the progress.

                  Pass --json to any command and stdout is exactly one JSON value: the answer the server gave,
                  verbatim - so it carries every field the API returns, including ones this tool does not render.
                  A command that makes several calls answers with an array of them, a non-JSON body (a PEM, a CSV,
                  an XML SBOM) arrives as {"contentType":...,"body":...}, and a command that changes something
                  without being answered gives {"ok":true}. Prefer it: it is the stable contract.

                  Errors are JSON too under --json - {"error":...,"remedy":...,"exit":N} - and go to stderr, as
                  every message does, so stdout is only ever the answer.

                  Without --json the output is line-oriented: one record per line, whitespace-separated columns,
                  '-' where a value is absent.

                WHERE THE DANGER IS
                  Most commands read. These write, and two of them delete data that is not recoverable:
                    quarantine discard   drops held files
                    purge --delete       reclaims a removed module's stored data
                  'purge' without --delete and 'cleanup plan' are dry runs that report what would happen. Prefer
                  them first.""");
    }
}
