package com.rotatesmp.kasa;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class KasaPlugin extends JavaPlugin {

    private CrateManager crates;

    @Override
    public void onEnable() {
        crates = new CrateManager(this);
        crates.load();

        CrateCommand executor = new CrateCommand(crates);
        for (String name : new String[]{"kasakoy", "kasaanahtarver"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(executor);
                pc.setTabCompleter(executor);
            }
        }

        getServer().getPluginManager().registerEvents(new CrateListener(crates), this);
        getLogger().info("RotateKasa aktif. Kayitli kasa sayisi: " + crates.count());
    }

    @Override
    public void onDisable() {
        if (crates != null) {
            crates.finishAllRolls();
            crates.save();
        }
    }
}
