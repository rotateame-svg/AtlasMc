package net.atlasmc;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static net.atlasmc.AtlasMC.color;

/**
 * Depolu spawner sistemi:
 *  - Mob spawn ETMEZ, belirli aralıkla XP + ürün biriktirir
 *  - Sadece İpeksi Dokunuş ile kırılır
 *  - Sağ tık: depo menüsü
 */
public class SpawnerManager implements Listener {

    static class Data {
        String type, world;
        int x, y, z, xp, progress;
        LinkedHashMap<Material, Integer> storage = new LinkedHashMap<>();
    }

    private static class Holder implements InventoryHolder {
        final String key;
        Inventory inv;
        Holder(String key) { this.key = key; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final AtlasMC plugin;
    private final Map<String, Data> map = new HashMap<>();
    private final NamespacedKey typeKey;
    private final File file;

    public SpawnerManager(AtlasMC plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "spawner_type");
        this.file = new File(plugin.getDataFolder(), "spawners.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ---------- Yardımcılar ----------
    private ConfigurationSection cfg(String type) {
        return plugin.getConfig().getConfigurationSection("spawners." + type);
    }

    public String displayName(String type) {
        ConfigurationSection s = cfg(type);
        return s == null ? AtlasMC.pretty(type) : s.getString("name", type);
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public ItemStack createItem(String type) {
        ItemStack it = new ItemStack(Material.SPAWNER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(color("&e&l" + displayName(type) + " Spawner"));
        m.setLore(List.of(
                color("&7Mob spawn etmez, &aXP &7ve &aürün &7biriktirir."),
                color("&7Yere koy, &eSağ Tık &7ile depoyu aç."),
                color("&cSadece İpeksi Dokunuş ile kırılır.")));
        m.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        it.setItemMeta(m);
        return it;
    }

    private Data register(Block b, String type) {
        Data d = new Data();
        d.type = type;
        d.world = b.getWorld().getName();
        d.x = b.getX(); d.y = b.getY(); d.z = b.getZ();
        ConfigurationSection drops = cfg(type) == null ? null : cfg(type).getConfigurationSection("drops");
        if (drops != null) {
            for (String k : drops.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m != null) d.storage.put(m, 0);
            }
        }
        map.put(key(b), d);
        return d;
    }

    private int roll(String range) {
        String[] p = range.split("-");
        int a = Integer.parseInt(p[0].trim());
        int b = p.length > 1 ? Integer.parseInt(p[1].trim()) : a;
        if (b < a) b = a;
        return a + ThreadLocalRandom.current().nextInt(b - a + 1);
    }

    // ---------- Üretim döngüsü ----------
    private void tick() {
        int interval = plugin.getConfig().getInt("spawner.interval-seconds", 10);
        int max = plugin.getConfig().getInt("spawner.max-storage", 10000);

        Iterator<Data> it = map.values().iterator();
        while (it.hasNext()) {
            Data d = it.next();
            World w = Bukkit.getWorld(d.world);
            if (w == null || !w.isChunkLoaded(d.x >> 4, d.z >> 4)) continue;
            if (w.getBlockAt(d.x, d.y, d.z).getType() != Material.SPAWNER) {
                it.remove();
                continue;
            }
            if (++d.progress < interval) continue;
            d.progress = 0;

            ConfigurationSection s = cfg(d.type);
            if (s == null) continue;
            d.xp = Math.min(1_000_000, d.xp + s.getInt("xp", 1));
            ConfigurationSection drops = s.getConfigurationSection("drops");
            if (drops == null) continue;
            for (String k : drops.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) continue;
                int add = roll(drops.getString(k, "1"));
                d.storage.put(m, Math.min(max, d.storage.getOrDefault(m, 0) + add));
            }
        }

        // Açık menüleri yenile
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h) {
                Data d = map.get(h.key);
                if (d != null) render(h, d);
            }
        }
    }

    // ---------- Menü ----------
    private void open(Player p, Block b, Data d) {
        Holder h = new Holder(key(b));
        h.inv = Bukkit.createInventory(h, 27, color("&8" + displayName(d.type) + " Spawner"));
        render(h, d);
        p.openInventory(h.inv);
    }

    private void render(Holder h, Data d) {
        Inventory inv = h.inv;
        inv.clear();

        ItemStack info = new ItemStack(Material.SPAWNER);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName(color("&e&l" + displayName(d.type) + " Spawner"));
        im.setLore(List.of(
                color("&7Her &f" + plugin.getConfig().getInt("spawner.interval-seconds", 10) + " sn &7de bir üretir."),
                color("&7Sonraki üretim: &f" + (plugin.getConfig().getInt("spawner.interval-seconds", 10) - d.progress) + " sn")));
        info.setItemMeta(im);
        inv.setItem(4, info);

        int slot = 10;
        for (Map.Entry<Material, Integer> e : d.storage.entrySet()) {
            if (slot > 16) break;
            ItemStack it = new ItemStack(e.getKey());
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(color("&f" + AtlasMC.pretty(e.getKey().name())));
            m.setLore(List.of(
                    color("&7Depoda: &e" + e.getValue()),
                    "",
                    color("&aTık: &f1 stack al"),
                    color("&aShift+Tık: &fhepsini al")));
            it.setItemMeta(m);
            inv.setItem(slot++, it);
        }

        ItemStack xp = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta xm = xp.getItemMeta();
        xm.setDisplayName(color("&a&lXP Topla"));
        xm.setLore(List.of(color("&7Biriken XP: &a" + d.xp), "", color("&eTopla!")));
        xp.setItemMeta(xm);
        inv.setItem(22, xp);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        Player p = (Player) e.getWhoClicked();
        Data d = map.get(h.key);
        if (d == null) { p.closeInventory(); return; }

        int s = e.getSlot();
        if (s == 22) {
            if (d.xp > 0) {
                p.giveExp(d.xp);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                plugin.msg(p, "&a" + d.xp + " XP &7topladın.");
                d.xp = 0;
            }
        } else if (s >= 10 && s <= 16) {
            List<Material> mats = new ArrayList<>(d.storage.keySet());
            int idx = s - 10;
            if (idx < mats.size()) take(p, d, mats.get(idx), e.isShiftClick());
        }
        render(h, d);
    }

    private void take(Player p, Data d, Material mat, boolean all) {
        int have = d.storage.getOrDefault(mat, 0);
        if (have <= 0) return;
        int want = all ? have : Math.min(have, mat.getMaxStackSize());
        int given = 0;
        while (want > 0) {
            int n = Math.min(want, mat.getMaxStackSize());
            Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(mat, n));
            int notGiven = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            given += n - notGiven;
            want -= n;
            if (notGiven > 0) break;
        }
        d.storage.put(mat, have - given);
        if (given == 0) plugin.msg(p, "&cEnvanterin dolu!");
    }

    // ---------- Olaylar ----------
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.SPAWNER) return;
        ItemMeta m = e.getItemInHand().getItemMeta();
        if (m == null) return;
        String type = m.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (type == null) return;

        Block b = e.getBlockPlaced();
        CreatureSpawner cs = (CreatureSpawner) b.getState();
        try {
            cs.setSpawnedType(EntityType.valueOf(type));
        } catch (IllegalArgumentException ignored) {
        }
        cs.update();
        register(b, type);
        plugin.msg(e.getPlayer(), "&a" + displayName(type) + " Spawner &7yerleştirildi. Depo için &eSağ Tık&7.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();

        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool.getType() == Material.AIR || !tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            e.setCancelled(true);
            plugin.msg(p, "&cSpawner'ı sadece &lİpeksi Dokunuş &r&cbüyülü kazma ile kırabilirsin!");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        e.setExpToDrop(0);
        e.setDropItems(false);

        Data d = map.remove(key(b));
        String type;
        if (d != null) {
            type = d.type;
        } else {
            EntityType et = ((CreatureSpawner) b.getState()).getSpawnedType();
            type = et == null ? "PIG" : et.name();
        }

        Location loc = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().dropItemNaturally(loc, createItem(type));

        if (d != null) {
            if (d.xp > 0) p.giveExp(d.xp);
            for (Map.Entry<Material, Integer> en : d.storage.entrySet()) {
                int left = en.getValue();
                while (left > 0) {
                    int n = Math.min(left, en.getKey().getMaxStackSize());
                    b.getWorld().dropItemNaturally(loc, new ItemStack(en.getKey(), n));
                    left -= n;
                }
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        if (e.getPlayer().isSneaking()) return;

        e.setCancelled(true);
        Data d = map.get(key(b));
        if (d == null) {
            EntityType et = ((CreatureSpawner) b.getState()).getSpawnedType();
            d = register(b, et == null ? "PIG" : et.name());
        }
        open(e.getPlayer(), b, d);
    }

    @EventHandler
    public void onSpawn(SpawnerSpawnEvent e) {
        if (map.containsKey(key(e.getSpawner().getBlock()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.SPAWNER);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.SPAWNER);
    }

    // ---------- Kayıt ----------
    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Data d : map.values()) {
            String p = "spawners." + (i++) + ".";
            y.set(p + "type", d.type);
            y.set(p + "world", d.world);
            y.set(p + "x", d.x);
            y.set(p + "y", d.y);
            y.set(p + "z", d.z);
            y.set(p + "xp", d.xp);
            for (Map.Entry<Material, Integer> e : d.storage.entrySet())
                y.set(p + "storage." + e.getKey().name(), e.getValue());
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("spawners.yml kaydedilemedi: " + ex.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("spawners");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            if (s == null) continue;
            Data d = new Data();
            d.type = s.getString("type");
            d.world = s.getString("world");
            d.x = s.getInt("x"); d.y = s.getInt("y"); d.z = s.getInt("z");
            d.xp = s.getInt("xp");
            ConfigurationSection st = s.getConfigurationSection("storage");
            if (st != null) {
                for (String mk : st.getKeys(false)) {
                    Material m = Material.matchMaterial(mk);
                    if (m != null) d.storage.put(m, st.getInt(mk));
                }
            }
            map.put(d.world + ";" + d.x + ";" + d.y + ";" + d.z, d);
        }
    }
}
