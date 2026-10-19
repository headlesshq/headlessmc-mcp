package io.github.headlesshq.hmcmcp.loader;

import cpw.mods.fml.common.Mod;
import io.github.headlesshq.hmcmcp.HmcMcp;

/**
 * For Forge 1.7.10. The version comes from the mcmod.info.
 * HMC-Specifics is loaded as a tweaker, not as a mod, so it can not be declared as a dependency.
 */
@Mod(modid = HmcMcp.ID, name = HmcMcp.NAME)
public class LegacyForgeMod {
    public LegacyForgeMod() {
        HmcMcp.onModConstructed("Forge 1.7.10");
    }

}
