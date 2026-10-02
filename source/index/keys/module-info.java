/**
 * The storage keys the published-index feature writes, in a module with no dependencies, so a reader that does not
 * carry the index and its zstd chunk codec - the console - can require the one spelling. A reader with a copy that
 * drifted would not fail: it would render "no index published yet".
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.index.keys {
    exports build.jenesis.repository.index.keys;
}
