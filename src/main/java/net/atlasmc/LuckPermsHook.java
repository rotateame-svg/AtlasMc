package net.atlasmc;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

/** LuckPerms yoksa sınıf hiç yüklenmez (AtlasMC içinde izole). */
public class LuckPermsHook {
    private final LuckPerms lp = LuckPermsProvider.get();

    public String primaryGroup(Player p) {
        User u = lp.getUserManager().getUser(p.getUniqueId());
        return u == null ? "default" : u.getPrimaryGroup();
    }
}
