package build.jenesis.repository.events.test;

import module java.base;
import module org.slf4j;

/**
 * A capturing slf4j {@link SLF4JServiceProvider} installed for the events test module, so the WARNING that {@link
 * build.jenesis.repository.events.EventSink#emit} logs when a discovered sink is swallowed can be asserted
 * deterministically rather than depending on the deployment's real slf4j binding. Because the events test module
 * carries no other slf4j provider, this is the bound provider, and its {@link ILoggerFactory} returns loggers that
 * record WARNING-and-above into {@link #WARNINGS}, each as {@code name|message[|thrown]} (slf4j renders {@code {}}
 * placeholders positionally); a test filters them by logger name and drains the list first. A record-only provider:
 * no events test asserts on console output, so binding slf4j here changes nothing observable except making the
 * diagnostic capturable (the events SPI's proof of the §9 contract that a fail-soft still emits a diagnostic).
 */
public final class CapturingLoggerFinder implements SLF4JServiceProvider {

    /** Captured WARNING-or-higher records, each as {@code name|message[|thrown]}; drained by the asserting test. */
    static final List<String> WARNINGS = new CopyOnWriteArrayList<>();

    private final ILoggerFactory loggerFactory = CapturingLogger::new;
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new BasicMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
    }

    /** Records WARNING-and-above into {@link #WARNINGS} in {@code name|message[|thrown]} form. */
    private static final class CapturingLogger extends LegacyAbstractLogger {

        private CapturingLogger(String name) {
            this.name = name;
        }

        @Override
        public boolean isTraceEnabled() {
            return true;
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                                                   Object[] arguments, Throwable throwable) {
            if (level.toInt() >= Level.WARN.toInt()) {
                String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
                WARNINGS.add(getName() + "|" + message + (throwable == null ? "" : "|" + throwable));
            }
        }
    }
}
