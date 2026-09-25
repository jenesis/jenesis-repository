package build.jenesis.repository.cli;

import module java.base;

/**
 * The content-lifecycle verbs: {@code staging} promotes or drops staged uploads, {@code cleanup}/{@code retention}
 * and {@code pins} drive the retention sweep, {@code purge} reclaims a removed module's data, {@code forwarding}
 * works the publish-through outbox, and {@code index} reports the published-index status.
 */
final class LifecycleCommands {

    private LifecycleCommands() {
    }

    static int staging(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: staging <repo> | staging promote|drop <repo> <id>");
        }
        RepositoryClient client = CliSupport.client(home);
        switch (args[1]) {
            case "promote" -> {
                return stagingAction(client, args, true);
            }
            case "drop" -> {
                return stagingAction(client, args, false);
            }
            default -> {
                List<RepositoryClient.StagingEntry> entries = client.staging(args[1]);
                if (entries == null) {
                    System.out.println("Staging is not installed on this deployment.");
                    return 0;
                }
                if (entries.isEmpty()) {
                    System.out.println("No staging in progress.");
                    return 0;
                }
                for (RepositoryClient.StagingEntry entry : entries) {
                    System.out.printf("%-24s %-8s items=%d%n", entry.id(), entry.state(), entry.items());
                }
                return 0;
            }
        }
    }

    private static int stagingAction(RepositoryClient client, String[] args, boolean promote)
            throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException("Usage: staging " + (promote ? "promote" : "drop") + " <repo> <id>");
        }
        int status = promote ? client.promoteStaging(args[2], args[3]) : client.dropStaging(args[2], args[3]);
        return switch (status) {
            case 200 -> {
                System.out.println((promote ? "Promoted " : "Dropped ") + args[3] + ".");
                yield 0;
            }
            case 409 -> {
                System.out.println("Staging id " + args[3] + " is already sealed.");
                yield 1;
            }
            case 501 -> {
                System.out.println("Staging is not installed on this deployment.");
                yield 1;
            }
            default -> {
                System.out.println("Staging " + (promote ? "promote" : "drop") + " failed (HTTP " + status + ").");
                yield 1;
            }
        };
    }

    static int index(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: index <repo>");
        }
        RepositoryClient.IndexDescriptor descriptor = CliSupport.client(home).index(args[1]);
        if (descriptor == null) {
            System.out.println("The published-index module is not installed on this deployment.");
            return 0;
        }
        long records = 0;
        long compressed = 0;
        for (RepositoryClient.IndexChunk chunk : descriptor.chunks()) {
            records += chunk.records();
            compressed += chunk.compressedSize();
        }
        System.out.println("generation: " + descriptor.generation());
        System.out.println("watermark:  " + descriptor.watermark());
        System.out.println("chunks:     " + descriptor.chunks().size());
        System.out.println("records:    " + records);
        System.out.println("compressed: " + compressed + " bytes");
        return 0;
    }

    static int cleanup(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: cleanup <repo> [plan]");
        }
        boolean plan = args.length > 2 && args[2].equals("plan");
        RepositoryClient client = CliSupport.client(home);
        RepositoryClient.CleanupReport report = plan ? client.cleanupPlan(args[1]) : client.cleanup(args[1]);
        if (report == null) {
            System.out.println("Retention is not installed on this deployment.");
            return 0;
        }
        if (report.evicted().isEmpty()) {
            System.out.println(plan ? "Nothing would be evicted." : "Nothing was evicted.");
        } else {
            System.out.println(plan ? "would evict:" : "evicted:");
            report.evicted().forEach(line -> System.out.println("  " + line));
            if (report.evictedCount() > report.evicted().size()) {
                System.out.println("  ... and " + (report.evictedCount() - report.evicted().size()) + " more");
            }
        }
        if (!plan) {
            System.out.println(report.blobsReclaimed() + " blob(s) reclaimed.");
        }
        return 0;
    }

    /** The removed-module reclamation, dry-run first by design: {@code purge} alone prints the orphaned-data report,
     *  {@code purge <module>} the dry-run listing of what a purge would delete, and {@code purge <module> --delete}
     *  prints that same listing before deleting - the operator always sees the blast radius, and nothing is ever
     *  removed without the module named explicitly and the flag passed. Every branch also prints what the purge
     *  deliberately cannot reach: the reserved key spaces no module may declare, so a report that lists none
     *  of them is never mistaken for a store that holds nothing there. */
    static int purge(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length < 2) {
            RepositoryClient.OrphansView report = client.orphans();
            if (report.orphans().isEmpty()) {
                System.out.println("No orphaned module data detected.");
            } else {
                System.out.println("orphaned data (modules no longer installed):");
                for (RepositoryClient.OrphanView orphan : report.orphans()) {
                    System.out.printf("  %s: %d object(s), %d byte(s)%n",
                            orphan.namespace(), orphan.objects(), orphan.bytes());
                }
                System.out.println("Nothing is removed automatically. Run 'purge <module>' for the dry-run plan.");
            }
            unreachable(report.unreachable(), report.note());
            return 0;
        }
        String namespace = args[1];
        boolean delete = args.length > 2 && args[2].equals("--delete");
        RepositoryClient.PurgeReport plan = client.purge(namespace, true);
        if (plan == null) {
            System.out.println("No storage namespace is registered for '" + namespace + "'.");
            return 1;
        }
        if (plan.objects() == 0) {
            System.out.println("Nothing is stored under the key-spaces of " + namespace + ".");
            unreachable(plan.unreachable(), plan.note());
            return 0;
        }
        System.out.println(delete ? "deleting:" : "would delete:");
        for (RepositoryClient.PurgeSpace space : plan.spaces()) {
            System.out.printf("  %s: %d object(s), %d byte(s)%n", space.prefix(), space.objects(), space.bytes());
        }
        if (!delete) {
            System.out.printf("%d object(s), %d byte(s) in total. Re-run with --delete to purge.%n",
                    plan.objects(), plan.bytes());
            unreachable(plan.unreachable(), plan.note());
            return 0;
        }
        RepositoryClient.PurgeReport purged = client.purge(namespace, false);
        System.out.printf("%d object(s), %d byte(s) purged.%n",
                purged == null ? 0 : purged.objects(), purged == null ? 0 : purged.bytes());
        unreachable(plan.unreachable(), plan.note());
        return 0;
    }

    /** Print what a purge deliberately cannot reach, beside every blast radius it prints. Silent only when the server
     *  named nothing - an older deployment, not an empty exclusion. */
    private static void unreachable(List<String> spaces, String note) {
        if (spaces == null || spaces.isEmpty()) {
            return;
        }
        System.out.println("not reachable by any purge: " + String.join(", ", spaces));
        if (note != null && !note.isBlank()) {
            System.out.println("  " + note);
        }
    }

    static int retention(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: retention <repo> | retention set <repo> [--keep-last N] "
                    + "[--max-age D] [--prerelease-expiry D] [--not-downloaded-for D]");
        }
        RepositoryClient client = CliSupport.client(home);
        if (args[1].equals("set")) {
            if (args.length < 3) {
                throw new IllegalArgumentException("Usage: retention set <repo> [--keep-last N] [--max-age D] "
                        + "[--prerelease-expiry D] [--not-downloaded-for D]");
            }
            String repo = args[2];
            int keepLast = 0;
            String maxAge = null;
            String prereleaseExpiry = null;
            String notDownloadedFor = null;
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--keep-last" -> keepLast = Integer.parseInt(CliSupport.flag(args, ++i));
                    case "--max-age" -> maxAge = CliSupport.flag(args, ++i);
                    case "--prerelease-expiry" -> prereleaseExpiry = CliSupport.flag(args, ++i);
                    case "--not-downloaded-for" -> notDownloadedFor = CliSupport.flag(args, ++i);
                    default -> throw new IllegalArgumentException("Unknown retention flag '" + args[i] + "'");
                }
            }
            if (!client.setRetention(repo, keepLast, maxAge, prereleaseExpiry, notDownloadedFor)) {
                System.out.println("Retention is not installed on this deployment.");
                return 1;
            }
            System.out.println("Set the retention policy of " + repo + ".");
            return 0;
        }
        RepositoryClient.RetentionView view = client.retention(args[1]);
        if (view == null) {
            System.out.println("Retention is not installed on this deployment.");
            return 0;
        }
        System.out.println("keep-last:          " + view.keepLast());
        System.out.println("max-age:            " + CliSupport.orDash(view.maxAge()));
        System.out.println("prerelease-expiry:  " + CliSupport.orDash(view.prereleaseExpiry()));
        System.out.println("not-downloaded-for: " + CliSupport.orDash(view.notDownloadedFor()));
        return 0;
    }

    static int pins(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: pins <repo> | pins pin|unpin <repo> <ecosystem> <coordinate> <version>");
        }
        RepositoryClient client = CliSupport.client(home);
        switch (args[1]) {
            case "pin", "unpin" -> {
                if (args.length < 6) {
                    throw new IllegalArgumentException(
                            "Usage: pins " + args[1] + " <repo> <ecosystem> <coordinate> <version>");
                }
                if (args[1].equals("pin")) {
                    client.pin(args[2], args[3], args[4], args[5]);
                    System.out.println("Pinned " + args[4] + ":" + args[5] + " in " + args[2] + ".");
                } else {
                    client.unpin(args[2], args[3], args[4], args[5]);
                    System.out.println("Unpinned " + args[4] + ":" + args[5] + " in " + args[2] + ".");
                }
                return 0;
            }
            default -> {
                List<String> pinned = client.pins(args[1]);
                if (pinned.isEmpty()) {
                    System.out.println("No pins.");
                } else {
                    pinned.forEach(System.out::println);
                }
                return 0;
            }
        }
    }

    static int forwarding(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: forwarding <repo> | forwarding retry <repo> <path>"
                    + " | forwarding internal [remove] <repo> <dest-tenant> <dest-repo>");
        }
        RepositoryClient client = CliSupport.client(home);
        if (args[1].equals("internal")) {
            boolean remove = args.length > 2 && args[2].equals("remove");
            int first = remove ? 3 : 2;
            if (args.length < first + 3) {
                throw new IllegalArgumentException(
                        "Usage: forwarding internal [remove] <repo> <dest-tenant> <dest-repo>");
            }
            String repo = args[first], tenant = args[first + 1], destination = args[first + 2];
            if (!remove) {
                client.addInternalForward(repo, tenant, destination);
                System.out.println("Forwarding " + repo + " to " + tenant + "/" + destination + ".");
                return 0;
            }
            if (client.removeInternalForward(repo, tenant, destination)) {
                System.out.println("Stopped forwarding " + repo + " to " + tenant + "/" + destination + ".");
                return 0;
            }
            System.out.println(repo + " was not forwarded to " + tenant + "/" + destination + ".");
            return 1;
        }
        if (args[1].equals("retry")) {
            if (args.length < 4) {
                throw new IllegalArgumentException("Usage: forwarding retry <repo> <path>");
            }
            if (client.retryForwarding(args[2], args[3])) {
                System.out.println("Unparked " + args[3] + " for another delivery attempt.");
                return 0;
            }
            System.out.println("Nothing parked is queued at " + args[3] + ".");
            return 1;
        }
        List<RepositoryClient.ForwardingEntry> entries = client.forwarding(args[1]);
        if (entries.isEmpty()) {
            System.out.println("The forwarding outbox is empty.");
            return 0;
        }
        for (RepositoryClient.ForwardingEntry entry : entries) {
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-8s %s (attempts=%d, delivered=%d)",
                    entry.status(), entry.path(), entry.attempts(), entry.delivered()));
            if (entry.error() != null && !entry.error().isEmpty()) {
                line.append(" - ").append(entry.error());
            }
            System.out.println(line);
        }
        return 0;
    }

    /**
     * The deprecation and end-of-life marks a repository carries.
     *
     * <p>A mark is advisory rather than a hold - the version keeps resolving, because breaking a build is not how
     * you tell someone to move on - so this is the surface that says which coordinates carry one and lets an
     * operator add or lift them.
     */
    static int lifecycle(String[] args, Path home) throws Exception {
        String action = args.length > 1 ? args[1] : "";
        switch (action) {
            case "mark" -> {
                if (args.length < 6) {
                    throw new IllegalArgumentException("Usage: lifecycle mark <repo> <coordinate> <version> "
                            + "<state> [--message <text>]");
                }
                String message = null;
                for (int i = 6; i < args.length; i++) {
                    switch (args[i]) {
                        case "--message" -> message = CliSupport.flag(args, ++i);
                        default -> throw new IllegalArgumentException("Unknown mark flag '" + args[i] + "'");
                    }
                }
                CliSupport.client(home).markLifecycle(args[2], args[3], args[4], args[5], message);
                System.out.println("Marked " + args[3] + "@" + args[4] + " " + args[5] + ".");
            }
            case "clear" -> {
                if (args.length < 5) {
                    throw new IllegalArgumentException("Usage: lifecycle clear <repo> <coordinate> <version>");
                }
                CliSupport.client(home).clearLifecycle(args[2], args[3], args[4]);
                System.out.println("Cleared the mark on " + args[3] + "@" + args[4] + ".");
            }
            default -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException("Usage: lifecycle <repo> [--limit N] [--cursor TOKEN] | "
                            + "lifecycle mark ... | lifecycle clear <repo> <coordinate> <version>");
                }
                // A mark exists per marked version, so the whole-repository listing is a page and the answer carries
                // the cursor that continues it. A caller wanting all of them follows that cursor.
                Integer limit = null;
                String cursor = null;
                for (int i = 2; i < args.length; i++) {
                    switch (args[i]) {
                        case "--limit" -> limit = Integer.valueOf(CliSupport.flag(args, ++i));
                        case "--cursor" -> cursor = CliSupport.flag(args, ++i);
                        default -> throw new IllegalArgumentException("Unknown lifecycle flag '" + args[i] + "'");
                    }
                }
                System.out.println(CliSupport.client(home).lifecycleMarks(args[1], cursor, limit));
            }
        }
        return 0;
    }

    /**
     * Drop every record of one ecosystem from a repository.
     *
     * <p>Destructive and deliberately explicit: it is the operation for a format that was published into a
     * repository by mistake, and there is no undo, so the ecosystem is named rather than inferred.
     */
    static int forgetEcosystem(String[] args, Path home) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: forget-ecosystem <repo> <ecosystem>");
        }
        CliSupport.client(home).forgetEcosystem(args[1], args[2]);
        System.out.println("Forgot the " + args[2] + " records in " + args[1] + ".");
        return 0;
    }
}
