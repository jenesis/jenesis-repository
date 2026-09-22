package build.jenesis.repository.cli;

import module java.base;

/**
 * The read-only discovery verbs: {@code browse} and {@code search} walk a repository's layout, {@code assets}
 * exports the published-asset enumeration, {@code dependents} answers the reverse-dependency index, and {@code sbom}
 * emits a CycloneDX / SPDX bill of materials - the outbound mirror of the import connectors.
 */
final class DiscoveryCommands {

    private DiscoveryCommands() {
    }

    static int browse(String[] args, Path home) throws Exception {
        if (args.length > 1 && args[1].equals("children")) {
            if (args.length < 3) {
                throw new IllegalArgumentException("Usage: browse children <repo> [prefix]");
            }
            // The folder probe rather than a listing: it answers which folders exist under a prefix in a bounded
            // read, which is what a caller walking a deep layout wants instead of every entry beneath it.
            System.out.println(CliSupport.client(home).browseChildren(args[2], args.length > 3 ? args[3] : ""));
            return 0;
        }
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: browse <repo> [prefix] | browse children <repo> [prefix]");
        }
        String prefix = args.length > 2 ? args[2] : "";
        List<String> entries = CliSupport.client(home).browse(args[1], prefix);
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
        List<String> results = CliSupport.client(home).search(args[1], query);
        results.forEach(System.out::println);
        if (results.isEmpty()) {
            System.out.println("No matches.");
        }
        return 0;
    }

    /** The published-asset enumeration - the free {@code /api/assets} walk (path, size, SHA-256 and, where the format
     *  describes one, the coordinate), the outbound mirror of the import connectors so getting your data out is never
     *  the paid feature. Prints one page and its resume cursor, or with {@code --all} follows the cursor to the end. */
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
            RepositoryClient.AssetPage page = client.assets(repo, cursor, limit);
            for (RepositoryClient.AssetEntry asset : page.assets()) {
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
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: dependents <repo> [coordinate]");
        }
        String coordinate = args.length > 2 ? args[2] : null;
        RepositoryClient.DependentsReport report = CliSupport.client(home).dependents(args[1], coordinate);
        if (report == null) {
            System.out.println("The reverse-dependency index is not installed on this deployment.");
            return 0;
        }
        if (coordinate == null) {
            List<String> coordinates = report.coordinates();
            if (coordinates == null || coordinates.isEmpty()) {
                System.out.println("The reverse-dependency index is empty (enable 'dependents-index' and let the sweep run).");
                return 0;
            }
            coordinates.forEach(System.out::println);
            return 0;
        }
        List<String> dependents = report.dependents();
        if (dependents == null || dependents.isEmpty()) {
            System.out.println("Nothing recorded depends on " + coordinate + ".");
            return 0;
        }
        dependents.forEach(System.out::println);
        return 0;
    }

    /**
     * Generate and download an SBOM for a hosted coordinate (a repository path) or a whole repository, in CycloneDX
     * (default), CycloneDX XML or SPDX. Written to a {@code --output} file, or printed - the outbound counterpart of
     * the reverse-dependency read, so the tenant's contents leave in a standard interchange format.
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
        String bom = CliSupport.client(home).sbom(repo, path, format);
        if (output != null) {
            Files.writeString(Path.of(output), bom);
            System.out.println("Wrote the " + (path == null ? "repository" : path) + " SBOM to " + output + ".");
        } else {
            System.out.print(bom);
        }
        return 0;
    }
}
