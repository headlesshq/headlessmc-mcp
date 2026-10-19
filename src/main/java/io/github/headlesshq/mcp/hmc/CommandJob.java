package io.github.headlesshq.mcp.hmc;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * A HeadlessMc command line executed by the {@link CommandService}, with everything it wrote to the console.
 */
public final class CommandJob {
    private static final int MAX_OUTPUT = 256 * 1024;

    private final StringBuilder output = new StringBuilder();
    private final CompletableFuture<Integer> result = new CompletableFuture<>();
    private final String id;
    private final String line;
    private final Interaction interaction;
    private volatile @Nullable Future<?> future;

    CommandJob(String id, String line, Interaction interaction) {
        this.id = id;
        this.line = line;
        this.interaction = interaction;
    }

    void setFuture(Future<?> future) {
        this.future = future;
    }

    @Nullable Future<?> getFuture() {
        return future;
    }

    void append(String text) {
        synchronized (output) {
            output.append(text);
            if (output.length() > MAX_OUTPUT) {
                output.delete(0, output.length() - MAX_OUTPUT);
            }
        }
    }

    public String getOutput() {
        synchronized (output) {
            return output.toString();
        }
    }

    public String getId() {
        return id;
    }

    public String getLine() {
        return line;
    }

    /**
     * @return the interaction with the MCP client of the tool call that started this job.
     */
    public Interaction getInteraction() {
        return interaction;
    }

    public boolean isDone() {
        return result.isDone();
    }

    /**
     * @return the exit code of the command, or {@code null} if it is still running.
     */
    public @Nullable Integer getExitCode() {
        return result.getNow(null);
    }

    public CompletableFuture<Integer> getResult() {
        return result;
    }

}
