package net.atlasmc;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static net.atlasmc.AtlasMC.color;

/** Atlas Mc Kristal Kazma: süreli, 3x3 kıran sunucu özel kazma. */
public class PickaxeManager implements CommandExecutor, Listener {

    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final AtlasMC plugin;
    private final NamespacedKey key;
    private boolean busy = false;

    public PickaxeManager(AtlasMC plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "kristal_kazma_bitis");
        // Her 30 saniyede süresi dolan kazmaları topla
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) checkExpired(p);
        }, 600L, 600L);
    }

    // ---------- Komut ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.isOp()) {
            plugin.msg(s, "&cBu komutu sadece operatörler kullanabilir!");
            return true;
        }
        int max = plugin.getConfig().getInt("pickaxe.max-days", 100);
        if (a.length < 1) {
            plugin.msg(s, "&cKullanım: &e/3x3kazmaver <gün 1-" + max + "> [oyuncu]");
            return true;
        }
        int days;
        try {
            days = Integer.parseInt(a[0]);
        } catch (NumberFormatException ex) {
            plugin.msg(s, "&cGün sayı olmalı. Örnek: &e/3x3kazmaver 7");
            return true;
        }
        if (days < 1 || days > max) {
            plugin.msg(s, "&cSüre 1 ile " + max + " gün arasında olmalı.");
            return true;
        }
        Player target;
        if (a.length >= 2) {
            target = Bukkit.getPlayerExact(a[1]);
            if (target == null) {
                plugin.msg(s, "&cOyuncu çevrimiçi değil.");
                return true;
            }
        } else if (s instanceof Player pl) {
            target = pl;
        } else {
            plugin.msg(s, "&cKonsoldan kullanırken oyuncu adı yaz.");
            return true;
        }
        Items.giveOrDrop(target, create(days));
        plugin.msg(target, "&b&lAtlas Mc Kristal Kazma &7aldın! Süre: &e" + days + " gün");
        if (s != target) plugin.msg(s, "&a" + target.getName() + " &7oyuncusuna &e" + days + " günlük &7kazma verildi.");
        return true;
    }

    private ItemStack create(int days) {
        Material mat = Material.matchMaterial(plugin.getConfig().getString("pickaxe.material", "DIAMOND_PICKAXE"));
        if (mat == null) mat = Material.DIAMOND_PICKAXE;
        long expire = System.currentTimeMillis() + days * 86400000L;

        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(color(plugin.getConfig().getString("pickaxe.name", "&b&lAtlas Mc Kristal Kazma")));
        m.addEnchant(Enchantment.FORTUNE, 3, true);
        m.addEnchant(Enchantment.EFFICIENCY, 5, true);
        m.addEnchant(Enchantment.UNBREAKING, 3, true);
        m.addEnchant(Enchantment.MENDING, 1, true);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(color("&b✦ &73x3 alanı tek seferde kırar"));
        lore.add(color("&b✦ &7Çömelerek kırarsan sadece 1 blok kırar"));
        lore.add("");
        lore.add(color("&7Süre: &e" + days + " gün"));
        lore.add(color("&7Bitiş: &c" + FMT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(expire), ZONE))));
        lore.add(color("&8Sunucuya özel eşya"));
        m.setLore(lore);
        m.getPersistentDataContainer().set(key, PersistentDataType.LONG, expire);
        it.setItemMeta(m);
        return it;
    }

    private Long expiry(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.LONG);
    }

    // ---------- Süre kontrolü ----------
    private void checkExpired(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        boolean removed = false;
        long now = System.currentTimeMillis();
        for (int i = 0; i < c.length; i++) {
            Long e = expiry(c[i]);
            if (e != null && e <= now) {
                inv.setItem(i, null);
                removed = true;
            }
        }
        if (removed) plugin.msg(p, "&cKristal Kazma'nın süresi doldu ve kayboldu.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkExpired(e.getPlayer()), 20L);
    }

    // ---------- 3x3 kırma ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (busy) return;
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        Long exp = expiry(tool);
        if (exp == null) return;

        if (exp <= System.currentTimeMillis()) {
            checkExpired(p);
            return;
        }
        if (p.isSneaking()) return;

        Block center = e.getBlock();
        BlockFace face = faceOf(p, center);
        int extra = 0;

        busy = true;
        try {
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) continue;
                    Block t;
                    if (face == BlockFace.UP || face == BlockFace.DOWN) t = center.getRelative(a, 0, b);
                    else if (face == BlockFace.NORTH || face == BlockFace.SOUTH) t = center.getRelative(a, b, 0);
                    else t = center.getRelative(0, b, a);

                    if (!canBreak(t)) continue;

                    Material type = t.getType();
                    BlockBreakEvent ev = new BlockBreakEvent(t, p);
                    Bukkit.getPluginManager().callEvent(ev);
                    if (ev.isCancelled()) continue;

                    Location loc = t.getLocation().add(0.5, 0.5, 0.5);
                    if (p.getGameMode() == GameMode.CREATIVE || !ev.isDropItems()) {
                        t.setType(Material.AIR);
                    } else {
                        t.breakNaturally(tool);
                        final int xp = oreExp(type);
                        if (xp > 0) t.getWorld().spawn(loc, ExperienceOrb.class, o -> o.setExperience(xp));
                    }
                    extra++;
                }
            }
        } finally {
            busy = false;
        }

        // Ekstra bloklar için dayanıklılık (Kırılmazlık büyüsü hesaba katılır)
        if (extra > 0 && p.getGameMode() != GameMode.CREATIVE) {
            int ub = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
            int dmg = 0;
            for (int i = 0; i < extra; i++) if (ThreadLocalRandom.current().nextInt(ub + 1) == 0) dmg++;
            ItemMeta meta = tool.getItemMeta();
            if (meta instanceof Damageable d) {
                int max = tool.getType().getMaxDurability();
                d.setDamage(Math.min(d.getDamage() + dmg, Math.max(0, max - 1)));
                tool.setItemMeta(meta);
            }
        }
    }

    private BlockFace faceOf(Player p, Block center) {
        RayTraceResult r = p.rayTraceBlocks(7.0);
        if (r != null && r.getHitBlock() != null && r.getHitBlock().equals(center) && r.getHitBlockFace() != null)
            return r.getHitBlockFace();
        float pitch = p.getLocation().getPitch();
        if (pitch > 50) return BlockFace.UP;
        if (pitch < -50) return BlockFace.DOWN;
        return p.getFacing().getOppositeFace();
    }

    private boolean canBreak(Block b) {
        Material t = b.getType();
        if (t.isAir() || b.isLiquid()) return false;
        if (t.getHardness() < 0) return false;                  // bedrock, barrier vb.
        if (t == Material.SPAWNER) return false;               // spawner kuralı: sadece ipeksi
        if (t == Material.REINFORCED_DEEPSLATE) return false;
        return !(b.getState() instanceof Container);            // sandık, fırın vb. korunur
    }

    private int oreExp(Material m) {
        String n = m.name();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (n.endsWith("DIAMOND_ORE") || n.endsWith("EMERALD_ORE")) return 3 + r.nextInt(5);
        if (n.endsWith("LAPIS_ORE")) return 2 + r.nextInt(4);
        if (n.endsWith("REDSTONE_ORE")) return 1 + r.nextInt(5);
        if (n.endsWith("COAL_ORE")) return r.nextInt(3);
        if (n.equals("NETHER_QUARTZ_ORE")) return 2 + r.nextInt(4);
        if (n.equals("NETHER_GOLD_ORE")) return r.nextInt(2);
        return 0;
    }
}
