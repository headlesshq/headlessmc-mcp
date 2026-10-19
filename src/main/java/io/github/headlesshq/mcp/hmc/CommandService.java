package io.github.headlesshq.mcp.hmc;

import io.github.headlesshq.headlessmc.commands.HeadlessMcCommand;
import io.github.headlesshq.headlessmc.console.ArgSplitter;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.Writer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes HeadlessMc command lines, like the HeadlessMc shell would.
 * <p>
 * HeadlessMc is a single user shell, its commands and services are not designed to run concurrently,
 * so commands are executed one after another on a single thread. While a command runs, the
 * {@link McpConsoleProvider} routes everything HeadlessMc writes to its console into the {@link CommandJob}.
 */
@ApplicationScoped
public class CommandService {
    private static final Logger LOG = Logger.getLogger(CommandService.class);
    private static final int MAX_JOBS = 50;
    private static final long CANCEL_POLL_MILLIS = 250;
    private static final int CANCELLED = 130;
    /**
     * Commands that make no sense over MCP, {@code exit} would call System.exit.
     */
    private static final Set<String> UNSUPPORTED = Set.of("exit", "quit");

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hmc-mcp-command");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, CommandJob> jobs = new LinkedHashMap<>();
    private final AtomicReference<@Nullable CommandJob> current = new AtomicReference<>();
    private final AtomicLong ids = new AtomicLong();
    private final ArgSplitter argSplitter;

    @Inject
    public CommandService(ArgSplitter argSplitter) {
        this.argSplitter = argSplitter;
    }

    void onShutdown(@Observes ShutdownEvent event) {
        executor.shutdownNow();
    }

    /**
     * Submits a command line and waits for it to finish, at most for the given timeout.
     * If the command takes longer it keeps running, check {@link CommandJob#isDone()}.
     * If the client cancels the tool call, the command is cancelled as well.
     *
     * @param line        the command line, without the leading {@code headlessmc}.
     * @param timeout     how long to wait.
     * @param interaction the interaction with the client of the tool call, used to ask the user for input.
     * @return the job, done or still running.
     */
    public CommandJob execute(String line, Duration timeout, Interaction interaction) {
        CommandJob job = submit(line, interaction);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!job.isDone()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }

                if (interaction.isCancelled()) {
                    cancel(job);
                    break;
                }

                try {
                    job.getResult().get(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, CANCEL_POLL_MILLIS), TimeUnit.MILLISECONDS);
                } catch (TimeoutException | ExecutionException ignored) {
                    // the caller checks isDone, the output contains the error
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!job.isDone()) {
            // the tool call returns, the command can not ask the user or report progress anymore
            interaction.detach();
        }

        return job;
    }

    public CommandJob submit(String line, Interaction interaction) {
        CommandJob job = new CommandJob(String.valueOf(ids.incrementAndGet()), line.strip(), interaction);
        synchronized (jobs) {
            jobs.put(job.getId(), job);
            while (jobs.size() > MAX_JOBS) {
                jobs.remove(jobs.keySet().iterator().next());
            }
        }

        job.setFuture(executor.submit(() -> run(job)));
        return job;
    }

    /**
     * Cancels a job: a running command is interrupted, a queued command does not run at all.
     */
    public void cancel(CommandJob job) {
        Future<?> future = job.getFuture();
        if (job.isDone() || future == null) {
            return;
        }

        job.append("Cancelled.\n");
        if (future.cancel(true) && current.get() != job) {
            // it did not start yet, so run will not complete it
            job.getResult().complete(CANCELLED);
        }
    }

    public Optional<CommandJob> get(String id) {
        synchronized (jobs) {
            return Optional.ofNullable(jobs.get(id));
        }
    }

    /**
     * @return the job that is currently running on the command thread, if there is one.
     */
    public @Nullable CommandJob current() {
        return current.get();
    }

    private void run(CommandJob job) {
        current.set(job);
        int exitCode = 1;
        try {
            exitCode = execute(job);
        } catch (Throwable throwable) { // report everything, the command thread has to survive
            LOG.debugf(throwable, "Command %s failed", job.getLine());
            job.append("Error: " + describe(throwable) + "\n");
        } finally {
            Thread.interrupted();
            current.set(null);
            job.getResult().complete(exitCode);
        }
    }

    private int execute(CommandJob job) {
        String[] args = argSplitter.split(job.getLine());
        if (args.length == 0) {
            // without arguments HeadlessMcCommand would start its interactive shell loop
            return 0;
        }

        if (UNSUPPORTED.contains(args[0].toLowerCase())) {
            job.append("The " + args[0] + " command is not supported over MCP.\n");
            return 1;
        }

        PrintWriter out = new PrintWriter(new JobWriter(job), true);
        CommandLine commandLine = new CommandLine(HeadlessMcCommand.class, new CdiCommandFactory());
        commandLine.setOut(out);
        commandLine.setErr(out);
        commandLine.setUsageHelpWidth(120);
        commandLine.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> {
            job.append("Error: " + describe(exception) + "\n");
            return cmd.getCommandSpec().exitCodeOnExecutionException();
        });

        try {
            return commandLine.execute(args);
        } finally {
            out.flush();
        }
    }

    static String describe(Throwable throwable) {
        StringBuilder message = new StringBuilder(String.valueOf(throwable.getMessage() == null
            ? throwable.getClass().getSimpleName()
            : throwable.getMessage()));
        Throwable cause = throwable.getCause();
        int depth = 0;
        while (cause != null && cause != throwable && depth++ < 8) {
            message.append("\n  Caused by: ").append(cause);
            throwable = cause;
            cause = cause.getCause();
        }

        return message.toString();
    }

    private static final class JobWriter extends Writer {
        private final CommandJob job;

        private JobWriter(CommandJob job) {
            this.job = job;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            job.append(new String(buffer, offset, length));
        }

        @Override
        public void flush() {
            // written immediately
        }

        @Override
        public void close() {
            // nothing to close
        }
    }

}
