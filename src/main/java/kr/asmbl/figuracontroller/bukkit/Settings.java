package kr.asmbl.figuracontroller.bukkit;

import org.bukkit.configuration.file.FileConfiguration;

/** `config.yml` — 설명은 그 파일에 */
public record Settings(boolean commandFeedback, boolean debug, int saveDelayTicks, int oldCoreWarnTicks) {
    public static Settings from(FileConfiguration c) {
        return new Settings(
                c.getBoolean("commandFeedback", false),
                c.getBoolean("debug", false),
                Math.max(1, c.getInt("saveDelayTicks", 100)),
                Math.max(20, c.getInt("oldCoreWarnTicks", 200)));
    }
}
