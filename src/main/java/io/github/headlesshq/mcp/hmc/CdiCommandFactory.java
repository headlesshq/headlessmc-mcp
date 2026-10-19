package io.github.headlesshq.mcp.hmc;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import picocli.CommandLine;

/**
 * Creates picocli commands, completion candidates etc. as CDI beans if possible,
 * like quarkus-picocli does, falling back to the default picocli factory otherwise
 * (e.g. for sub commands that do not need injection, like {@code account login}).
 */
final class CdiCommandFactory implements CommandLine.IFactory {
    private final CommandLine.IFactory fallback = CommandLine.defaultFactory();

    @Override
    public <K> K create(Class<K> cls) throws Exception {
        InstanceHandle<K> handle = Arc.container().instance(cls);
        if (handle.isAvailable()) {
            return handle.get();
        }

        return fallback.create(cls);
    }

}
