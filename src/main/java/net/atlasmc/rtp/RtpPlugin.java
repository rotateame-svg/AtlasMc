package net.atlasmc.rtp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class RtpPlugin extends JavaPlugin implements Listener {

    private NamespacedKey npcKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Random random = new Random();

    private static final Set<Material> UNSAFE = Set.of(
            Material.LAVA, Material.WATER, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.BEDROCK);

    /** Menu envanterini taniyabilmek icin ozel holder */
    private static class RtpMenu implements InventoryHolder {
        private Inventory inv;
        @Override
        public Inventory getInventory() { return inv; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        npcKey = new NamespacedKey(this, "rtpnpc");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // ---------------------------------------------------------------- Komutlar

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Bu komut sadece oyuncular icindir.");
            return true;
        }
        switch (cmd.getName().toLowerCase()) {
            case "rtp" -> openMenu(p);
            case "rtpnpcolustur" -> createNpc(p);
            case "rtpnpcsil" -> removeNearestNpc(p);
        }
        return true;
    }

    private void createNpc(Player p) {
        Location loc = p.getLocation();
        loc.setPitch(0f);
        String name = getConfig().getString("npc-isim", "&b&lRTP");
        p.getWorld().spawn(loc, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.customName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        });
        p.sendMessage(Component.text("RTP NPC olusturuldu.", NamedTextColor.GREEN));
    }

    private void removeNearestNpc(Player p) {
        Entity nearest = null;
        double best = 25; // 5 blok^2
        for (Entity e : p.getNearbyEntities(5, 5, 5)) {
            if (e.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) {
                double d = e.getLocation().distanceSquared(p.getLocation());
                if (d < best) { best = d; nearest = e; }
            }
        }
        if (nearest == null) {
            p.sendMessage(Component.text("Yakinda RTP NPC yok.", NamedTextColor.RED));
            return;
        }
        nearest.remove();
        p.sendMessage(Component.text("RTP NPC silindi.", NamedTextColor.GREEN));
    }

    // ---------------------------------------------------------------- Menu

    private void openMenu(Player p) {
        RtpMenu holder = new RtpMenu();
        holder.inv = Bukkit.createInventory(holder, 27,
                Component.text("Gitmek istedigin dunyayi sec", NamedTextColor.DARK_GRAY));

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 27; i++) holder.inv.setItem(i, filler);

        holder.inv.setItem(11, item(Material.GRASS_BLOCK,
                Component.text("SMP Dunya", NamedTextColor.GREEN),
                List.of(Component.text("Tikla ve rastgele isinlan", NamedTextColor.GRAY))));
        holder.inv.setItem(13, item(Material.NETHERRACK,
                Component.text("SMP Nether", NamedTextColor.RED),
                List.of(Component.text("Tikla ve rastgele isinlan", NamedTextColor.GRAY))));
        holder.inv.setItem(15, item(Material.END_STONE,
                Component.text("SMP End", NamedTextColor.LIGHT_PURPLE),
                List.of(Component.text("Tikla ve rastgele isinlan", NamedTextColor.GRAY))));

        p.openInventory(holder.inv);
    }

    private ItemStack item(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RtpMenu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;

        String key = switch (e.getSlot()) {
            case 11 -> "dunya";
            case 13 -> "nether";
            case 15 -> "end";
            default -> null;
        };
        if (key == null) return;
        p.closeInventory();
        startRtp(p, key);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof RtpMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        openMenu(e.getPlayer());
    }

    // ---------------------------------------------------------------- RTP

    private void startRtp(Player p, String key) {
        int cd = getConfig().getInt("cooldown-saniye", 10);
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cd * 1000L && !p.hasPermission("atlasrtp.admin")) {
            long left = (cd * 1000L - (now - last)) / 1000 + 1;
            p.sendMessage(Component.text("Lutfen " + left + " saniye bekle.", NamedTextColor.RED));
            return;
        }

        String worldName = getConfig().getString("dunyalar." + key + ".isim");
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        if (w == null) {
            p.sendMessage(Component.text("Bu dunya su an mevcut degil: " + worldName, NamedTextColor.RED));
            return;
        }
        cooldowns.put(p.getUniqueId(), now);
        p.sendMessage(Component.text("Guvenli bir yer araniyor...", NamedTextColor.YELLOW));
        attempt(p, w, key, 0);
    }

    private void attempt(Player p, World w, String key, int tries) {
        if (!p.isOnline()) return;
        if (tries >= 25) {
            p.sendMessage(Component.text("Guvenli yer bulunamadi, tekrar dene.", NamedTextColor.RED));
            cooldowns.remove(p.getUniqueId());
            return;
        }
        int min = getConfig().getInt("dunyalar." + key + ".min", 500);
        int max = getConfig().getInt("dunyalar." + key + ".max", 5000);
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = min + random.nextDouble() * (max - min);
        int x = (int) (Math.cos(angle) * dist);
        int z = (int) (Math.sin(angle) * dist);

        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            Location loc = findSafe(w, x, z);
            if (loc == null || !w.getWorldBorder().isInside(loc)) {
                Bukkit.getScheduler().runTask(this, () -> attempt(p, w, key, tries + 1));
                return;
            }
            p.teleportAsync(loc).thenAccept(ok -> {
                if (ok) p.sendMessage(Component.text("Isinlandin! (" + x + ", " + z + ")", NamedTextColor.GREEN));
            });
        });
    }

    private Location findSafe(World w, int x, int z) {
        switch (w.getEnvironment()) {
            case NETHER -> {
                for (int y = 120; y > 32; y--) {
                    if (isStandable(w.getBlockAt(x, y, z)) && isFree(w, x, y + 1, z) && isFree(w, x, y + 2, z)) {
                        return new Location(w, x + 0.5, y + 1, z + 0.5);
                    }
                }
                return null;
            }
            default -> {
                int y = w.getHighestBlockYAt(x, z);
                if (y <= w.getMinHeight()) return null; // bosluk
                if (!isStandable(w.getBlockAt(x, y, z))) return null;
                if (!isFree(w, x, y + 1, z) || !isFree(w, x, y + 2, z)) return null;
                return new Location(w, x + 0.5, y + 1, z + 0.5);
            }
        }
    }

    private boolean isStandable(Block b) {
        return b.getType().isSolid() && !UNSAFE.contains(b.getType());
    }

    private boolean isFree(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        return b.isPassable() && !b.isLiquid() && !UNSAFE.contains(b.getType());
    }
}
