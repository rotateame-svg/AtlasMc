package net.atlasmc;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AtlasMC extends JavaPlugin {

    public static final String PREFIX = "&b&lAtlasMC &8» ";
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");

    private Economy economy;
    private LuckPermsHook luckPerms;
    private final Map<UUID, Integer> crystals = new HashMap<>();
    private File dataFile;

    private AfkManager afk;
    private SpawnerManager spawners;
    private ShopManager shop;
    private MarketManager market;
    private BoardManager board;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            luckPerms = new LuckPermsHook();
        } else {
            getLogger().warning("LuckPerms bulunamadı, herkes 'Oyuncu' rankında görünecek.");
        }

        afk = new AfkManager(this);
        spawners = new SpawnerManager(this);
        shop = new ShopManager(this);
        market = new MarketManager(this);
        board = new BoardManager(this);

        getServer().getPluginManager().registerEvents(afk, this);
        getServer().getPluginManager().registerEvents(spawners, this);
        getServer().getPluginManager().registerEvents(shop, this);
        getServer().getPluginManager().registerEvents(market, this);
        getServer().getPluginManager().registerEvents(board, this);

        getCommand("afk").setExecutor(afk);
        getCommand("shop").setExecutor(shop);
        getCommand("kristal").setExecutor(market);
        getCommand("kristalmarket").setExecutor(market);

        // Dakikada bir kristal (AFK odasında 2, normalde 1)
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                int amt = afk.isAfk(p)
                        ? getConfig().getInt("crystal.afk-per-minute", 2)
                        : getConfig().getInt("crystal.per-minute", 1);
                addCrystals(p.getUniqueId(), amt);
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        new TextComponent(color("&d+" + amt + " Kristal &7(Toplam: &d" + getCrystals(p.getUniqueId()) + "&7)")));
            }
        }, 1200L, 1200L);

        // 5 dakikada bir kaydet
        getServer().getScheduler().runTaskTimer(this, () -> {
            saveData();
            spawners.save();
        }, 6000L, 6000L);

        getLogger().info("AtlasMC aktif!");
    }

    @Override
    public void onDisable() {
        if (afk != null) afk.returnAll();
        saveData();
        if (spawners != null) spawners.save();
    }

    // ---------- Ekonomi / Rank ----------
    public Economy getEconomy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    public double getMoney(Player p) {
        Economy e = getEconomy();
        return e == null ? 0 : e.getBalance(p);
    }

    public String getRank(Player p) {
        String group = "default";
        if (luckPerms != null) {
            try {
                group = luckPerms.primaryGroup(p);
            } catch (Throwable ignored) {
            }
        }
        ConfigurationSection ranks = getConfig().getConfigurationSection("ranks");
        String v = ranks == null ? null : ranks.getString(group.toLowerCase());
        if (v != null) return color(v);
        if (group.equalsIgnoreCase("default")) return color("&7Oyuncu");
        return color("&f" + group.substring(0, 1).toUpperCase() + group.substring(1));
    }

    // ---------- Kristal ----------
    public int getCrystals(UUID id) {
        return crystals.getOrDefault(id, 0);
    }

    public void addCrystals(UUID id, int amt) {
        crystals.put(id, Math.max(0, getCrystals(id) + amt));
    }

    public boolean takeCrystals(UUID id, int amt) {
        if (getCrystals(id) < amt) return false;
        crystals.put(id, getCrystals(id) - amt);
        return true;
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("crystals");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                crystals.put(UUID.fromString(k), s.getInt(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        crystals.forEach((u, v) -> y.set("crystals." + u, v));
        try {
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("data.yml kaydedilemedi: " + e.getMessage());
        }
    }

    // ---------- İstatistikler (sürüm adı değişikliklerine dayanıklı) ----------
    private static Statistic stat(String... names) {
        for (String n : names) {
            try {
                return Statistic.valueOf(n);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static final Statistic PLAY = stat("PLAY_ONE_MINUTE", "PLAY_TIME", "TOTAL_WORLD_TIME");
    private static final Statistic KILLS = stat("PLAYER_KILLS");
    private static final Statistic DEATHS = stat("DEATHS");

    private int safeStat(Player p, Statistic s) {
        try {
            return s == null ? 0 : p.getStatistic(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    public int playTicks(Player p) { return safeStat(p, PLAY); }
    public int kills(Player p) { return safeStat(p, KILLS); }
    public int deaths(Player p) { return safeStat(p, DEATHS); }

    // ---------- Yardımcılar ----------
    public AfkManager afk() { return afk; }
    public SpawnerManager spawners() { return spawners; }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public void msg(CommandSender s, String m) {
        s.sendMessage(color(PREFIX + m));
    }

    public static String money(double v) {
        return MONEY.format(v);
    }

    public static String time(long ticks) {
        long sec = ticks / 20;
        return (sec / 3600) + "sa " + ((sec % 3600) / 60) + "dk";
    }

    public static String pretty(String s) {
        return s.toLowerCase().replace('_', ' ');
    }
}
