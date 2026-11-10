package net.atlasmc;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AfkManager implements CommandExecutor, Listener {

    private final AtlasMC plugin;
    private final String worldName;
    private World world;
    private final Map<UUID, Location> back = new HashMap<>();

    public AfkManager(AtlasMC plugin) {
        this.plugin = plugin;
        this.worldName = plugin.getConfig().getString("afk.world", "atlas_afk");
        createWorld();
    }

    private void createWorld() {
        WorldCreator wc = new WorldCreator(worldName);
        wc.generator(new VoidGenerator());
        world = wc.createWorld();
        if (world == null) {
            plugin.getLogger().severe("AFK dünyası oluşturulamadı!");
            return;
        }
        setRule("doDaylightCycle", "advance_time");
        setRule("doMobSpawning", "spawn_mobs");
        setRule("doWeatherCycle", "advance_weather");
        world.setTime(18000);
        world.setSpawnLocation(0, 101, 0);

        // Kapkara oda: 7x7x5, siyah beton
        if (world.getBlockAt(0, 100, 0).getType() != Material.BLACK_CONCRETE) {
            for (int x = -3; x <= 3; x++)
                for (int y = 100; y <= 105; y++)
                    for (int z = -3; z <= 3; z++) {
                        boolean wall = Math.abs(x) == 3 || Math.abs(z) == 3 || y == 100 || y == 105;
                        world.getBlockAt(x, y, z).setType(wall ? Material.BLACK_CONCRETE : Material.AIR, false);
                    }
        }
    }

    @SuppressWarnings("unchecked")
    private void setRule(String... names) {
        for (String n : names) {
            try {
                GameRule<?> r = GameRule.getByName(n);
                if (r != null && Boolean.class.equals(r.getType())) {
                    world.setGameRule((GameRule<Boolean>) r, false);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isAfk(Player p) {
        return world != null && p.getWorld().getName().equals(worldName);
    }

    private Location spawn() {
        return new Location(world, 0.5, 101, 0.5);
    }

    private void enter(Player p) {
        back.put(p.getUniqueId(), p.getLocation());
        p.teleport(spawn());
        if (plugin.getConfig().getBoolean("afk.blindness", true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false, false));
        }
        plugin.msg(p, "&7AFK odasındasın. Dakikada &d" + plugin.getConfig().getInt("crystal.afk-per-minute", 2)
                + " kristal &7kazanıyorsun. Çıkmak için &e/afk");
    }

    private void leave(Player p, boolean notify) {
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        Location l = back.remove(p.getUniqueId());
        if (l == null) l = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.teleport(l);
        if (notify) plugin.msg(p, "&aAFK odasından çıktın.");
    }

    public void returnAll() {
        for (Player p : Bukkit.getOnlinePlayers()) if (isAfk(p)) leave(p, false);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Sadece oyuncular kullanabilir.");
            return true;
        }
        if (world == null) {
            plugin.msg(p, "&cAFK dünyası aktif değil.");
            return true;
        }
        if (isAfk(p)) leave(p, true);
        else enter(p);
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (isAfk(e.getPlayer())) leave(e.getPlayer(), false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (isAfk(p) && !back.containsKey(p.getUniqueId())) {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isAfk(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (isAfk(e.getPlayer()) && !e.getPlayer().hasPermission("atlasmc.admin")) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isAfk(e.getPlayer()) && !e.getPlayer().hasPermission("atlasmc.admin")) e.setCancelled(true);
    }
}
