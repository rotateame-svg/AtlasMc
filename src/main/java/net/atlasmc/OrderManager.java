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

/**
 * /order : Alım siparişi sistemi.
 * Oyuncu "şu eşyadan şu kadar, tanesi şu fiyata isterim" der, parası peşin düşer.
 * Başkaları eşyayı teslim edip parayı alır. Sipariş sahibi teslimatı menüden alır.
 */
public class OrderManager implements CommandExecutor, Listener {

    static class Order {
        long id;
        UUID owner;
        String ownerName;
        Material mat;
        int amount, filled, collected;
        double unit;
    }

    private static class Holder implements InventoryHolder {
        Inventory inv;
        boolean mine;
        int page;
        final List<Long> ids = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final AtlasMC plugin;
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private long nextId = 1;
    private final File file;

    public OrderManager(AtlasMC plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "orders.yml");
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
            case "olustur", "oluştur", "create" -> create(p, a);
            case "siparislerim", "siparişlerim" -> open(p, true, 0);
            default -> {
                plugin.msg(p, "&e/order &7- Açık siparişleri gör, eşya teslim et");
                plugin.msg(p, "&e/order olustur <adet> <birim fiyat> [materyal] &7- Sipariş ver");
                plugin.msg(p, "&8  (materyal yazmazsan elindeki eşya istenir)");
                plugin.msg(p, "&e/order siparislerim &7- Siparişlerini yönet");
            }
        }
        return true;
    }

    private void create(Player p, String[] a) {
        if (a.length < 3) {
            plugin.msg(p, "&cKullanım: &e/order olustur <adet> <birim fiyat> [materyal]");
            return;
        }
        int amount;
        double unit;
        try {
            amount = Integer.parseInt(a[1]);
            unit = Double.parseDouble(a[2].replace(",", "."));
        } catch (NumberFormatException ex) {
            plugin.msg(p, "&cAdet ve fiyat sayı olmalı.");
            return;
        }
        if (amount < 1 || amount > 10000) {
            plugin.msg(p, "&cAdet 1 ile 10000 arasında olmalı.");
            return;
        }
        if (Double.isNaN(unit) || unit <= 0 || unit > 1e9) {
            plugin.msg(p, "&cGeçersiz birim fiyat.");
            return;
        }
        Material mat;
        if (a.length >= 4) mat = Material.matchMaterial(a[3]);
        else mat = p.getInventory().getItemInMainHand().getType();
        if (mat == null || mat.isAir() || !mat.isItem()) {
            plugin.msg(p, "&cGeçerli bir eşya belirt (materyal yaz ya da elinde tut).");
            return;
        }

        int count = 0;
        for (Order o : orders.values()) if (o.owner.equals(p.getUniqueId())) count++;
        int max = plugin.getConfig().getInt("order.max-orders", 5);
        if (count >= max) {
            plugin.msg(p, "&cEn fazla &e" + max + " &csiparişin olabilir.");
            return;
        }

        Economy eco = plugin.getEconomy();
        if (eco == null) { plugin.msg(p, "&cEkonomi sistemi bulunamadı!"); return; }
        double total = amount * unit;
        if (!eco.has(p, total)) {
            plugin.msg(p, "&cYeterli paran yok! Gerekli: &a$" + AtlasMC.money(total));
            return;
        }
        EconomyResponse r = eco.withdrawPlayer(p, total);
        if (!r.transactionSuccess()) {
            plugin.msg(p, "&cÖdeme başarısız.");
            return;
        }

        Order o = new Order();
        o.id = nextId++;
        o.owner = p.getUniqueId();
        o.ownerName = p.getName();
        o.mat = mat;
        o.amount = amount;
        o.unit = unit;
        orders.put(o.id, o);
        save();
        plugin.msg(p, "&aSipariş verildi: &f" + amount + "x " + AtlasMC.pretty(mat.name())
                + " &7(tanesi &a$" + AtlasMC.money(unit) + "&7) &c-$" + AtlasMC.money(total));
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

    private void reopen(Player p, boolean mine, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> open(p, mine, page));
    }

    private void open(Player p, boolean mine, int page) {
        List<Order> list = new ArrayList<>();
        for (Order o : orders.values()) {
            if (mine) {
                if (o.owner.equals(p.getUniqueId())) list.add(o);
            } else if (o.amount - o.filled > 0) {
                list.add(o);
            }
        }
        if (mine) list.sort(Comparator.comparingLong((Order o) -> o.id).reversed());
        else list.sort(Comparator.comparingDouble((Order o) -> o.unit).reversed());

        int maxPage = Math.max(0, (list.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));

        Holder h = new Holder();
        h.mine = mine;
        h.page = page;
        Inventory inv = Bukkit.createInventory(h, 54, color(mine ? "&8Siparişlerim" : "&8Atlas Siparişler"));
        h.inv = inv;

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Order o = list.get(idx);
            h.ids.add(o.id);

            int remaining = o.amount - o.filled;
            ItemStack it = new ItemStack(o.mat);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(color("&f" + AtlasMC.pretty(o.mat.name())));
            List<String> lore = new ArrayList<>();
            lore.add(color("&7Sipariş sahibi: &f" + o.ownerName));
            lore.add(color("&7İstenen: &e" + remaining + "&7/&e" + o.amount));
            lore.add(color("&7Birim fiyat: &a$" + AtlasMC.money(o.unit)));
            lore.add(color("&7Toplam ödenecek: &a$" + AtlasMC.money(remaining * o.unit)));
            lore.add("");
            if (mine) {
                lore.add(color("&7Teslim edilip alınmayı bekleyen: &b" + o.collected));
                lore.add(color("&aSol tık: &fteslim edilenleri al"));
                lore.add(color("&cSağ tık: &fsiparişi iptal et (kalan para iade)"));
            } else if (o.owner.equals(p.getUniqueId())) {
                lore.add(color("&8Bu senin siparişin."));
            } else {
                lore.add(color("&aTıkla: &felindeki ürünleri teslim et"));
            }
            m.setLore(lore);
            it.setItemMeta(m);
            inv.setItem(i, it);
        }

        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, glass);
        if (page > 0) inv.setItem(45, named(Material.ARROW, "&eÖnceki sayfa"));
        if (page < maxPage) inv.setItem(53, named(Material.ARROW, "&eSonraki sayfa"));
        inv.setItem(49, mine
                ? named(Material.BOOK, "&aTüm Siparişler", "&7Açık siparişlere dön")
                : named(Material.CHEST, "&6Siparişlerim", "&7Kendi siparişlerini yönet"));
        inv.setItem(50, named(Material.PAPER, "&7Sayfa &f" + (page + 1) + "&7/&f" + (maxPage + 1),
                "&7Toplam sipariş: &f" + list.size()));
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

        Order o = orders.get(h.ids.get(s));
        if (o == null) { reopen(p, h.mine, h.page); return; }

        if (h.mine) {
            if (e.getClick().isRightClick()) cancel(p, o);
            else claim(p, o);
        } else {
            fill(p, o);
        }
        reopen(p, h.mine, h.page);
    }

    private void fill(Player p, Order o) {
        if (o.owner.equals(p.getUniqueId())) {
            plugin.msg(p, "&cKendi siparişine teslimat yapamazsın.");
            return;
        }
        Economy eco = plugin.getEconomy();
        if (eco == null) { plugin.msg(p, "&cEkonomi sistemi bulunamadı!"); return; }
        int remaining = o.amount - o.filled;
        int give = Math.min(remaining, Items.countPlain(p, o.mat));
        if (give <= 0) {
            plugin.msg(p, "&cEnvanterinde bu üründen yok! (Büyülü/isimli eşyalar kabul edilmez)");
            return;
        }
        Items.removePlain(p, o.mat, give);
        double pay = give * o.unit;
        eco.depositPlayer(p, pay);
        o.filled += give;
        o.collected += give;
        save();
        plugin.msg(p, "&a" + give + "x " + AtlasMC.pretty(o.mat.name()) + " &7teslim ettin. &a+$" + AtlasMC.money(pay));
        Player owner = Bukkit.getPlayer(o.owner);
        if (owner != null)
            plugin.msg(owner, "&a" + p.getName() + " &7siparişine &f" + give + "x " + AtlasMC.pretty(o.mat.name())
                    + " &7teslim etti. &e/order siparislerim &7ile al.");
    }

    private void claim(Player p, Order o) {
        if (o.collected <= 0) {
            plugin.msg(p, "&7Henüz teslim edilen ürün yok.");
            return;
        }
        int given = Items.give(p, o.mat, o.collected);
        o.collected -= given;
        if (given == 0) plugin.msg(p, "&cEnvanterin dolu!");
        else plugin.msg(p, "&a" + given + "x " + AtlasMC.pretty(o.mat.name()) + " &7teslim aldın.");
        if (o.filled >= o.amount && o.collected <= 0) {
            orders.remove(o.id);
            plugin.msg(p, "&7Sipariş tamamlandı ve kaldırıldı.");
        }
        save();
    }

    private void cancel(Player p, Order o) {
        Economy eco = plugin.getEconomy();
        int remaining = o.amount - o.filled;
        if (remaining > 0 && eco != null) {
            double refund = remaining * o.unit;
            eco.depositPlayer(p, refund);
            plugin.msg(p, "&eSipariş iptal edildi. &a+$" + AtlasMC.money(refund) + " &7iade.");
        } else {
            plugin.msg(p, "&eSipariş kaldırıldı.");
        }
        int left = o.collected;
        while (left > 0) {
            int n = Math.min(left, o.mat.getMaxStackSize());
            Items.giveOrDrop(p, new ItemStack(o.mat, n));
            left -= n;
        }
        orders.remove(o.id);
        save();
    }

    // ---------- Kayıt ----------
    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Order o : orders.values()) {
            String p = "orders." + o.id + ".";
            y.set(p + "owner", o.owner.toString());
            y.set(p + "name", o.ownerName);
            y.set(p + "material", o.mat.name());
            y.set(p + "amount", o.amount);
            y.set(p + "filled", o.filled);
            y.set(p + "collected", o.collected);
            y.set(p + "unit", o.unit);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("orders.yml kaydedilemedi: " + ex.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("orders");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Order o = new Order();
                o.id = Long.parseLong(k);
                o.owner = UUID.fromString(s.getString("owner"));
                o.ownerName = s.getString("name", "?");
                o.mat = Material.matchMaterial(s.getString("material", ""));
                if (o.mat == null) continue;
                o.amount = s.getInt("amount");
                o.filled = s.getInt("filled");
                o.collected = s.getInt("collected");
                o.unit = s.getDouble("unit");
                orders.put(o.id, o);
                nextId = Math.max(nextId, o.id + 1);
            } catch (Exception ignored) {
            }
        }
    }
}
