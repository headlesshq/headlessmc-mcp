package io.github.headlesshq.mcp.hmc;

import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.ElicitationRequest;
import io.quarkiverse.mcp.server.ElicitationResponse;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.mcpjava.server.Cancellation;
import org.mcpjava.server.progress.Progress;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a tool call can do with the MCP client while it runs: ask the user (elicitation),
 * report progress and check whether the client cancelled the call.
 * <p>
 * Once the tool call has returned, e.g. because a command keeps running after the timeout,
 * the interaction is {@link #detach() detached} and nothing is sent to the client anymore.
 */
public final class Interaction {
    private static final Logger LOG = Logger.getLogger(Interaction.class);
    private static final Duration ASK_TIMEOUT = Duration.ofMinutes(10);
    public static final Interaction NONE = new Interaction(null, null, null);

    private final AtomicLong progressCounter = new AtomicLong();
    private final @Nullable Elicitation elicitation;
    private final @Nullable Progress progress;
    private final @Nullable Cancellation cancellation;
    private volatile boolean detached;

    public Interaction(@Nullable Elicitation elicitation, @Nullable Progress progress, @Nullable Cancellation cancellation) {
        this.elicitation = elicitation;
        this.progress = progress;
        this.cancellation = cancellation;
    }

    /**
     * @return whether the client can show the user a form (form mode elicitation).
     */
    public boolean canAsk() {
        return !detached && elicitation != null && elicitation.isFormModeSupported();
    }

    /**
     * Asks the user for a line of text. Never use this for passwords or other secrets,
     * the MCP specification forbids requesting sensitive information with form elicitation.
     *
     * @param message the question.
     * @return the answer, or empty if the client can not ask or the user declined.
     */
    public Optional<String> ask(String message) {
        if (!canAsk()) {
            return Optional.empty();
        }

        ElicitationResponse response = elicitation.requestBuilder()
            .setMessage(message)
            .addSchemaProperty("answer", ElicitationRequest.StringSchema.builder()
                .setTitle("Answer")
                .setRequired(true)
                .build())
            .setTimeout(ASK_TIMEOUT)
            .build()
            .sendAndAwait();
        if (!response.actionAccepted() || response.content() == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(response.content().getString("answer"));
    }

    /**
     * Asks the user to confirm something with a checkbox.
     *
     * @param message what the user should confirm.
     * @param label   the label of the checkbox.
     * @return empty if the client can not ask, otherwise whether the user confirmed.
     */
    public Optional<Boolean> confirm(String message, String label) {
        if (!canAsk()) {
            return Optional.empty();
        }

        ElicitationResponse response = elicitation.requestBuilder()
            .setMessage(message)
            .addSchemaProperty("confirm", ElicitationRequest.BooleanSchema.builder()
                .setTitle(label)
                .setRequired(true)
                .build())
            .setTimeout(ASK_TIMEOUT)
            .build()
            .sendAndAwait();
        return Optional.of(response.actionAccepted()
                               && response.content() != null
                               && Boolean.TRUE.equals(response.content().getBoolean("confirm")));
    }

    /**
     * Asks the client to show the user a URL to open (URL mode elicitation), e.g. to log in.
     * Does not wait for the user.
     *
     * @return whether the request has been sent, if not the caller has to show the URL some other way.
     */
    public boolean openUrl(String message, String url) {
        if (detached || elicitation == null || !elicitation.isUrlModeSupported()) {
            return false;
        }

        elicitation.urlRequestBuilder()
            .setMessage(message)
            .setUrl(url)
            .setTimeout(ASK_TIMEOUT)
            .build()
            .send()
            .subscribe().with(
                response -> LOG.debugf("URL elicitation for %s: %s", url, response.action()),
                failure -> LOG.debugf(failure, "URL elicitation for %s failed", url));
        return true;
    }

    /**
     * Sends a progress notification, if the client asked for them.
     */
    public void progress(String message) {
        if (detached || progress == null || progress.token().isEmpty()) {
            return;
        }

        try {
            progress.notificationBuilder()
                .setProgress(progressCounter.incrementAndGet())
                .setMessage(message)
                .build()
                .sendAndForget();
        } catch (RuntimeException e) {
            LOG.debugf(e, "Failed to send progress %s", message);
        }
    }

    /**
     * @return whether the client cancelled the tool call.
     */
    public boolean isCancelled() {
        return !detached && cancellation != null && cancellation.check().isRequested();
    }

    /**
     * Called once the tool call has returned.
     */
    public void detach() {
        detached = true;
    }

}
