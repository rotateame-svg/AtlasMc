package net.atlasmc;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class Items {
    private Items() {}

    /** Meta'sız (düz) eşyaları sayar. */
    public static int countPlain(Player p, Material m) {
        int c = 0;
        for (ItemStack i : p.getInventory().getStorageContents())
            if (i != null && i.getType() == m && !i.hasItemMeta()) c += i.getAmount();
        return c;
    }

    public static void removePlain(Player p, Material m, int amt) {
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

    /** Envanter dolana kadar verir, verilen miktarı döndürür. */
    public static int give(Player p, Material m, int amt) {
        int given = 0, left = amt;
        while (left > 0) {
            int n = Math.min(left, m.getMaxStackSize());
            Map<Integer, ItemStack> r = p.getInventory().addItem(new ItemStack(m, n));
            int not = r.values().stream().mapToInt(ItemStack::getAmount).sum();
            given += n - not;
            left -= n;
            if (not > 0) break;
        }
        return given;
    }

    public static void giveOrDrop(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
    }
}
