package kr.asmbl.figuracontroller.bukkit;

import kr.asmbl.figuracontroller.core.WorldView;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** {@link WorldView} — Paper `Entity.getTrackedBy()`(자기 자신은 안 든다 — research §2 ④) */
final class BukkitView implements WorldView {
    @Override
    public Collection<UUID> trackers(UUID subject) {
        Entity e = Bukkit.getEntity(subject);
        if (e == null) return List.of();
        Set<Player> t = e.getTrackedBy();
        List<UUID> out = new ArrayList<>(t.size());
        for (Player p : t) out.add(p.getUniqueId());
        return out;
    }

    @Override
    public boolean isPlayer(UUID subject) {
        return Bukkit.getPlayer(subject) != null;
    }
}
