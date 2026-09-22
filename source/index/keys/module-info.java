/**
 * The storage keys the published-index feature writes, in a module light enough for a reader to require.
 *
 * <p>The index itself rides in {@code build.jenesis.repository.index} together with the zstd chunk codec, which the
 * console deliberately does not carry (&sect;2) - so the console could not reference the descriptor key and spelled
 * it as a literal of its own instead. The two agreed, and if they stopped agreeing the console would not fail: it
 * would read nothing and render "no index published yet", which is the silent-wrong-answer shape a shared constant
 * exists to make impossible.
 *
 * <p>This module is the home both nodes can see. It carries constants and nothing else - no dependencies at all -
 * so requiring it from a read surface adds no weight to that node's graph.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.index.keys {
    exports build.jenesis.repository.index.keys;
}
