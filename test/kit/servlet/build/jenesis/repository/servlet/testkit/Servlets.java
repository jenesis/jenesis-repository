package build.jenesis.repository.servlet.testkit;

import module java.base;
import java.lang.reflect.Proxy;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The request and the response a controller is handed, without a servlet container.
 *
 * <p>A handler reads a handful of things off a request - its URI, its content type, its body, a header - and writes
 * a handful onto a response: a status, a content type, a header and a body. Both are answered here by a dynamic
 * proxy over the servlet interfaces, so a suite states exactly the request it means and reads back exactly what the
 * handler wrote. Anything else a handler asks answers as an unset servlet attribute would: {@code null}, zero or
 * {@code false}.
 */
public final class Servlets {

    private Servlets() {
    }

    /** A request for {@code uri} with no body, no content type and no header. */
    public static HttpServletRequest request(String method, String uri) {
        return request(method, uri, null, new byte[0], Map.of());
    }

    /** A request for {@code uri} carrying {@code body} as {@code contentType}, with the given headers. */
    public static HttpServletRequest request(String method, String uri, String contentType, byte[] body,
                                             Map<String, String> headers) {
        ServletInputStream in = new BodyStream(new ByteArrayInputStream(body));
        Map<String, String> named = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        named.putAll(headers);
        return (HttpServletRequest) Proxy.newProxyInstance(Servlets.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (_, method1, arguments) -> switch (method1.getName()) {
                    case "getMethod" -> method;
                    case "getRequestURI" -> uri;
                    case "getServletPath" -> uri;
                    case "getContextPath" -> "";
                    case "getContentType" -> contentType;
                    case "getContentLengthLong" -> (long) body.length;
                    case "getContentLength" -> body.length;
                    case "getInputStream" -> in;
                    case "getHeader" -> named.get((String) arguments[0]);
                    case "getHeaders" -> Collections.enumeration(named.containsKey((String) arguments[0])
                            ? List.of(named.get((String) arguments[0])) : List.of());
                    case "getHeaderNames" -> Collections.enumeration(named.keySet());
                    case "toString" -> method + " " + uri;
                    case "hashCode" -> System.identityHashCode(uri);
                    case "equals" -> false;
                    default -> unset(method1.getReturnType());
                });
    }

    /** A {@code multipart/form-data} request for {@code uri} carrying one file part named {@code field}. */
    public static HttpServletRequest multipart(String uri, String field, String filename, byte[] content) {
        String boundary = "servlets-boundary-7f3a";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\""
                + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return request("POST", uri, "multipart/form-data; boundary=" + boundary, body.toByteArray(), Map.of());
    }

    /** A response that keeps what the handler wrote to it. */
    public static Response response() {
        return new Response();
    }

    private static Object unset(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return type == double.class ? 0d : type == float.class ? 0f : type == short.class ? (short) 0 : (byte) 0;
    }

    /** What a handler wrote: the status ({@code 200} until one is set), the content type, the headers and the body. */
    public static final class Response {

        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final PrintWriter writer = new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8), true);
        private final ServletOutputStream out = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }

            @Override
            public void write(int b) {
                body.write(b);
            }
        };
        private int status = 200;
        private String contentType;
        private final HttpServletResponse servlet = (HttpServletResponse) Proxy.newProxyInstance(
                Servlets.class.getClassLoader(), new Class<?>[] {HttpServletResponse.class},
                (_, method, arguments) -> switch (method.getName()) {
                    case "setStatus", "sendError" -> {
                        status = (Integer) arguments[0];
                        yield null;
                    }
                    case "getStatus" -> status;
                    case "setContentType" -> {
                        contentType = (String) arguments[0];
                        yield null;
                    }
                    case "getContentType" -> contentType;
                    case "setHeader", "addHeader" -> {
                        headers.put((String) arguments[0], (String) arguments[1]);
                        if ("Content-Type".equalsIgnoreCase((String) arguments[0])) {
                            contentType = (String) arguments[1];
                        }
                        yield null;
                    }
                    case "getHeader" -> headers.get((String) arguments[0]);
                    case "containsHeader" -> headers.containsKey((String) arguments[0]);
                    case "getWriter" -> writer;
                    case "getOutputStream" -> out;
                    case "getCharacterEncoding" -> "UTF-8";
                    case "toString" -> "response " + status;
                    case "hashCode" -> System.identityHashCode(this);
                    case "equals" -> false;
                    default -> unset(method.getReturnType());
                });

        private Response() {
        }

        /** The response to hand the handler. */
        public HttpServletResponse servlet() {
            return servlet;
        }

        public int status() {
            return status;
        }

        public String contentType() {
            return contentType;
        }

        public String header(String name) {
            return headers.get(name);
        }

        /** The body as UTF-8 text, whether the handler wrote it through the writer or the stream. */
        public String body() {
            writer.flush();
            return body.toString(StandardCharsets.UTF_8);
        }

        /** The body's bytes. */
        public byte[] bytes() {
            writer.flush();
            return body.toByteArray();
        }
    }

    /** A request body the handler reads once. */
    private static final class BodyStream extends ServletInputStream {

        private final InputStream in;

        private BodyStream(InputStream in) {
            this.in = in;
        }

        @Override
        public boolean isFinished() {
            try {
                return in.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return in.read(buffer, offset, length);
        }
    }
}
