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
 * <p>A route of a module the deployment left out answers {@code 404} like a request naming something absent, and
 * only this node knows which routes it has, so it says so; the {@code jenrepo} CLI turns the header into its own exit
 * code. A route that exists and answers {@code 404} for something absent or hidden carries no header, so it says what
 * this node carries and nothing about what the store holds.
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
