package net.atlasmc;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.atlasmc.AtlasMC.color;

/** /ah : Açık Artırma / Pazar */
public class AuctionManager implements CommandExecutor, Listener {

    static class Listing {
        long id;
        UUID seller;
        String sellerName;
        ItemStack item;
        double price;
        long expireAt;
    }

    private static class Holder implements InventoryHolder {
        Inventory inv;
        boolean mine;
        int page;
        final List<Long> ids = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final AtlasMC plugin;
    private final Map<Long, Listing> listings = new LinkedHashMap<>();
    private long nextId = 1;
    private final File file;

    public AuctionManager(AtlasMC plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ah.yml");
        load();
    }

    // ---------- Komut ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Sadece oyuncular kullanabilir.");
            return true;
        }
        if (a.length == 0) {
            open(p, false, 0);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "sat", "sell" -> sell(p, a);
            case "ilanlarim", "ilanlarım" -> open(p, true, 0);
            default -> {
                plugin.msg(p, "&e/ah &7- Pazarı aç");
                plugin.msg(p, "&e/ah sat <fiyat> &7- Elindeki eşyayı sat");
                plugin.msg(p, "&e/ah ilanlarim &7- İlanlarını gör / geri al");
            }
        }
        return true;
    }

    private void sell(Player p, String[] a) {
        if (a.length < 2) {
            plugin.msg(p, "&cKullanım: &e/ah sat <fiyat>");
            return;
        }
        double price;
        try {
            price = Double.parseDouble(a[1].replace(",", "."));
        } catch (NumberFormatException ex) {
            plugin.msg(p, "&cGeçersiz fiyat.");
            return;
        }
        double min = plugin.getConfig().getDouble("ah.min-price", 1);
        if (Double.isNaN(price) || price < min || price > 1e12) {
            plugin.msg(p, "&cFiyat en az &a$" + AtlasMC.money(min) + " &colmalı.");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            plugin.msg(p, "&cSatmak için elinde bir eşya tutmalısın!");
            return;
        }
        int count = 0;
        for (Listing l : listings.values()) if (l.seller.equals(p.getUniqueId())) count++;
        int max = plugin.getConfig().getInt("ah.max-listings", 10);
        if (count >= max) {
            plugin.msg(p, "&cEn fazla &e" + max + " &cilanın olabilir. &e/ah ilanlarim &cile eskileri geri al.");
            return;
        }
        Listing l = new Listing();
        l.id = nextId++;
        l.seller = p.getUniqueId();
        l.sellerName = p.getName();
        l.item = hand.clone();
        l.price = price;
        l.expireAt = System.currentTimeMillis() + plugin.getConfig().getLong("ah.duration-hours", 48) * 3600000L;
        p.getInventory().setItemInMainHand(null);
        listings.put(l.id, l);
        save();
        plugin.msg(p, "&a" + l.item.getAmount() + "x " + AtlasMC.pretty(l.item.getType().name())
                + " &7ilana konuldu: &a$" + AtlasMC.money(price));
    }

    // ---------- Menü ----------
    private ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(color(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(color(s));
        meta.setLore(l);
        it.setItemMeta(meta);
        return it;
    }

    private String remain(long ms) {
        if (ms <= 0) return "&cDoldu";
        return "&e" + (ms / 3600000) + "sa " + ((ms / 60000) % 60) + "dk";
    }

    private void reopen(Player p, boolean mine, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> open(p, mine, page));
    }

    private void open(Player p, boolean mine, int page) {
        long now = System.currentTimeMillis();
        List<Listing> list = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (mine) {
                if (l.seller.equals(p.getUniqueId())) list.add(l);
            } else if (l.expireAt > now) {
                list.add(l);
            }
        }
        list.sort(Comparator.comparingLong((Listing l) -> l.id).reversed());

        int maxPage = Math.max(0, (list.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));

        Holder h = new Holder();
        h.mine = mine;
        h.page = page;
        Inventory inv = Bukkit.createInventory(h, 54, color(mine ? "&8İlanlarım" : "&8Atlas Pazar"));
        h.inv = inv;

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Listing l = list.get(idx);
            h.ids.add(l.id);

            ItemStack it = l.item.clone();
            ItemMeta m = it.getItemMeta();
            List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(color("&7Satıcı: &f" + l.sellerName));
            lore.add(color("&7Fiyat: &a$" + AtlasMC.money(l.price)));
            lore.add(color("&7Kalan süre: " + remain(l.expireAt - now)));
            lore.add("");
            if (l.seller.equals(p.getUniqueId()))
                lore.add(color("&eTıkla: ilanı geri al"));
            else
                lore.add(color("&aTıkla: satın al"));
            m.setLore(lore);
            it.setItemMeta(m);
            inv.setItem(i, it);
        }

        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, glass);
        if (page > 0) inv.setItem(45, named(Material.ARROW, "&eÖnceki sayfa"));
        if (page < maxPage) inv.setItem(53, named(Material.ARROW, "&eSonraki sayfa"));
        inv.setItem(49, mine
                ? named(Material.BOOK, "&aTüm İlanlar", "&7Pazara dön")
                : named(Material.CHEST, "&6İlanlarım", "&7Kendi ilanlarını gör / geri al"));
        inv.setItem(50, named(Material.PAPER, "&7Sayfa &f" + (page + 1) + "&7/&f" + (maxPage + 1),
                "&7Toplam ilan: &f" + list.size()));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        Player p = (Player) e.getWhoClicked();
        int s = e.getSlot();

        if (s == 45 && h.page > 0) { reopen(p, h.mine, h.page - 1); return; }
        if (s == 53) { reopen(p, h.mine, h.page + 1); return; }
        if (s == 49) { reopen(p, !h.mine, 0); return; }
        if (s < 0 || s >= h.ids.size()) return;

        Listing l = listings.get(h.ids.get(s));
        if (l == null) { reopen(p, h.mine, h.page); return; }

        if (l.seller.equals(p.getUniqueId())) {
            listings.remove(l.id);
            save();
            Items.giveOrDrop(p, l.item);
            plugin.msg(p, "&eİlanını geri aldın.");
        } else {
            buy(p, l);
        }
        reopen(p, h.mine, h.page);
    }

    private void buy(Player p, Listing l) {
        if (l.expireAt <= System.currentTimeMillis()) {
            plugin.msg(p, "&cBu ilanın süresi dolmuş.");
            return;
        }
        Economy eco = plugin.getEconomy();
        if (eco == null) { plugin.msg(p, "&cEkonomi sistemi bulunamadı!"); return; }
        if (!eco.has(p, l.price)) {
            plugin.msg(p, "&cYeterli paran yok! Gerekli: &a$" + AtlasMC.money(l.price));
            return;
        }
        EconomyResponse r = eco.withdrawPlayer(p, l.price);
        if (!r.transactionSuccess()) {
            plugin.msg(p, "&cÖdeme başarısız.");
            return;
        }
        double tax = plugin.getConfig().getDouble("ah.tax-percent", 5);
        double payout = l.price * (1 - tax / 100.0);
        eco.depositPlayer(Bukkit.getOfflinePlayer(l.seller), payout);

        listings.remove(l.id);
        save();
        Items.giveOrDrop(p, l.item);

        String name = l.item.getAmount() + "x " + AtlasMC.pretty(l.item.getType().name());
        plugin.msg(p, "&a" + name + " &7satın aldın. &c-$" + AtlasMC.money(l.price));
        Player sp = Bukkit.getPlayer(l.seller);
        if (sp != null)
            plugin.msg(sp, "&a" + p.getName() + " &7ilanını satın aldı: &f" + name + " &a+$" + AtlasMC.money(payout));
    }

    // ---------- Kayıt ----------
    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Listing l : listings.values()) {
            String p = "listings." + l.id + ".";
            y.set(p + "seller", l.seller.toString());
            y.set(p + "name", l.sellerName);
            y.set(p + "price", l.price);
            y.set(p + "expire", l.expireAt);
            y.set(p + "item", l.item);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("ah.yml kaydedilemedi: " + ex.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("listings");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Listing l = new Listing();
                l.id = Long.parseLong(k);
                l.seller = UUID.fromString(s.getString("seller"));
                l.sellerName = s.getString("name", "?");
                l.price = s.getDouble("price");
                l.expireAt = s.getLong("expire");
                l.item = s.getItemStack("item");
                if (l.item == null) continue;
                listings.put(l.id, l);
                nextId = Math.max(nextId, l.id + 1);
            } catch (Exception ignored) {
            }
        }
    }
}
