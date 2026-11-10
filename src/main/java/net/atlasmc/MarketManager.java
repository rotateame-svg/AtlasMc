package net.atlasmc;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

import static net.atlasmc.AtlasMC.color;

public class MarketManager implements CommandExecutor, Listener {

    private static class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final AtlasMC plugin;

    public MarketManager(AtlasMC plugin) {
        this.plugin = plugin;
    }

    private List<String> keys() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("spawners");
        return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        // Yönetici: /kristal ver|al <oyuncu> <miktar>
        if (label.equalsIgnoreCase("kristal") && a.length == 3 && s.hasPermission("atlasmc.admin")) {
            Player t = Bukkit.getPlayer(a[1]);
            if (t == null) { plugin.msg(s, "&cOyuncu bulunamadı."); return true; }
            try {
                int n = Integer.parseInt(a[2]);
                if (a[0].equalsIgnoreCase("ver")) plugin.addCrystals(t.getUniqueId(), n);
                else if (a[0].equalsIgnoreCase("al")) plugin.addCrystals(t.getUniqueId(), -n);
                else return false;
                plugin.msg(s, "&a" + t.getName() + " yeni kristal: &d" + plugin.getCrystals(t.getUniqueId()));
            } catch (NumberFormatException ex) {
                plugin.msg(s, "&cGeçersiz sayı.");
            }
            return true;
        }
        if (!(s instanceof Player p)) return true;
        if (label.equalsIgnoreCase("kristal")) {
            plugin.msg(p, "&7Kristal bakiyen: &d" + plugin.getCrystals(p.getUniqueId()) + " ✦");
        }
        open(p);
        return true;
    }

    private void open(Player p) {
        List<String> keys = keys();
        Holder h = new Holder();
        int size = Math.max(9, ((keys.size() - 1) / 9 + 1) * 9);
        Inventory inv = Bukkit.createInventory(h, Math.min(size, 54), color("&8Kristal Market"));
        h.inv = inv;
        int bal = plugin.getCrystals(p.getUniqueId());
        for (int i = 0; i < keys.size() && i < 54; i++) {
            String k = keys.get(i);
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("spawners." + k);
            Material icon = Material.matchMaterial(k + "_SPAWN_EGG");
            if (icon == null) icon = Material.SPAWNER;
            ItemStack it = new ItemStack(icon);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(color("&e&l" + sec.getString("name", k) + " Spawner"));
            List<String> lore = new ArrayList<>();
            lore.add(color("&7Fiyat: &d" + sec.getInt("cost") + " Kristal"));
            ConfigurationSection drops = sec.getConfigurationSection("drops");
            if (drops != null) {
                lore.add("");
                lore.add(color("&7Üretir:"));
                for (String d : drops.getKeys(false))
                    lore.add(color("&8 • &f" + AtlasMC.pretty(d) + " &7(" + drops.getString(d) + ")"));
            }
            lore.add(color("&8 • &aXP: &f" + sec.getInt("xp")));
            lore.add("");
            lore.add(color(bal >= sec.getInt("cost") ? "&aSatın almak için tıkla!" : "&cYeterli kristalin yok."));
            lore.add(color("&7Bakiyen: &d" + bal + " ✦"));
            m.setLore(lore);
            it.setItemMeta(m);
            inv.setItem(i, it);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        List<String> keys = keys();
        int slot = e.getSlot();
        if (slot < 0 || slot >= keys.size()) return;
        Player p = (Player) e.getWhoClicked();
        String type = keys.get(slot);
        int cost = plugin.getConfig().getInt("spawners." + type + ".cost");
        if (!plugin.takeCrystals(p.getUniqueId(), cost)) {
            plugin.msg(p, "&cYeterli kristalin yok! Gerekli: &d" + cost);
            return;
        }
        ItemStack item = plugin.spawners().createItem(type);
        p.getInventory().addItem(item).values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        plugin.msg(p, "&a" + plugin.spawners().displayName(type) + " Spawner &7satın alındı! &d-" + cost + " Kristal");
        open(p);
    }
}
