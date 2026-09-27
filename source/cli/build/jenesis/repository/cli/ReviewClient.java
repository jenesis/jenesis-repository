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

    /** Release a held artifact into the repository's layout. */
    public void releaseQuarantine(String repo, String path) throws IOException, InterruptedException {
        require(send("POST", "/api/quarantine/release?repo=" + enc(repo), body(Map.of("path", path)),
                "application/json"), 200, "release " + path);
    }

    /** Discard a held artifact so it is never served. */
    public void discardQuarantine(String repo, String path) throws IOException, InterruptedException {
        require(send("POST", "/api/quarantine/discard?repo=" + enc(repo), body(Map.of("path", path)),
                "application/json"), 200, "discard " + path);
    }

    /** One quarantine hold: when it was recorded, the path within the repository and coordinate, the gate verdict and the reasons. */
    public record QuarantineEvent(String when, String path, String coordinate, String verdict, List<String> reasons) {
    }

    private record QuarantineView(List<QuarantineEvent> events) {
    }
}
