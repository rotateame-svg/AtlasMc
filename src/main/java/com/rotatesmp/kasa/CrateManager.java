package com.rotatesmp.kasa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CrateManager {

    public static final String ADMIN_PERM = "rotatekasa.admin";
    public static final MiniMessage MM = MiniMessage.miniMessage();
    public static final Component PREFIX = MM.deserialize("<dark_gray>[<gold>Kasa<dark_gray>] ");

    private final KasaPlugin plugin;
    private final NamespacedKey keyTag;
    private final File file;
    private final Set<String> crates = new HashSet<>();
    private final Map<UUID, CrateRoll> rolls = new LinkedHashMap<>();

    public CrateManager(KasaPlugin plugin) {
        this.plugin = plugin;
        this.keyTag = new NamespacedKey(plugin, "kasa_anahtar");
        this.file = new File(plugin.getDataFolder(), "crates.yml");
    }

    public KasaPlugin plugin() {
        return plugin;
    }

    // ---------- mesaj ----------

    public static void msg(CommandSender to, String mini, TagResolver... resolvers) {
        to.sendMessage(PREFIX.append(MM.deserialize(mini, resolvers)));
    }

    // ---------- kasa kaydi ----------

    public static String keyOf(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    public boolean isCrate(Block b) {
        if (b == null || !Tag.SHULKER_BOXES.isTagged(b.getType())) {
            return false;
        }
        return crates.contains(keyOf(b.getLocation()));
    }

    public void register(Block b) {
        crates.add(keyOf(b.getLocation()));
        save();
    }

    public void unregister(Block b) {
        crates.remove(keyOf(b.getLocation()));
        save();
    }

    public int count() {
        return crates.size();
    }

    public void load() {
        crates.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        crates.addAll(yml.getStringList("crates"));
    }

    public void save() {
        try {
            plugin.getDataFolder().mkdirs();
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("crates", new ArrayList<>(crates));
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("crates.yml kaydedilemedi: " + e.getMessage());
        }
    }

    // ---------- anahtar ----------

    public ItemStack createKey(int amount) {
        ItemStack item = new ItemStack(Material.TRIPWIRE_HOOK, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize("<!italic><gold><bold>Kasa Anahtarı"));
        meta.lore(List.of(
                MM.deserialize("<!italic><gray>Spawn'daki kasayı açmak için"),
                MM.deserialize("<!italic><gray>kasaya sağ tıkla.")
        ));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(keyTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isKey(ItemStack item) {
        if (item == null || item.getType() != Material.TRIPWIRE_HOOK || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(keyTag, PersistentDataType.BYTE);
    }

    // ---------- animasyon takibi ----------

    public boolean isRolling(Player p) {
        return rolls.containsKey(p.getUniqueId());
    }

    public void startRoll(Player p, List<ItemStack> pool) {
        CrateRoll roll = new CrateRoll(this, p, pool);
        rolls.put(p.getUniqueId(), roll);
        roll.start();
    }

    void removeRoll(UUID id) {
        rolls.remove(id);
    }

    public CrateRoll getRoll(UUID id) {
        return rolls.get(id);
    }

    public void finishAllRolls() {
        for (CrateRoll r : new ArrayList<>(rolls.values())) {
            r.finishNow();
        }
        rolls.clear();
    }
}
