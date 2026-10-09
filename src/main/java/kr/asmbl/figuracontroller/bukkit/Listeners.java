package kr.asmbl.figuracontroller.bukkit;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import kr.asmbl.figuracontroller.core.Dispatcher;
import kr.asmbl.figuracontroller.core.StateStore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/** Bukkit 이벤트 → 저장소 · 보내기 · 저장 */
final class Listeners implements Listener {
    private final Host host;
    private final StateStore store;
    private final Dispatcher dispatcher;
    private final Storage storage;

    /** 플러그인 본체가 준다 — 순환 의존을 줄이려고 필요한 것만 */
    interface Host {
        void tickEnd();

        void scheduleOldCoreCheck(Player p);
    }

    Listeners(Host host, StateStore store, Dispatcher dispatcher, Storage storage) {
        this.host = host;
        this.store = store;
        this.dispatcher = dispatcher;
        this.storage = storage;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) storage.preload(e.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        storage.loadPlayer(e.getPlayer().getUniqueId());
        host.scheduleOldCoreCheck(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        dispatcher.quit(id);
        storage.quit(id);
    }

    /** 보기 시작 — 상태가 있는 대상만(대부분의 엔티티는 표 조회 한 번으로 끝) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent e) {
        UUID s = e.getEntity().getUniqueId();
        if (store.subject(s) != null) dispatcher.trackStart(e.getPlayer().getUniqueId(), s);
    }

    /** 그만 봄 — 보낸 적이 있는 대상만 DROP */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntrack(PlayerUntrackEntityEvent e) {
        UUID p = e.getPlayer().getUniqueId();
        UUID s = e.getEntity().getUniqueId();
        if (dispatcher.knows(p, s)) dispatcher.trackStop(p, s);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAdd(EntityAddToWorldEvent e) {
        if (!(e.getEntity() instanceof Player)) storage.loadEntity(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveFromWorldEvent e) {
        if (!(e.getEntity() instanceof Player)) storage.entityRemoved(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent e) {
        host.tickEnd();
    }
}
