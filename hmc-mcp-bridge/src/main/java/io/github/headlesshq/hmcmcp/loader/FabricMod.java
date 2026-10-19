package io.github.headlesshq.hmcmcp.loader;

import io.github.headlesshq.hmcmcp.HmcMcp;
import net.fabricmc.api.ClientModInitializer;

public class FabricMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HmcMcp.onModConstructed("Fabric");
    }

}
