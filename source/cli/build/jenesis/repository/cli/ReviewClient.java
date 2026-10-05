package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

/**
 * The queues that wait for a person's decision: what the compliance gate held for review, and the release or
 * discard that settles each hold.
 *
 * <p>Reached through {@link RepositoryClient#review()}.
 */
public final class ReviewClient extends ClientCalls {

    ReviewClient(ClientCalls calls) {
        super(calls);
    }

    /** The compliance-gate holds for a repository - what was quarantined on the publish or proxy path, with the
     *  verdict and the reasons - so a reviewer can release or discard each. */
    public List<QuarantineEvent> quarantine(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/quarantine?repo=" + enc(repo), null, null);
        require(response, 200, "read the quarantine of " + repo);
        return JSON.readValue(response.body(), QuarantineView.class).events();
    }

    /** Release held files - one, or every file of a version - into the repository's layout. */
    public void releaseQuarantine(String repo, List<String> paths) throws IOException, InterruptedException {
        require(send("POST", "/api/quarantine/release?repo=" + enc(repo), body(Map.of("paths", paths)),
                "application/json"), 200, "release " + String.join(", ", paths));
    }

    /** Hold a version for review by hand, answering whether anything was held. */
    public boolean holdVersion(String repo, String ecosystem, String coordinate, String version)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/quarantine/hold?repo=" + enc(repo),
                body(Map.of("ecosystem", ecosystem, "coordinate", coordinate, "version", version)), "application/json");
        require(response, 200, "hold " + coordinate + ":" + version);
        return JSON.readValue(response.body(), Held.class).held();
    }

    /** A hold's answer: whether anything was held. */
    public record Held(boolean held) {
    }

    /** Discard held files so they are never served, and say which were still held. */
    public Discarded discardQuarantine(String repo, List<String> paths) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/quarantine/discard?repo=" + enc(repo),
                body(Map.of("paths", paths)), "application/json");
        require(response, 200, "discard " + String.join(", ", paths));
        return JSON.readValue(response.body(), Discarded.class);
    }

    /** A discard's answer: the paths whose held bytes it dropped, and those at which nothing was held any more. */
    public record Discarded(List<String> discarded, List<String> absent) {
    }

    /** One quarantine hold: when it was recorded, the path within the repository and coordinate, the gate verdict, the
     *  reasons, the rules it is held for, and the queue's notes on it. */
    public record QuarantineEvent(String when, String path, String coordinate, String verdict, List<String> reasons,
                                  List<String> rules, List<String> notes) {
    }

    private record QuarantineView(List<QuarantineEvent> events) {
    }
}
