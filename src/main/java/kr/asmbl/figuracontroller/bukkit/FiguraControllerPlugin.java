package kr.asmbl.figuracontroller.bukkit;

import kr.asmbl.figuracontroller.FiguraController;
import kr.asmbl.figuracontroller.api.FiguraControllerAPI;
import kr.asmbl.figuracontroller.api.FiguraSignalReceiveEvent;
import kr.asmbl.figuracontroller.api.FiguraStateChangeEvent;
import kr.asmbl.figuracontroller.core.Dispatcher;
import kr.asmbl.figuracontroller.core.StateStore;
import kr.asmbl.figuracontroller.protocol.C2S;
import kr.asmbl.figuracontroller.protocol.Protocol;
import kr.asmbl.figuracontroller.protocol.ProtocolException;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.figuramc.figura.server.api.FiguraFSBApi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FiguraController — 서버가 주인인 Figura 상태 · 신호 (설계 `projects/figura_controller/design/컨트롤러_플러그인/plan.md`).
 * <p>
 * 흐름: API · 명령 → {@link StateStore}(즉시) → 틱 끝 {@link Dispatcher#flush} → 받는 클라마다 FSB `figuracontroller:v1` 패킷 →
 * 포크 코어가 아바타보다 먼저 가로채 클라 저장소에 반영(T4).
 */
public final class FiguraControllerPlugin extends JavaPlugin implements Listeners.Host, FcCommand.Host {
    private Settings settings;
    private StateStore store;
    private Dispatcher dispatcher;
    private Storage storage;
    private ApiImpl api;
    private final BukkitView view = new BukkitView();
    private long sentPackets;
    private long sentBytes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.from(getConfig());
        store = new StateStore();
        dispatcher = new Dispatcher(store);
        store.addListener(dispatcher);
        store.addListener((s, n, o, nw, c) -> Bukkit.getPluginManager().callEvent(
                new FiguraStateChangeEvent(s, n, o == null ? null : o.value, nw == null ? null : nw.value, c)));
        storage = new Storage(this, store, dispatcher, settings);
        store.setPersistListener(storage::markDirty);
        PresetStore presets = new PresetStore(new java.io.File(getDataFolder(), "presets"));
        try {
            presets.ensureDefault();
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "프리셋 default 를 못 만들었다", e);
        }
        api = new ApiImpl(store, dispatcher, storage, presets);

        // 저장된 것 — 전역 · 접속한 플레이어(리로드) · 실린 몹
        boolean hadGlobal = storage.loadGlobal();
        for (Player p : Bukkit.getOnlinePlayers()) storage.loadPlayer(p.getUniqueId());
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (!(e instanceof Player)) storage.loadEntity(e);
        if (!hadGlobal && presets.exists("default")) {
            // 첫 실행 — 옛 CH 의 «~global.json 이 없을 때 기본 전역» 과 같게
            List<UUID> g = new ArrayList<>();
            g.add(null);
            try {
                api.applyPresetInternal("default", g);
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "프리셋 default 적용 실패", e);
            }
        }

        getServer().getPluginManager().registerEvents(new Listeners(this, store, dispatcher, storage), this);
        FiguraFSBApi.onServerPacket(Protocol.CHANNEL, this::onPacket);
        FcCommand fc = new FcCommand(this, api, store, dispatcher, storage, presets);
        PluginCommand cmd = getCommand("figuracontroller");
        if (cmd != null) {
            cmd.setExecutor(fc);
            cmd.setTabCompleter(fc);
        }
        getServer().getServicesManager().register(FiguraControllerAPI.class, api, this, ServicePriority.Normal);
        FiguraController.install(api);

        // 플러그인 리로드 — 이미 접속한 새 코어 클라는 HELLO 를 다시 안 보낸다 → FSB 가 연결된 사람은 준비됨으로 보고 처음부터 다시 보낸다
        // (옛 코어가 받아도 해가 없다: 그 이름의 리스너가 없어 버린다)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (FiguraFSBApi.isConnected(p.getUniqueId())) dispatcher.hello(p.getUniqueId(), Protocol.VERSION);
        }
    }

    @Override
    public void onDisable() {
        FiguraController.install(null);
        try {
            FiguraFSBApi.onServerPacket(Protocol.CHANNEL, (name, sender, player, owner, data) -> {});
        } catch (Throwable ignored) {
            // FSB 가 먼저 꺼졌다
        }
        getServer().getServicesManager().unregisterAll(this);
        if (storage != null) storage.flushAllSync();
    }

    // ── 클라 → 서버

    private void onPacket(String name, UUID senderUuid, Player sender, UUID avatarOwner, byte[] data) {
        if (Bukkit.isPrimaryThread()) handlePacket(senderUuid, avatarOwner, data);
        else Bukkit.getScheduler().runTask(this, () -> handlePacket(senderUuid, avatarOwner, data));
    }

    private void handlePacket(UUID sender, UUID avatarOwner, byte[] data) {
        if (!sender.equals(avatarOwner)) {
            debug("호스트가 아닌 아바타의 패킷은 버린다 — 보낸 사람 " + sender + " · 아바타 " + avatarOwner);
            return;
        }
        Player p = Bukkit.getPlayer(sender);
        if (p == null) return;
        C2S.Op op;
        try {
            op = C2S.read(data);
        } catch (ProtocolException e) {
            debug("깨진 패킷 — " + p.getName() + ": " + e.getMessage());
            return;
        }
        if (op instanceof C2S.Hello h) {
            if (dispatcher.hello(sender, h.version())) debug("HELLO — " + p.getName());
            else getLogger().warning("규약판이 다르다 — " + p.getName() + " 클라 v" + h.version() + " · 서버 v" + Protocol.VERSION + " (코어를 맞춘다)");
        } else if (op instanceof C2S.Signal s) {
            Bukkit.getPluginManager().callEvent(new FiguraSignalReceiveEvent(p, s.name(), s.data()));
        }
    }

    // ── 틱 끝

    @Override
    public void tickEnd() {
        long now = Bukkit.getCurrentTick();
        store.expire(now);
        storage.tick(now);
        Map<UUID, List<byte[]>> out = dispatcher.flush(view);
        for (Map.Entry<UUID, List<byte[]>> e : out.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            for (byte[] body : e.getValue()) {
                FiguraFSBApi.sendServerPacket(p, p.getUniqueId(), Protocol.CHANNEL, body);
                sentPackets++;
                sentBytes += body.length;
            }
        }
    }

    @Override
    public void scheduleOldCoreCheck(Player p) {
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q != null && FiguraFSBApi.isConnected(id) && !dispatcher.isReady(id)) {
                getLogger().warning("FSB 는 연결됐는데 HELLO 가 없다(옛 코어?) — " + q.getName() + " · 이 사람은 상태를 못 받는다");
            }
        }, settings.oldCoreWarnTicks());
    }

    // ── Host

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public String version() {
        return getPluginMeta().getVersion();
    }

    @Override
    public long[] sentStats() {
        return new long[]{sentPackets, sentBytes};
    }

    @Override
    public Logger logger() {
        return getLogger();
    }

    private void debug(String m) {
        if (settings.debug()) getLogger().info("[debug] " + m);
    }
}
