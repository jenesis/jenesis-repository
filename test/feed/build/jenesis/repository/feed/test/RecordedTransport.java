package build.jenesis.repository.feed.test;

import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.feed.FeedTransport;

import module java.base;

/**
 * A {@link FeedTransport} answering from a scripted list of steps, recording every request it was handed and the
 * per-request timeout the client allowed it - the offline stand-in a contract suite drives a whole feed with. A step
 * may instead inject an {@link IOException}, so a transport failure and its retry are exercised without a socket.
 * Once the script runs out its last step repeats, and each send builds a fresh body stream so a repeated step is
 * never a spent one.
 */
final class RecordedTransport implements FeedTransport {

    /** One scripted answer: a status and body to hand back, or a failure to throw. */
    record Step(int status, String body, Map<String, List<String>> headers, IOException failure) {

        static Step answering(int status, String body) {
            return new Step(status, body, Map.of(), null);
        }

        static Step answering(int status, String body, Map<String, List<String>> headers) {
            return new Step(status, body, headers, null);
        }

        static Step failing(IOException failure) {
            return new Step(0, "", Map.of(), failure);
        }

        FeedResponse response() {
            return new FeedResponse(status, headers,
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private final List<Step> script;
    private final List<FeedRequest> requests = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();
    private int sent;

    RecordedTransport(Step... steps) {
        script = List.of(steps);
    }

    @Override
    public FeedResponse send(FeedRequest request, Duration timeout) throws IOException {
        requests.add(request);
        timeouts.add(timeout);
        Step step = script.get(Math.min(sent++, script.size() - 1));
        if (step.failure() != null) {
            throw step.failure();
        }
        return step.response();
    }

    /** Every request the client sent, in order. */
    List<FeedRequest> requests() {
        return List.copyOf(requests);
    }

    /** Every per-request timeout the client allowed, in order. */
    List<Duration> timeouts() {
        return List.copyOf(timeouts);
    }

    /** How many requests were sent. */
    int sent() {
        return sent;
    }
}
