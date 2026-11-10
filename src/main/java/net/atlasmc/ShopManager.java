package net.atlasmc;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
import java.util.Map;

import static net.atlasmc.AtlasMC.color;

public class ShopManager implements CommandExecutor, Listener {

    private record Entry(Material mat, double buy, double sell) {}

    private static class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final AtlasMC plugin;
    private final List<Entry> entries = new ArrayList<>();

    public ShopManager(AtlasMC plugin) {
        this.plugin = plugin;
        for (Map<?, ?> m : plugin.getConfig().getMapList("shop.items")) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
            if (mat == null) continue;
            double buy = m.get("buy") == null ? 0 : Double.parseDouble(String.valueOf(m.get("buy")));
            double sell = m.get("sell") == null ? 0 : Double.parseDouble(String.valueOf(m.get("sell")));
            if (entries.size() < 54) entries.add(new Entry(mat, buy, sell));
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) return true;
        open(p);
        return true;
    }

    private void open(Player p) {
        Holder h = new Holder();
        int size = Math.max(9, ((entries.size() - 1) / 9 + 1) * 9);
        Inventory inv = Bukkit.createInventory(h, size, color(plugin.getConfig().getString("shop.title", "&8Atlas Shop")));
        h.inv = inv;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            ItemStack it = new ItemStack(e.mat());
            ItemMeta meta = it.getItemMeta();
            List<String> lore = new ArrayList<>();
            lore.add(color("&7Alış: " + (e.buy() > 0 ? "&a$" + AtlasMC.money(e.buy()) : "&cSatılmıyor")));
            lore.add(color("&7Satış: " + (e.sell() > 0 ? "&e$" + AtlasMC.money(e.sell()) : "&cAlınmıyor")));
            lore.add("");
            lore.add(color("&aSol tık: &f1 al  &7| &aShift+Sol: &f64 al"));
            lore.add(color("&eSağ tık: &f1 sat  &7| &eShift+Sağ: &fhepsini sat"));
            meta.setLore(lore);
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        if (slot < 0 || slot >= entries.size()) return;
        Player p = (Player) e.getWhoClicked();
        Entry en = entries.get(slot);
        switch (e.getClick()) {
            case LEFT -> buy(p, en, 1);
            case SHIFT_LEFT -> buy(p, en, 64);
            case RIGHT -> sell(p, en, 1);
            case SHIFT_RIGHT -> sell(p, en, -1);
            default -> {}
        }
    }

    private void buy(Player p, Entry en, int amt) {
        Economy eco = plugin.getEconomy();
        if (eco == null) { plugin.msg(p, "&cEkonomi sistemi bulunamadı!"); return; }
        if (en.buy() <= 0) { plugin.msg(p, "&cBu ürün satılmıyor."); return; }
        double cost = en.buy() * amt;
        if (!eco.has(p, cost)) { plugin.msg(p, "&cYeterli paran yok! Gerekli: &a$" + AtlasMC.money(cost)); return; }
        eco.withdrawPlayer(p, cost);
        Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(en.mat(), amt));
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        plugin.msg(p, "&a" + amt + "x " + AtlasMC.pretty(en.mat().name()) + " &7satın aldın. &c-$" + AtlasMC.money(cost));
    }

    private void sell(Player p, Entry en, int amt) {
        Economy eco = plugin.getEconomy();
        if (eco == null) { plugin.msg(p, "&cEkonomi sistemi bulunamadı!"); return; }
        if (en.sell() <= 0) { plugin.msg(p, "&cBu ürün alınmıyor."); return; }
        int have = count(p, en.mat());
        if (amt == -1) amt = have;
        if (amt <= 0 || have < amt) { plugin.msg(p, "&cEnvanterinde yeterli ürün yok!"); return; }
        remove(p, en.mat(), amt);
        double gain = en.sell() * amt;
        eco.depositPlayer(p, gain);
        plugin.msg(p, "&e" + amt + "x " + AtlasMC.pretty(en.mat().name()) + " &7sattın. &a+$" + AtlasMC.money(gain));
    }

    private int count(Player p, Material m) {
        int c = 0;
        for (ItemStack i : p.getInventory().getStorageContents())
            if (i != null && i.getType() == m && !i.hasItemMeta()) c += i.getAmount();
        return c;
    }

    private void remove(Player p, Material m, int amt) {
        ItemStack[] cont = p.getInventory().getStorageContents();
        for (int s = 0; s < cont.length && amt > 0; s++) {
            ItemStack i = cont[s];
            if (i == null || i.getType() != m || i.hasItemMeta()) continue;
            int take = Math.min(amt, i.getAmount());
            i.setAmount(i.getAmount() - take);
            if (i.getAmount() <= 0) cont[s] = null;
            amt -= take;
        }
        p.getInventory().setStorageContents(cont);
    }
}
