package com.rotatesmp.kasa;

import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

public final class CrateListener implements Listener {

    private final CrateManager crates;

    public CrateListener(CrateManager crates) {
        this.crates = crates;
    }

    // ---------- kasayi acma ----------

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = e.getClickedBlock();
        if (block == null || !crates.isCrate(block)) {
            return;
        }

        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(block.getState() instanceof ShulkerBox box)) {
            return;
        }

        Player p = e.getPlayer();

        // Admin: shift + sag tik -> ici duzenle
        if (p.isSneaking() && p.hasPermission(CrateManager.ADMIN_PERM)) {
            p.openInventory(box.getInventory());
            return;
        }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!crates.isKey(hand)) {
            CrateManager.msg(p, "<red>Bu kasayı açmak için <gold>Kasa Anahtarı <red>gerekli!");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        if (crates.isRolling(p)) {
            return;
        }

        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack it : box.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) {
                pool.add(it.clone());
            }
        }
        if (pool.isEmpty()) {
            CrateManager.msg(p, "<red>Bu kasanın içi boş, şu an açılamıyor.");
            return;
        }

        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);

        crates.startRoll(p, pool);
    }

    // ---------- kasa korumasi ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!crates.isCrate(b)) {
            return;
        }
        Player p = e.getPlayer();
        if (p.hasPermission(CrateManager.ADMIN_PERM) && p.isSneaking()) {
            crates.unregister(b);
            CrateManager.msg(p, "<yellow>Kasa kaldırıldı.");
            return;
        }
        e.setCancelled(true);
        if (p.hasPermission(CrateManager.ADMIN_PERM)) {
            CrateManager.msg(p, "<gray>Kasayı kaldırmak için <white>shift basılıyken <gray>kır.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(crates::isCrate);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(crates::isCrate);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (crates.isCrate(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (crates.isCrate(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (isCrateInventory(e.getSource()) || isCrateInventory(e.getDestination())) {
            e.setCancelled(true);
        }
    }

    private boolean isCrateInventory(Inventory inv) {
        if (inv.getType() != InventoryType.SHULKER_BOX) {
            return false;
        }
        Location loc = inv.getLocation();
        return loc != null && crates.isCrate(loc.getBlock());
    }

    // ---------- animasyon GUI korumasi ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof CrateRoll) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof CrateRoll) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof CrateRoll roll) {
            roll.finishNow();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        CrateRoll roll = crates.getRoll(e.getPlayer().getUniqueId());
        if (roll != null) {
            roll.finishNow();
        }
    }
}
