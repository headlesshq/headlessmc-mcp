package io.github.headlesshq.mcp.hmc;

import io.github.headlesshq.headlessmc.console.Console;
import io.github.headlesshq.headlessmc.console.ConsoleProvider;
import io.github.headlesshq.headlessmc.console.Password;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugs into HeadlessMc's {@link ConsoleProvider} SPI before all of HeadlessMc's own consoles.
 * Those write to the stdout file descriptor, which is the MCP channel, so this provider never fails:
 * while a {@link CommandJob} runs, output goes into the job, otherwise to stderr.
 * <p>
 * When HeadlessMc asks for input, the user is asked through the MCP client (form elicitation).
 * When it prints the link for the Microsoft device code login, the client is asked to open it (URL elicitation).
 */
@Mcp
@ApplicationScoped
public class McpConsoleProvider implements ConsoleProvider {
    /**
     * Printed by HeadlessMc's device code login, see McAuthenticator.
     */
    private static final Pattern LOGIN_URL = Pattern.compile("To login visit (https://\\S+)");

    private final CommandService commandService;

    @Inject
    public McpConsoleProvider(CommandService commandService) {
        this.commandService = commandService;
    }

    @Override
    public Console get() {
        CommandJob job = commandService.current();
        return new JobConsole(job);
    }

    @Override
    public int sort() {
        return 0; // before ConsoleProvider.SORT_CACHE
    }

    private record JobConsole(@Nullable CommandJob job) implements Console {
        @Override
        public void write(String message) {
            write(message, "\n");
        }

        @Override
        public void write(String message, String nextLine) {
            String text = message + nextLine.replace(System.lineSeparator(), "\n");
            if (job == null) {
                System.err.print(text);
                System.err.flush();
                return;
            }

            job.append(text);
            Matcher login = LOGIN_URL.matcher(message);
            if (login.find()) {
                // the device code login waits until the user has logged in, let the client open the page
                job.getInteraction().openUrl("Log in with your Microsoft account to use it with HeadlessMc.", login.group(1));
            }
        }

        @Override
        public String read(String message) {
            write(message, "\n");
            return ask(message);
        }

        @Override
        public String read() {
            if (job == null) {
                throw new UnsupportedOperationException("HeadlessMc asked for input outside of a command.");
            }

            // Console.read(String) has written the question before calling read()
            String[] lines = job.getOutput().strip().split("\n");
            return ask(lines[lines.length - 1]);
        }

        private String ask(String question) {
            String prompt = question.strip();
            if (job == null || !job.getInteraction().canAsk()) {
                throw new UnsupportedOperationException("HeadlessMc asked \"" + prompt + "\", but the MCP client can not ask the user"
                    + " (no support for elicitation). Pass all arguments to the command instead.");
            }

            Optional<String> answer = job.getInteraction().ask("HeadlessMc asks: " + prompt);
            if (answer.isEmpty()) {
                throw new UnsupportedOperationException("HeadlessMc asked \"" + prompt + "\", but the user declined to answer.");
            }

            job.append(answer.get() + "\n");
            return answer.get();
        }

        @Override
        public Password readPassword() {
            // the MCP specification forbids asking for secrets with form elicitation
            throw new UnsupportedOperationException("HeadlessMc asked for a password or token, which is never requested over MCP."
                + " To log in to a Microsoft account run `login`, it opens a page in the browser of the user instead.");
        }
    }

}
