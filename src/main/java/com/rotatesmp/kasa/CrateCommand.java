package com.rotatesmp.kasa;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CrateCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_KEYS = 2304;

    private final CrateManager crates;

    public CrateCommand(CrateManager crates) {
        this.crates = crates;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(CrateManager.ADMIN_PERM)) {
            CrateManager.msg(sender, "<red>Bu komutu kullanma yetkin yok.");
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "kasakoy" -> placeCrate(sender);
            case "kasaanahtarver" -> giveKeys(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------- /kasakoy ----------

    private void placeCrate(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            CrateManager.msg(sender, "<red>Bu komutu sadece oyuncular kullanabilir.");
            return;
        }

        RayTraceResult hit = player.rayTraceBlocks(6.0);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
            CrateManager.msg(player, "<red>Kasanın konacağı bir bloğa bak <gray>(en fazla 6 blok).");
            return;
        }

        BlockFace face = hit.getHitBlockFace();
        Block target = hit.getHitBlock().getRelative(face);
        World world = target.getWorld();

        if (target.getY() < world.getMinHeight() || target.getY() >= world.getMaxHeight()) {
            CrateManager.msg(player, "<red>Buraya kasa konamaz (yükseklik sınırı).");
            return;
        }
        if (!target.isEmpty() && !target.isReplaceable()) {
            CrateManager.msg(player, "<red>Orada kasa için yer yok.");
            return;
        }

        target.setType(Material.SHULKER_BOX, false);
        BlockData data = target.getBlockData();
        if (data instanceof Directional directional) {
            directional.setFacing(face);
            target.setBlockData(directional, false);
        }

        if (!(target.getState() instanceof ShulkerBox box)) {
            CrateManager.msg(player, "<red>Kasa yerleştirilemedi.");
            return;
        }

        Inventory crateInv = box.getInventory();
        PlayerInventory pi = player.getInventory();
        ItemStack[] storage = pi.getStorageContents();
        int moved = 0;
        int kept = 0;

        for (int i = 0; i < storage.length; i++) {
            ItemStack it = storage[i];
            if (it == null || it.getType().isAir() || crates.isKey(it)) {
                continue;
            }
            int before = it.getAmount();
            Map<Integer, ItemStack> left = crateInv.addItem(it.clone());
            if (left.isEmpty()) {
                storage[i] = null;
                moved += before;
            } else {
                ItemStack rest = left.values().iterator().next();
                moved += before - rest.getAmount();
                kept += rest.getAmount();
                storage[i] = rest;
            }
        }
        pi.setStorageContents(storage);

        crates.register(target);
        player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 1f, 1f);

        CrateManager.msg(player, "<green>Kasa yerleştirildi! <gray>Kasaya <white><n></white> item kondu.",
                Placeholder.unparsed("n", String.valueOf(moved)));
        if (kept > 0) {
            CrateManager.msg(player, "<yellow>Kasa dolduğu için <white><n></white> item envanterinde kaldı.",
                    Placeholder.unparsed("n", String.valueOf(kept)));
        }
        if (moved == 0) {
            CrateManager.msg(player, "<yellow>Envanterin boştu, kasa boş. <gray>Shift + sağ tık ile içine ödül koyabilirsin.");
        }
    }

    // ---------- /kasaanahtarver ----------

    private void giveKeys(CommandSender sender, String[] args) {
        if (args.length < 1) {
            CrateManager.msg(sender, "<red>Kullanım: <white>/kasaanahtarver <adet> [oyuncu]");
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            CrateManager.msg(sender, "<red>Adet bir sayı olmalı.");
            return;
        }
        if (amount < 1 || amount > MAX_KEYS) {
            CrateManager.msg(sender, "<red>Adet 1 ile <max> arasında olmalı.",
                    Placeholder.unparsed("max", String.valueOf(MAX_KEYS)));
            return;
        }

        Player target;
        if (args.length >= 2) {
            target = sender.getServer().getPlayerExact(args[1]);
            if (target == null) {
                CrateManager.msg(sender, "<red>Oyuncu bulunamadı: <white><p>",
                        Placeholder.unparsed("p", args[1]));
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            CrateManager.msg(sender, "<red>Konsoldan kullanırken oyuncu adı yazmalısın.");
            return;
        }

        int remaining = amount;
        while (remaining > 0) {
            int n = Math.min(64, remaining);
            Map<Integer, ItemStack> left = target.getInventory().addItem(crates.createKey(n));
            for (ItemStack rest : left.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), rest);
            }
            remaining -= n;
        }

        CrateManager.msg(target, "<green><n> adet <gold>Kasa Anahtarı <green>aldın!",
                Placeholder.unparsed("n", String.valueOf(amount)));
        if (target != sender) {
            CrateManager.msg(sender, "<white><p> <gray>oyuncusuna <white><n> <gray>adet anahtar verildi.",
                    Placeholder.unparsed("p", target.getName()),
                    Placeholder.unparsed("n", String.valueOf(amount)));
        }
    }

    // ---------- tab ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!cmd.getName().equalsIgnoreCase("kasaanahtarver") || !sender.hasPermission(CrateManager.ADMIN_PERM)) {
            return out;
        }
        if (args.length == 1) {
            for (String s : List.of("1", "5", "10", "32", "64")) {
                if (s.startsWith(args[0])) {
                    out.add(s);
                }
            }
        } else if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player p : sender.getServer().getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
