package io.github.headlesshq.mcp.tools;

/**
 * Builds command lines for HeadlessMc and HMC-Specifics, which split them like a shell (JLine's DefaultParser).
 */
final class Args {
    private Args() {
        throw new AssertionError();
    }

    /**
     * @return the argument in double quotes, with backslashes and quotes escaped.
     */
    static String quote(String arg) {
        return '"' + arg.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * @return the argument as is, if it is a single word, otherwise quoted.
     */
    static String word(String arg) {
        return arg.isEmpty() || arg.chars().anyMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'' || c == '\\')
            ? quote(arg)
            : arg;
    }

}
