package com.rotatesmp.kasa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Rulet tarzi kasa acilis animasyonu. Envanterin holder'i da budur. */
public final class CrateRoll implements InventoryHolder {

    private static final int TOTAL_STEPS = 25;
    private static final int ROW_START = 9;   // orta satir 9..17
    private static final int CENTER_INDEX = 4; // satirdaki orta slot

    private final CrateManager manager;
    private final Player player;
    private final Inventory inventory;
    private final List<ItemStack> sequence = new ArrayList<>();
    private final ItemStack winner;

    private int step = 0;
    private boolean finished = false;
    private BukkitTask task;

    public CrateRoll(CrateManager manager, Player player, List<ItemStack> pool) {
        this.manager = manager;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, 27, CrateManager.MM.deserialize("<gold><bold>Kasa açılıyor..."));

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        this.winner = pool.get(rnd.nextInt(pool.size())).clone();

        for (int i = 0; i < TOTAL_STEPS + 9; i++) {
            sequence.add(pool.get(rnd.nextInt(pool.size())).clone());
        }
        sequence.set(TOTAL_STEPS + CENTER_INDEX, winner.clone());

        ItemStack pane = filler(Material.GRAY_STAINED_GLASS_PANE);
        ItemStack marker = filler(Material.LIME_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, pane);
            inventory.setItem(18 + i, pane);
        }
        inventory.setItem(CENTER_INDEX, marker);
        inventory.setItem(18 + CENTER_INDEX, marker);
    }

    private static ItemStack filler(Material material) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(" "));
        it.setItemMeta(meta);
        return it;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void start() {
        player.openInventory(inventory);
        render();
        schedule(delayFor(0));
    }

    private static long delayFor(int s) {
        return 2 + (s * s) / 100;
    }

    private void schedule(long delay) {
        task = manager.plugin().getServer().getScheduler().runTaskLater(manager.plugin(), this::tick, delay);
    }

    private void render() {
        for (int k = 0; k < 9; k++) {
            inventory.setItem(ROW_START + k, sequence.get(step + k).clone());
        }
    }

    private void tick() {
        if (finished) {
            return;
        }
        step++;
        render();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);

        if (step >= TOTAL_STEPS) {
            giveReward();
            manager.plugin().getServer().getScheduler().runTaskLater(manager.plugin(), this::closeView, 30L);
            return;
        }
        schedule(delayFor(step));
    }

    private void closeView() {
        if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) == this) {
            player.closeInventory();
        }
    }

    /** Animasyon bitmeden kapatilirsa odulu hemen verir (hile/kayip onlemi). */
    public void finishNow() {
        if (finished) {
            return;
        }
        giveReward();
    }

    private void giveReward() {
        if (finished) {
            return;
        }
        finished = true;
        if (task != null) {
            task.cancel();
        }
        manager.removeRoll(player.getUniqueId());

        ItemStack reward = winner.clone();
        Map<Integer, ItemStack> left = player.getInventory().addItem(reward);
        for (ItemStack rest : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        Component text = CrateManager.PREFIX
                .append(CrateManager.MM.deserialize("<gray>Kasadan kazandın: <white>"))
                .append(winner.displayName());
        if (winner.getAmount() > 1) {
            text = text.append(Component.text(" x" + winner.getAmount(), NamedTextColor.GRAY));
        }
        player.sendMessage(text);
    }
}
