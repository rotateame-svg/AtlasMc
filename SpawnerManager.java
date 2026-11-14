package net.atlasmc;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static net.atlasmc.AtlasMC.color;

/**
 * Stacklenebilir, depolu spawner sistemi.
 *  - Mob çıkarmaz; belirli aralıkla ürün + XP biriktirir
 *  - Aynı türden spawner'a spawner ile sağ tıklayınca stacklenir (her stack daha fazla üretim)
 *  - Menü: Depo / Bilgi (sat & XP topla) / XP
 *  - Sadece İpeksi Dokunuş ile kırılır
 */
public class SpawnerManager implements Listener {

    static class Data {
        String type, world;
        int x, y, z, progress, stack = 1, sort = 0;
        long xp;
        final LinkedHashMap<Material, Long> storage = new LinkedHashMap<>();
        final Set<Material> blocked = new HashSet<>();
    }

    private static class MenuHolder implements InventoryHolder {
        final String key;
        Inventory inv;
        MenuHolder(String key) { this.key = key; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static class StoreHolder implements InventoryHolder {
        final String key;
        int page = 0, pages = 1;
        Inventory inv;
        final Material[] mats = new Material[45];
        final long[] amts = new long[45];
        StoreHolder(String key) { this.key = key; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static class FilterHolder implements InventoryHolder {
        final String key;
        Inventory inv;
        final List<Material> mats = new ArrayList<>();
        FilterHolder(String key) { this.key = key; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final Map<Material, Double> DEFAULT_SELL = new HashMap<>();
    static {
        DEFAULT_SELL.put(Material.ROTTEN_FLESH, 2.0);
        DEFAULT_SELL.put(Material.BONE, 3.0);
        DEFAULT_SELL.put(Material.ARROW, 2.0);
        DEFAULT_SELL.put(Material.BEEF, 5.0);
        DEFAULT_SELL.put(Material.LEATHER, 4.0);
        DEFAULT_SELL.put(Material.PORKCHOP, 5.0);
        DEFAULT_SELL.put(Material.STRING, 3.0);
        DEFAULT_SELL.put(Material.SPIDER_EYE, 4.0);
        DEFAULT_SELL.put(Material.GUNPOWDER, 6.0);
        DEFAULT_SELL.put(Material.BLAZE_ROD, 15.0);
        DEFAULT_SELL.put(Material.IRON_INGOT, 10.0);
        DEFAULT_SELL.put(Material.POPPY, 1.0);
    }

    private static final String[] SORT_NAMES = {"Miktar", "İsim", "Değer"};

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

    // ---------- Ayarlar / hesaplar ----------
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

    private int interval() { return Math.max(1, plugin.getConfig().getInt("spawner.interval-seconds", 10)); }
    private int maxStack() { return plugin.getConfig().getInt("spawner.max-stack", 5000); }

    /** 1 stack = x1, her ek stack +bonus: 1 ->5, 2 ->8 (bonus 0.6) */
    private double mult(Data d) {
        return 1.0 + (d.stack - 1) * plugin.getConfig().getDouble("spawner.stack-bonus", 0.6);
    }

    private long capItems(Data d) {
        return (long) plugin.getConfig().getInt("spawner.storage-per-stack", 2000) * d.stack;
    }

    private long capXp(Data d) {
        return (long) plugin.getConfig().getInt("spawner.xp-per-stack", 100000) * d.stack;
    }

    private double price(Material m) {
        return plugin.getConfig().getDouble("sell." + m.name(), DEFAULT_SELL.getOrDefault(m, 1.0));
    }

    private long total(Data d) {
        long t = 0;
        for (long v : d.storage.values()) t += v;
        return t;
    }

    private double value(Data d) {
        double v = 0;
        for (Map.Entry<Material, Long> e : d.storage.entrySet()) v += e.getValue() * price(e.getKey());
        return v;
    }

    private int[] parseRange(String range) {
        String[] p = range.split("-");
        int a = Integer.parseInt(p[0].trim());
        int b = p.length > 1 ? Integer.parseInt(p[1].trim()) : a;
        return new int[]{a, Math.max(a, b)};
    }

    /** Bir üretimde toplam min-max ürün (filtre ve stack dahil). */
    private int[] spawnRange(Data d) {
        ConfigurationSection s = cfg(d.type);
        ConfigurationSection drops = s == null ? null : s.getConfigurationSection("drops");
        int lo = 0, hi = 0;
        if (drops == null) return new int[]{0, 0};
        double m = mult(d);
        for (String k : drops.getKeys(false)) {
            Material mat = Material.matchMaterial(k);
            if (mat == null || d.blocked.contains(mat)) continue;
            int[] r = parseRange(drops.getString(k, "1"));
            lo += (int) Math.round(r[0] * m);
            hi += (int) Math.round(r[1] * m);
        }
        return new int[]{lo, hi};
    }

    private static String trim1(double x) {
        String s = String.format(Locale.US, "%.1f", x);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    static String shortNum(double v) {
        if (v >= 1_000_000_000d) return trim1(v / 1_000_000_000d) + "B";
        if (v >= 1_000_000d) return trim1(v / 1_000_000d) + "M";
        if (v >= 1_000d) return trim1(v / 1_000d) + "K";
        return String.valueOf((long) v);
    }

    private static String pct(double part, double whole) {
        if (whole <= 0 || part <= 0) return "0%";
        double p = part * 100.0 / whole;
        if (p < 1) return "<1%";
        return (int) Math.min(100, p) + "%";
    }

    // ---------- Spawner eşyası ----------
    public ItemStack createItem(String type) {
        ItemStack it = new ItemStack(Material.SPAWNER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(color("&e&l" + displayName(type) + " Spawner"));
        m.setLore(List.of(
                color("&7Mob spawn etmez, &aXP &7ve &aürün &7biriktirir."),
                color("&7Aynı türdeki spawner'a &eSağ Tık &7ile &astacklenir&7."),
                color("&cSadece İpeksi Dokunuş ile kırılır.")));
        m.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        it.setItemMeta(m);
        return it;
    }

    private String itemType(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    private Data register(Block b, String type) {
        Data d = new Data();
        d.type = type;
        d.world = b.getWorld().getName();
        d.x = b.getX();
        d.y = b.getY();
        d.z = b.getZ();
        initStorage(d);
        map.put(key(b), d);
        return d;
    }

    private void initStorage(Data d) {
        ConfigurationSection s = cfg(d.type);
        ConfigurationSection drops = s == null ? null : s.getConfigurationSection("drops");
        if (drops == null) return;
        for (String k : drops.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null) d.storage.putIfAbsent(m, 0L);
        }
    }

    // ---------- Üretim döngüsü ----------
    private void tick() {
        int interval = interval();
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
            produce(d);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder top = p.getOpenInventory().getTopInventory().getHolder();
            if (top instanceof MenuHolder) {
                MenuHolder h = (MenuHolder) top;
                Data d = map.get(h.key);
                if (d != null) renderMenu(h, d);
            } else if (top instanceof StoreHolder) {
                StoreHolder h = (StoreHolder) top;
                Data d = map.get(h.key);
                if (d != null) renderStore(h, d);
            }
        }
    }

    private void produce(Data d) {
        ConfigurationSection s = cfg(d.type);
        if (s == null) return;
        double m = mult(d);
        ThreadLocalRandom r = ThreadLocalRandom.current();

        d.xp = Math.min(capXp(d), d.xp + Math.round(s.getInt("xp", 1) * m));

        ConfigurationSection drops = s.getConfigurationSection("drops");
        if (drops == null) return;
        long cap = capItems(d);
        long used = total(d);
        for (String k : drops.getKeys(false)) {
            Material mat = Material.matchMaterial(k);
            if (mat == null || d.blocked.contains(mat)) continue;
            int[] rg = parseRange(drops.getString(k, "1"));
            int lo = (int) Math.round(rg[0] * m);
            int hi = (int) Math.round(rg[1] * m);
            long add = lo + r.nextInt(Math.max(1, hi - lo + 1));
            add = Math.min(add, Math.max(0, cap - used));
            if (add <= 0) continue;
            d.storage.merge(mat, add, Long::sum);
            used += add;
        }
    }

    // ---------- Menü ----------
    private ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(color(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(color(s));
        meta.setLore(l);
        it.setItemMeta(meta);
        return it;
    }

    private void openMenu(Player p, Data d, String key) {
        MenuHolder h = new MenuHolder(key);
        h.inv = Bukkit.createInventory(h, 27, color("&8" + d.stack + "x " + displayName(d.type) + " Spawner"));
        renderMenu(h, d);
        p.openInventory(h.inv);
    }

    private void renderMenu(MenuHolder h, Data d) {
        Inventory inv = h.inv;
        inv.clear();

        inv.setItem(11, icon(Material.CHEST, "&6&lSpawner Deposu",
                "&9» &7Bilgi:",
                "&8 ▪ &7Üretilen eşyaları görüntüle, al veya sat.",
                "",
                "&b▸ &7Açmak için tıkla!"));

        Material egg = Material.matchMaterial(d.type + "_SPAWN_EGG");
        int[] rg = spawnRange(d);
        int left = interval() - d.progress;
        String next = String.format("%02d:%02d", left / 60, left % 60);
        inv.setItem(13, icon(egg == null ? Material.SPAWNER : egg, "&e&lSpawner &bBilgisi",
                "&9» &bİstatistikler:",
                "&8 ▪ &7Stack: &e" + d.stack,
                "&8 ▪ &7Üretim: &e" + rg[0] + "-" + rg[1] + " &7adet",
                "&8 ▪ &7Gecikme: &e" + interval() + " &7saniye",
                "&8 ▪ &7Çarpan: &ax" + trim1(mult(d)),
                "",
                "&b★ &bSonraki Üretim: &f" + next,
                "&8 ▪ &7XP: &e" + pct(d.xp, capXp(d)),
                "&8 ▪ &7Depo: &e" + pct(total(d), capItems(d)),
                "&8 ▪ &7Satış Değeri: &a$" + shortNum(value(d)),
                "",
                "&b▸ &7Sol Tık: &fSatış yap & XP topla"));

        inv.setItem(15, icon(Material.EXPERIENCE_BOTTLE, "&a&lXP &bTopla",
                "&9» &7Bilgi:",
                "&8 ▪ &7Biriken XP'yi topla veya",
                "&8   &7Tamir basılı eşyalarını onar.",
                "",
                "&b★ &bKapasite: &e" + pct(d.xp, capXp(d)),
                "&8 ▪ &7Biriken XP: &a" + shortNum(d.xp) + "&7/&a" + shortNum(capXp(d)),
                "",
                "&b▸ &7Sol Tık: &fXP topla",
                "&b▸ &7Sağ Tık: &fEşyalarını onar"));
    }

    private void openStore(Player p, Data d, String key, int page) {
        StoreHolder h = new StoreHolder(key);
        h.page = page;
        h.inv = Bukkit.createInventory(h, 54, color("&8Spawner Deposu"));
        renderStore(h, d);
        p.openInventory(h.inv);
    }

    private List<Material> sorted(Data d) {
        List<Material> order = new ArrayList<>();
        for (Map.Entry<Material, Long> e : d.storage.entrySet()) if (e.getValue() > 0) order.add(e.getKey());
        if (d.sort == 0) order.sort(Comparator.comparingLong((Material m) -> d.storage.get(m)).reversed());
        else if (d.sort == 1) order.sort(Comparator.comparing(Material::name));
        else order.sort(Comparator.comparingDouble((Material m) -> d.storage.get(m) * price(m)).reversed());
        return order;
    }

    private void renderStore(StoreHolder h, Data d) {
        Inventory inv = h.inv;
        inv.clear();
        List<Material> order = sorted(d);

        long totalSlots = 0;
        for (Material m : order) {
            int max = m.getMaxStackSize();
            totalSlots += (d.storage.get(m) + max - 1) / max;
        }
        h.pages = (int) Math.max(1, (totalSlots + 44) / 45);
        if (h.page >= h.pages) h.page = h.pages - 1;
        if (h.page < 0) h.page = 0;

        for (int i = 0; i < 45; i++) {
            h.mats[i] = null;
            h.amts[i] = 0;
        }
        long pageStart = (long) h.page * 45, pageEnd = pageStart + 45, idx = 0;
        for (Material m : order) {
            long left = d.storage.get(m);
            int max = m.getMaxStackSize();
            long stacks = (left + max - 1) / max;
            for (long s = Math.max(0, pageStart - idx); s < stacks; s++) {
                long gi = idx + s;
                if (gi >= pageEnd) break;
                int slot = (int) (gi - pageStart);
                long n = Math.min(left - s * max, max);
                h.mats[slot] = m;
                h.amts[slot] = n;
                ItemStack it = new ItemStack(m, (int) n);
                ItemMeta meta = it.getItemMeta();
                meta.setLore(List.of(color("&7Depoda toplam: &e" + shortNum(left)),
                        color("&b▸ &7Tıkla: &fenvanterine al")));
                it.setItemMeta(meta);
                inv.setItem(slot, it);
            }
            idx += stacks;
            if (idx >= pageEnd) break;
        }

        List<String> sortLore = new ArrayList<>();
        sortLore.add("");
        for (int i = 0; i < SORT_NAMES.length; i++)
            sortLore.add((i == d.sort ? "&b ▪ &b" : "&8 ▪ &7") + SORT_NAMES[i]);
        sortLore.add("");
        sortLore.add("&b▸ &7Sıralamak için tıkla!");

        if (h.page > 0) inv.setItem(45, icon(Material.ARROW, "&eÖnceki Sayfa"));
        inv.setItem(47, icon(Material.CAULDRON, "&fEşyaları &bSırala", sortLore.toArray(new String[0])));
        inv.setItem(48, icon(Material.HOPPER, "&fDüşenleri &bFiltrele",
                "", "&9» &7Bilgi:",
                "&8 ▪ &7Bu spawner için izin verilen ve",
                "&8   &7engellenen eşyaları ayarla.",
                "", "&b▸ &7Filtreleri ayarlamak için tıkla!"));
        inv.setItem(49, icon(Material.GOLD_INGOT, "&fEşyaları &aSat",
                "", "&9» &7Bilgi:",
                "&8 ▪ &7Depodaki tüm eşyaları satıp",
                "&8   &7paraya çevirirsin.",
                "&8 ▪ &7Toplam: &a$" + shortNum(value(d)),
                "", "&a▸ &aSatmak için tıkla!"));
        inv.setItem(50, icon(Material.BUNDLE, "&fHepsini &bAl",
                "", "&9» &7Bilgi:",
                "&8 ▪ &7Bu sayfadaki tüm eşyaları",
                "&8   &7envanterine alırsın.",
                "", "&b▸ &7Almak için tıkla!"));
        inv.setItem(51, icon(Material.DROPPER, "&fHepsini &cYere At",
                "", "&c⚠ Uyarı:",
                "&8 ▪ &7Bu sayfadaki tüm eşyaları",
                "&8   &7yere fırlatırsın.",
                "", "&c▸ &cYere atmak için tıkla!"));
        inv.setItem(52, icon(Material.BARRIER, "&cGeri Dön"));
        if (h.page < h.pages - 1) inv.setItem(53, icon(Material.ARROW, "&eSonraki Sayfa"));
    }

    private void openFilter(Player p, Data d, String key) {
        FilterHolder h = new FilterHolder(key);
        h.inv = Bukkit.createInventory(h, 27, color("&8Düşen Filtresi"));
        ConfigurationSection s = cfg(d.type);
        ConfigurationSection drops = s == null ? null : s.getConfigurationSection("drops");
        if (drops != null)
            for (String k : drops.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m != null && h.mats.size() < 7) h.mats.add(m);
            }
        renderFilter(h, d);
        p.openInventory(h.inv);
    }

    private void renderFilter(FilterHolder h, Data d) {
        h.inv.clear();
        for (int i = 0; i < h.mats.size(); i++) {
            Material m = h.mats.get(i);
            boolean blocked = d.blocked.contains(m);
            h.inv.setItem(10 + i, icon(m, "&f" + AtlasMC.pretty(m.name()),
                    "",
                    blocked ? "&c✘ Engelli &7(üretilmiyor)" : "&a✔ İzinli &7(üretiliyor)",
                    "",
                    "&b▸ &7Değiştirmek için tıkla!"));
        }
        h.inv.setItem(22, icon(Material.BARRIER, "&cGeri Dön"));
    }

    // ---------- Tıklamalar ----------
    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof MenuHolder || h instanceof StoreHolder || h instanceof FilterHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof MenuHolder || holder instanceof StoreHolder || holder instanceof FilterHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        Player p = (Player) e.getWhoClicked();
        int s = e.getSlot();

        if (holder instanceof MenuHolder) {
            MenuHolder h = (MenuHolder) holder;
            Data d = map.get(h.key);
            if (d == null) { p.closeInventory(); return; }
            if (s == 11) later(() -> openStore(p, d, h.key, 0));
            else if (s == 13) { sellAll(p, d); collectXp(p, d); renderMenu(h, d); }
            else if (s == 15) {
                if (e.getClick().isRightClick()) repair(p, d);
                else collectXp(p, d);
                renderMenu(h, d);
            }
        } else if (holder instanceof StoreHolder) {
            StoreHolder h = (StoreHolder) holder;
            Data d = map.get(h.key);
            if (d == null) { p.closeInventory(); return; }
            if (s >= 0 && s < 45) {
                if (h.mats[s] != null) take(p, d, h.mats[s], h.amts[s]);
            } else if (s == 45 && h.page > 0) h.page--;
            else if (s == 53 && h.page < h.pages - 1) h.page++;
            else if (s == 47) d.sort = (d.sort + 1) % SORT_NAMES.length;
            else if (s == 48) { later(() -> openFilter(p, d, h.key)); return; }
            else if (s == 49) sellAll(p, d);
            else if (s == 50) takePage(p, d, h);
            else if (s == 51) dropPage(p, d, h);
            else if (s == 52) { later(() -> openMenu(p, d, h.key)); return; }
            renderStore(h, d);
        } else {
            FilterHolder h = (FilterHolder) holder;
            Data d = map.get(h.key);
            if (d == null) { p.closeInventory(); return; }
            if (s >= 10 && s < 10 + h.mats.size()) {
                Material m = h.mats.get(s - 10);
                if (!d.blocked.remove(m)) d.blocked.add(m);
                renderFilter(h, d);
            } else if (s == 22) {
                later(() -> openStore(p, d, h.key, 0));
            }
        }
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    // ---------- Eylemler ----------
    private void take(Player p, Data d, Material mat, long amt) {
        long have = d.storage.getOrDefault(mat, 0L);
        int want = (int) Math.min(amt, have);
        if (want <= 0) return;
        int given = Items.give(p, mat, want);
        d.storage.put(mat, have - given);
        if (given < want) plugin.msg(p, "&cEnvanterin dolu!");
    }

    private void takePage(Player p, Data d, StoreHolder h) {
        for (int i = 0; i < 45; i++) {
            if (h.mats[i] == null) continue;
            long have = d.storage.getOrDefault(h.mats[i], 0L);
            int want = (int) Math.min(h.amts[i], have);
            if (want <= 0) continue;
            int given = Items.give(p, h.mats[i], want);
            d.storage.put(h.mats[i], have - given);
            if (given < want) {
                plugin.msg(p, "&cEnvanterin doldu!");
                return;
            }
        }
    }

    private void dropPage(Player p, Data d, StoreHolder h) {
        for (int i = 0; i < 45; i++) {
            if (h.mats[i] == null) continue;
            long have = d.storage.getOrDefault(h.mats[i], 0L);
            int n = (int) Math.min(h.amts[i], have);
            if (n <= 0) continue;
            d.storage.put(h.mats[i], have - n);
            p.getWorld().dropItemNaturally(p.getLocation(), new ItemStack(h.mats[i], n));
        }
    }

    private void sellAll(Player p, Data d) {
        Economy eco = plugin.getEconomy();
        if (eco == null) {
            plugin.msg(p, "&cEkonomi sistemi bulunamadı!");
            return;
        }
        double total = value(d);
        if (total <= 0) {
            plugin.msg(p, "&7Satılacak eşya yok.");
            return;
        }
        for (Map.Entry<Material, Long> e : d.storage.entrySet()) e.setValue(0L);
        eco.depositPlayer(p, total);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        plugin.msg(p, "&aDepodaki eşyalar satıldı: &2+$" + AtlasMC.money(total));
    }

    private void collectXp(Player p, Data d) {
        if (d.xp <= 0) return;
        int give = (int) Math.min(Integer.MAX_VALUE, d.xp);
        p.giveExp(give);
        d.xp = 0;
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        plugin.msg(p, "&a" + shortNum(give) + " XP &7topladın.");
    }

    /** XP ile Tamir (Mending) büyülü eşyaları onarır: 1 XP = 2 dayanıklılık. */
    private void repair(Player p, Data d) {
        if (d.xp <= 0) return;
        PlayerInventory inv = p.getInventory();
        long used = 0;
        int repaired = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (d.xp - used <= 0) break;
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType().isAir() || !it.containsEnchantment(Enchantment.MENDING)) continue;
            ItemMeta meta = it.getItemMeta();
            if (!(meta instanceof Damageable)) continue;
            Damageable dm = (Damageable) meta;
            if (dm.getDamage() <= 0) continue;
            long xpNeeded = (dm.getDamage() + 1) / 2;
            long use = Math.min(xpNeeded, d.xp - used);
            dm.setDamage(Math.max(0, dm.getDamage() - (int) (use * 2)));
            it.setItemMeta(meta);
            inv.setItem(slot, it);
            used += use;
            repaired++;
        }
        if (repaired == 0) {
            plugin.msg(p, "&7Onarılacak Tamir büyülü eşyan yok.");
            return;
        }
        d.xp -= used;
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
        plugin.msg(p, "&a" + repaired + " eşya onarıldı. &7Harcanan XP: &e" + shortNum(used));
    }

    // ---------- Olaylar ----------
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.SPAWNER) return;
        String type = itemType(e.getItemInHand());
        if (type == null) return;

        Block b = e.getBlockPlaced();
        CreatureSpawner cs = (CreatureSpawner) b.getState();
        try {
            cs.setSpawnedType(EntityType.valueOf(type));
        } catch (IllegalArgumentException ignored) {
        }
        cs.update();
        register(b, type);
        plugin.msg(e.getPlayer(), "&a" + displayName(type) + " Spawner &7yerleştirildi. Menü için &eSağ Tık&7, stacklemek için aynı spawner ile &eSağ Tık&7.");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String handType = itemType(hand);

        if (p.isSneaking() && handType == null) return; // çömelirken blok koyabilsin

        e.setCancelled(true);
        Data d = map.get(key(b));
        if (d == null) {
            EntityType et = ((CreatureSpawner) b.getState()).getSpawnedType();
            d = register(b, et == null ? "PIG" : et.name());
        }

        if (handType != null) {
            stackAdd(p, d, hand, handType);
            return;
        }
        openMenu(p, d, key(b));
    }

    private void stackAdd(Player p, Data d, ItemStack hand, String handType) {
        if (!handType.equals(d.type)) {
            plugin.msg(p, "&cFarklı türdeki spawner'lar birleştirilemez!");
            return;
        }
        int space = maxStack() - d.stack;
        if (space <= 0) {
            plugin.msg(p, "&cBu spawner en yüksek stack'e ulaştı (&e" + maxStack() + "&c).");
            return;
        }
        int n = p.isSneaking() ? Math.min(hand.getAmount(), space) : 1;
        d.stack += n;
        if (hand.getAmount() <= n) p.getInventory().setItemInMainHand(null);
        else {
            hand.setAmount(hand.getAmount() - n);
            p.getInventory().setItemInMainHand(hand);
        }
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.5f);
        plugin.msg(p, "&aStacklendi! &7Stack: &e" + d.stack + " &7| Çarpan: &ax" + trim1(mult(d)));
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

        Data d = map.get(key(b));
        Location loc = b.getLocation().add(0.5, 0.5, 0.5);

        // Stack > 1 ve çömelmiyorsa: sadece 1 spawner al
        if (d != null && d.stack > 1 && !p.isSneaking()) {
            e.setCancelled(true);
            d.stack--;
            b.getWorld().dropItemNaturally(loc, createItem(d.type));
            plugin.msg(p, "&e1 spawner alındı. &7Kalan stack: &e" + d.stack + " &8(Hepsini kırmak için çömelerek kır)");
            return;
        }

        e.setExpToDrop(0);
        e.setDropItems(false);

        String type;
        int count = 1;
        if (d != null) {
            map.remove(key(b));
            type = d.type;
            count = d.stack;
        } else {
            EntityType et = ((CreatureSpawner) b.getState()).getSpawnedType();
            type = et == null ? "PIG" : et.name();
        }

        int left = count;
        while (left > 0) {
            int n = Math.min(left, 64);
            ItemStack it = createItem(type);
            it.setAmount(n);
            b.getWorld().dropItemNaturally(loc, it);
            left -= n;
        }

        if (d != null) {
            if (d.xp > 0) p.giveExp((int) Math.min(Integer.MAX_VALUE, d.xp));
            for (Map.Entry<Material, Long> en : d.storage.entrySet()) {
                long l = en.getValue();
                while (l > 0) {
                    int n = (int) Math.min(l, en.getKey().getMaxStackSize());
                    b.getWorld().dropItemNaturally(loc, new ItemStack(en.getKey(), n));
                    l -= n;
                }
            }
        }
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
            y.set(p + "stack", d.stack);
            y.set(p + "sort", d.sort);
            for (Map.Entry<Material, Long> e : d.storage.entrySet())
                y.set(p + "storage." + e.getKey().name(), e.getValue());
            List<String> bl = new ArrayList<>();
            for (Material m : d.blocked) bl.add(m.name());
            y.set(p + "blocked", bl);
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
            d.x = s.getInt("x");
            d.y = s.getInt("y");
            d.z = s.getInt("z");
            d.xp = s.getLong("xp");
            d.stack = Math.max(1, s.getInt("stack", 1));
            d.sort = s.getInt("sort", 0);
            ConfigurationSection st = s.getConfigurationSection("storage");
            if (st != null) {
                for (String mk : st.getKeys(false)) {
                    Material m = Material.matchMaterial(mk);
                    if (m != null) d.storage.put(m, st.getLong(mk));
                }
            }
            for (String bn : s.getStringList("blocked")) {
                Material m = Material.matchMaterial(bn);
                if (m != null) d.blocked.add(m);
            }
            initStorage(d);
            map.put(d.world + ";" + d.x + ";" + d.y + ";" + d.z, d);
        }
    }
}
