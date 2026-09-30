package build.jenesis.repository.application;

import module java.base;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Marks a {@code 404} that no route answered with {@code Jenesis-Installed: false}.
 *
 * <p>The product is assembled from modules a deployment may leave out, and an absent module serves nothing, so a
 * request for its routes is answered {@code 404} exactly like a request that names something absent. The two mean
 * opposite things to a caller - one is worth asking again with other arguments, the other never is - and only this
 * node knows which routes it has, so it says so rather than leaving a client to keep its own copy of which module
 * serves what. The {@code jenrepo} CLI reads the header to answer with its own exit code.
 *
 * <p>Only a request no handler matched carries it. A route that exists and answers {@code 404} for an absent or
 * hidden tenant, repository or artifact does not, so the header says what this node carries and nothing about what
 * the store holds. The answer is otherwise the ordinary {@code 404}, rendered by the same error handling.
 */
@ControllerAdvice
public class UnservedRouteAdvice {

    /** The header a {@code 404} for a route this node does not have carries, with the value {@code false}. */
    public static final String INSTALLED = "Jenesis-Installed";

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void unserved(HttpServletResponse response) throws IOException {
        if (!response.isCommitted()) {
            response.setHeader(INSTALLED, "false");
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
