package io.github.headlesshq.mcp.hmc;

import io.github.headlesshq.headlessmc.progressbar.ProgressBar;
import io.github.headlesshq.headlessmc.progressbar.ProgressbarService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plugs into HeadlessMc's {@link ProgressbarService} SPI, instead of the JLine progress bars, which draw to the terminal.
 * Adds one line per finished task (downloads of assets, libraries, java etc.) to the output of the running command,
 * and sends progress notifications to the client, if it asked for them.
 */
@ApplicationScoped
public class McpProgressbarService implements ProgressbarService {
    private final CommandService commandService;

    @Inject
    public McpProgressbarService(CommandService commandService) {
        this.commandService = commandService;
    }

    @Override
    public String getName() {
        return "mcp";
    }

    @Override
    public ProgressBar displayProgressBar(ProgressBar.Configuration configuration) {
        return new McpProgressBar(commandService.current(), configuration);
    }

    private static final class McpProgressBar implements ProgressBar {
        private static final long NOTIFY_INTERVAL = TimeUnit.SECONDS.toNanos(1);

        private final AtomicLong lastNotification = new AtomicLong(System.nanoTime());
        private final AtomicLong current = new AtomicLong();
        private final AtomicLong max = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ProgressBar.Configuration configuration;
        private final CommandJob job;

        private McpProgressBar(CommandJob job, ProgressBar.Configuration configuration) {
            this.job = job;
            this.configuration = configuration;
            this.max.set(configuration.initialMax());
        }

        @Override
        public boolean isDummy() {
            return false;
        }

        @Override
        public void stepBy(long n) {
            current.addAndGet(n);
            maybeNotify();
        }

        @Override
        public void stepTo(long n) {
            current.set(n);
            maybeNotify();
        }

        @Override
        public void step() {
            stepBy(1);
        }

        @Override
        public void maxHint(long n) {
            max.set(n);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true) && job != null) {
                ProgressBar.Configuration.Unit unit = configuration.unit();
                String amount = unit == null
                    ? String.valueOf(current.get())
                    : current.get() / Math.max(1, unit.size()) + " " + unit.name();
                job.append(configuration.taskName() + ": done (" + amount + ")\n");
                job.getInteraction().progress(configuration.taskName() + ": done");
            }
        }

        private void maybeNotify() {
            long now = System.nanoTime();
            long last = lastNotification.get();
            if (job != null && now - last > NOTIFY_INTERVAL && lastNotification.compareAndSet(last, now)) {
                long max = this.max.get();
                ProgressBar.Configuration.Unit unit = configuration.unit();
                long size = unit == null ? 1 : Math.max(1, unit.size());
                String suffix = unit == null ? "" : " " + unit.name();
                job.getInteraction().progress(configuration.taskName() + ": " + current.get() / size
                    + (max > 0 ? "/" + max / size : "") + suffix);
            }
        }
    }

}
