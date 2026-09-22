package build.jenesis.repository.cli;

import module java.base;

/**
 * The verbs over evidence supplied from outside: third-party scanner reports, VEX statements saying what an advisory
 * actually means for this product, and the test runs the selection service learns from.
 *
 * <p>All three ingest a document produced by another tool, so each takes a file path rather than asking a caller to
 * inline a payload - which is also what lets a CI job or an agent use them without assembling JSON.
 */
final class ScanCommands {

    private ScanCommands() {
    }

    static int vex(String[] args, Path home) throws Exception {
        String action = args.length > 1 ? args[1] : "";
        switch (action) {
            case "show" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: vex show <id>");
                }
                System.out.println(CliSupport.client(home).vexStatement(args[2]));
            }
            case "add" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: vex add <file>");
                }
                System.out.println(CliSupport.client(home).addVex(Files.readString(Path.of(args[2]))));
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: vex remove <id>");
                }
                CliSupport.client(home).removeVex(args[2]);
                System.out.println("Withdrew VEX statement " + args[2] + ".");
            }
            case "export" -> {
                String output = null;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equals("--output")) {
                        output = CliSupport.flag(args, ++i);
                    } else {
                        throw new IllegalArgumentException("Unknown export flag '" + args[i] + "'");
                    }
                }
                String document = CliSupport.client(home).exportVex();
                if (output == null) {
                    System.out.println(document);
                } else {
                    Files.writeString(Path.of(output), document);
                    System.out.println("Wrote " + output + ".");
                }
            }
            default -> {
                String repo = null;
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("--repo")) {
                        repo = CliSupport.flag(args, ++i);
                    } else {
                        throw new IllegalArgumentException("Unknown vex action or flag '" + args[i] + "'");
                    }
                }
                System.out.println(CliSupport.client(home).vexStatements(repo));
            }
        }
        return 0;
    }

    static int scans(String[] args, Path home) throws Exception {
        String action = args.length > 1 ? args[1] : "";
        switch (action) {
            case "show" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: scans show <id>");
                }
                System.out.println(CliSupport.client(home).scan(args[2]));
            }
            case "report" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: scans report <id>");
                }
                System.out.println(CliSupport.client(home).scanReport(args[2]));
            }
            case "analytics" -> {
                boolean asReport = args.length > 2 && args[2].equals("report");
                System.out.println(CliSupport.client(home).scanAnalytics(asReport));
            }
            case "ingest" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: scans ingest <file> [--repo R]");
                }
                String repo = null;
                for (int i = 3; i < args.length; i++) {
                    if (args[i].equals("--repo")) {
                        repo = CliSupport.flag(args, ++i);
                    } else {
                        throw new IllegalArgumentException("Unknown ingest flag '" + args[i] + "'");
                    }
                }
                System.out.println(CliSupport.client(home)
                        .ingestScan(Files.readString(Path.of(args[2])), repo));
            }
            default -> {
                String repo = null;
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("--repo")) {
                        repo = CliSupport.flag(args, ++i);
                    } else {
                        throw new IllegalArgumentException("Unknown scans action or flag '" + args[i] + "'");
                    }
                }
                System.out.println(CliSupport.client(home).scans(repo));
            }
        }
        return 0;
    }

    static int tests(String[] args, Path home) throws Exception {
        String action = args.length > 1 ? args[1] : "";
        switch (action) {
            case "ingest" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: tests ingest <file> [--repo R]");
                }
                String repo = null;
                for (int i = 3; i < args.length; i++) {
                    if (args[i].equals("--repo")) {
                        repo = CliSupport.flag(args, ++i);
                    } else {
                        throw new IllegalArgumentException("Unknown ingest flag '" + args[i] + "'");
                    }
                }
                System.out.println(CliSupport.client(home)
                        .ingestTestRun(Files.readString(Path.of(args[2])), repo));
            }
            case "show" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: tests show <id>");
                }
                System.out.println(CliSupport.client(home).testRun(args[2]));
            }
            case "flaky" -> {
                String repo = null;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equals("--repo")) {
                        repo = CliSupport.flag(args, ++i);
                    } else {
                        throw new IllegalArgumentException("Unknown flaky flag '" + args[i] + "'");
                    }
                }
                System.out.println(CliSupport.client(home).flakyTests(repo));
            }
            case "select" -> {
                String repo = null;
                String changed = null;
                for (int i = 2; i < args.length; i++) {
                    switch (args[i]) {
                        case "--repo" -> repo = CliSupport.flag(args, ++i);
                        case "--changed" -> changed = CliSupport.flag(args, ++i);
                        default -> throw new IllegalArgumentException("Unknown select flag '" + args[i] + "'");
                    }
                }
                System.out.println(CliSupport.client(home).selectTests(repo, changed));
            }
            default -> throw new IllegalArgumentException(
                    "Usage: tests ingest <file> | tests show <id> | tests flaky | tests select");
        }
        return 0;
    }
}
