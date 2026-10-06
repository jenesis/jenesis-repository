package build.jenesis.repository.cli;

import module java.base;

/**
 * The read-only discovery verbs: {@code browse} and {@code search} walk a repository's layout, {@code assets}
 * exports the published-asset enumeration, {@code dependents} answers what depends on a package or a version of it -
 * built against it, or declaring it - and {@code sbom} emits a CycloneDX / SPDX bill of materials - the outbound mirror
 * of the import connectors.
 */
final class DiscoveryCommands {

    private DiscoveryCommands() {
    }

    static int browse(String[] args, Path home) throws Exception {
        if (args.length > 1 && args[1].equals("children")) {
            String usage = "Usage: browse children <repo> [prefix] [--limit N] [--cursor T]";
            if (args.length < 3) {
                throw new IllegalArgumentException(usage);
            }
            // The folder probe: one bounded window under a prefix; the answer's next is the cursor.
            String prefix = "";
            String cursor = null;
            Integer limit = null;
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--cursor" -> cursor = CliSupport.flag(args, ++i);
                    case "--limit" -> limit = Integer.parseInt(CliSupport.flag(args, ++i));
                    default -> {
                        if (i != 3 || args[i].startsWith("--")) {
                            throw new IllegalArgumentException(usage);
                        }
                        prefix = args[i];
                    }
                }
            }
            System.out.println(CliSupport.client(home).contents().browseChildren(args[2], prefix, cursor, limit));
            return 0;
        }
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: browse <repo> [prefix] | browse children <repo> [prefix]");
        }
        String prefix = args.length > 2 ? args[2] : "";
        List<String> entries = CliSupport.client(home).contents().browse(args[1], prefix);
        entries.forEach(System.out::println);
        if (entries.isEmpty()) {
            System.out.println("(empty)");
        }
        return 0;
    }

    static int search(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: search <repo> [query]");
        }
        String query = args.length > 2 ? args[2] : "";
        ContentsClient.Found found = CliSupport.client(home).contents().search(args[1], query);
        if (!"FULL_TEXT".equals(found.mode())) {
            System.out.println("Looked up by the start of a name: full-text search is off for " + args[1] + ".");
        } else if (!found.indexed()) {
            System.out.println("Looked up by the start of a name: the full-text index of " + args[1]
                    + " is not built yet.");
        }
        found.results().forEach(System.out::println);
        if (found.results().isEmpty()) {
            System.out.println("No matches.");
        }
        return 0;
    }

    /** The published-asset enumeration - the {@code /api/assets} walk (path, size, SHA-256 and, where the format
     *  describes one, the coordinate), the outbound mirror of the import connectors. Prints one page and its resume
     *  cursor, or with {@code --all} follows the cursor to the end. */
    static int assets(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: assets <repo> [--limit N] [--cursor TOKEN] [--all]");
        }
        String repo = args[1];
        Integer limit = null;
        String cursor = null;
        boolean all = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--limit" -> limit = Integer.valueOf(CliSupport.flag(args, ++i));
                case "--cursor" -> cursor = CliSupport.flag(args, ++i);
                case "--all" -> all = true;
                default -> throw new IllegalArgumentException("Unknown assets flag '" + args[i] + "'");
            }
        }
        RepositoryClient client = CliSupport.client(home);
        long total = 0;
        while (true) {
            ContentsClient.AssetPage page = client.contents().assets(repo, cursor, limit);
            for (ContentsClient.AssetEntry asset : page.assets()) {
                String coordinate = asset.coordinate() == null || asset.coordinate().isBlank()
                        ? "" : "  " + asset.coordinate() + ":" + CliSupport.orDash(asset.version());
                System.out.println(asset.path() + "  " + asset.size() + "  " + asset.sha256() + coordinate);
                total++;
            }
            cursor = page.cursor();
            if (!all || cursor == null) {
                if (cursor != null) {
                    System.out.println("next cursor: " + cursor);
                } else if (total == 0) {
                    System.out.println("No assets.");
                }
                return 0;
            }
        }
    }

    static int dependents(String[] args, Path home) throws Exception {
        String usage = "Usage: dependents <repo> <ecosystem> <coordinate> [--version V] [--cursor T] "
                + "[--declared-cursor T]";
        if (args.length < 4 || args[2].startsWith("--") || args[3].startsWith("--")) {
            throw new IllegalArgumentException(usage);
        }
        String version = null;
        String cursor = null;
        String declaredCursor = null;
        for (int i = 4; i < args.length; i++) {
            switch (args[i]) {
                case "--version" -> version = CliSupport.flag(args, ++i);
                case "--cursor" -> cursor = CliSupport.flag(args, ++i);
                case "--declared-cursor" -> declaredCursor = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException(usage);
            }
        }
        ProvenanceClient.Dependents answer = CliSupport.client(home).provenance()
                .dependents(args[1], args[2], args[3], version, cursor, declaredCursor);
        String subject = answer.coordinate() + (answer.version() == null ? "" : " " + answer.version());
        resolved(answer.resolved(), subject);
        declared(answer.declared(), answer.coordinate());
        return 0;
    }

    /** The published versions built against the version asked about, each with the path its closure reaches it
     *  along, and the cursor of the next page. */
    private static void resolved(ProvenanceClient.Resolved resolved, String subject) {
        System.out.println("Resolved dependents:");
        if (resolved == null) {
            System.out.println("  Name a version (--version) to list the published versions built against it.");
            return;
        }
        List<ProvenanceClient.Dependent> dependents = resolved.dependents() == null ? List.of()
                : resolved.dependents();
        if (dependents.isEmpty() && resolved.next() == null) {
            System.out.println("  No resolved closure of a published version reaches " + subject + ".");
        }
        for (ProvenanceClient.Dependent dependent : dependents) {
            System.out.println("  " + dependent.coordinate() + " " + dependent.version() + " of "
                    + dependent.repository() + (dependent.cut() ? "  its closure stops here, held for review" : "")
                    + (dependent.byCoordinate() ? "  its " + dependent.ecosystem() + " bill names it by coordinate"
                    : ""));
            ComplianceCommands.through(dependent.path());
        }
        if (resolved.next() != null) {
            System.out.println("  more: --cursor " + resolved.next());
        }
    }

    /** The versions whose manifest declares the package, each with its requirement - and, given a version, whether
     *  the requirement admits it - and the cursor of the next page. A requirement is not a version anything was
     *  built against. */
    private static void declared(ProvenanceClient.Declared declared, String dependency) {
        System.out.println("Declared dependents:");
        if (declared == null || !declared.installed()) {
            System.out.println("  The declared-dependencies index is not installed on this deployment.");
            return;
        }
        if (declared.built() == null) {
            System.out.println("  The declared dependencies have not been indexed in full yet.");
            return;
        }
        List<ProvenanceClient.Declaration> rows = declared.declarations() == null ? List.of()
                : declared.declarations();
        if (rows.isEmpty() && declared.next() == null) {
            System.out.println("  No version declares a dependency on " + dependency + ".");
        }
        for (ProvenanceClient.Declaration row : rows) {
            System.out.println("  " + row.ecosystem() + "  " + row.coordinate() + "  " + row.version() + "  "
                    + (row.requirement() == null || row.requirement().isEmpty() ? "-" : row.requirement())
                    + (row.admits() == null ? "" : "  " + row.admits()));
        }
        if (declared.next() != null) {
            System.out.println("  more: --declared-cursor " + declared.next());
        }
    }

    /**
     * Generate and download an SBOM for a hosted coordinate (a repository path) or a whole repository, in CycloneDX
     * (default), CycloneDX XML or SPDX. Written to a {@code --output} file, or printed, so the tenant's contents
     * leave in a standard interchange format.
     */
    static int sbom(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: sbom <repo> [path] [--format cyclonedx|cyclonedx-xml|spdx] [--output <file>]");
        }
        String repo = args[1];
        String path = null;
        String format = null;
        String output = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--format" -> format = CliSupport.flag(args, ++i);
                case "--output", "-o" -> output = CliSupport.flag(args, ++i);
                default -> {
                    if (args[i].startsWith("--") || path != null) {
                        throw new IllegalArgumentException("Unexpected argument: " + args[i]);
                    }
                    path = args[i];
                }
            }
        }
        String bom = CliSupport.client(home).provenance().sbom(repo, path, format);
        if (output != null) {
            Files.writeString(Path.of(output), bom);
            System.out.println("Wrote the " + (path == null ? "repository" : path) + " SBOM to " + output + ".");
        } else {
            System.out.print(bom);
        }
        return 0;
    }
}
