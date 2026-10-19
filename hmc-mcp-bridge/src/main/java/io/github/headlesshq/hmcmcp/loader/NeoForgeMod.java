package io.github.headlesshq.hmcmcp.loader;

import io.github.headlesshq.hmcmcp.HmcMcp;
import net.neoforged.fml.common.Mod;

@Mod(HmcMcp.ID)
public class NeoForgeMod {
    public NeoForgeMod() {
        HmcMcp.onModConstructed("NeoForge");
    }

}
