package kr.asmbl.figuracontroller.bukkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.asmbl.figuracontroller.core.Dispatcher;
import kr.asmbl.figuracontroller.core.Entry;
import kr.asmbl.figuracontroller.core.JsonText;
import kr.asmbl.figuracontroller.core.PersistCodec;
import kr.asmbl.figuracontroller.core.StateStore;
import kr.asmbl.figuracontroller.core.SubjectState;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 저장 (설계 §4.3) — 플레이어 `players/&lt;uuid&gt;.json` · 전역 `global.json` · 몹 엔티티 PDC `figuracontroller:states`.
 * <ul>
 *   <li>파일 쓰기는 **한 줄짜리 IO 스레드**에서 차례로(같은 파일의 옛 쓰기가 새 쓰기를 늦게 덮지 않게) · 임시 파일 → 원자적 이동.</li>
 *   <li>묶기: 처음 바뀐 뒤 {@code saveDelayTicks} 에 한 번(계속 바뀌어도 그 주기로) · 나갈 때 · 끌 때는 바로.</li>
 *   <li>플레이어는 `AsyncPlayerPreLoginEvent` 에서 **IO 스레드를 거쳐** 미리 읽는다(나갔다 바로 들어와도 방금 쓴 것을 읽는다) → 접속했을 때 이미 있다.</li>
 *   <li>몹은 바뀐 틱 끝에 PDC 에 바로 쓴다(메모리 위 값이라 싸다) — 청크가 내려갈 때 엔티티와 함께 저장된다.</li>
 *   <li>접속 안 한 플레이어 · 모르는 대상: 플레이어면 파일을 읽어 고친 뒤 저장하고 메모리에서 뺀다 · 모르는 UUID 는 메모리에만.</li>
 * </ul>
 */
final class Storage {
    /** 저장 예약표에서 «전역» 을 가리키는 열쇠 — 대상 UUID 와 안 겹친다 */
    private static final UUID GLOBAL_KEY = new UUID(0L, 0L);

    private final JavaPlugin plugin;
    private final StateStore store;
    private final Dispatcher dispatcher;
    private final Settings settings;
    private final File playersDir;
    private final File globalFile;
    private final NamespacedKey pdcKey;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FiguraController-IO");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<UUID, JsonObject> preloaded = new ConcurrentHashMap<>();
    private final Map<UUID, Long> due = new HashMap<>();
    private final Set<UUID> pdcDirty = new LinkedHashSet<>();

    Storage(JavaPlugin plugin, StateStore store, Dispatcher dispatcher, Settings settings) {
        this.plugin = plugin;
        this.store = store;
        this.dispatcher = dispatcher;
        this.settings = settings;
        this.playersDir = new File(plugin.getDataFolder(), "players");
        this.globalFile = new File(plugin.getDataFolder(), "global.json");
        this.pdcKey = new NamespacedKey(plugin, "states");
        playersDir.mkdirs();
    }

    File playerFile(UUID id) {
        return new File(playersDir, id + ".json");
    }

    int pendingSaves() {
        return due.size() + pdcDirty.size();
    }

    // ── 표시

    /** {@link StateStore.PersistListener} */
    void markDirty(UUID id) {
        long due0 = Bukkit.getCurrentTick() + settings.saveDelayTicks();
        if (id == null) {
            due.putIfAbsent(GLOBAL_KEY, due0);
            return;
        }
        Entity e = Bukkit.getEntity(id);
        if (e != null && !(e instanceof Player)) {
            pdcDirty.add(id);
        } else if (e instanceof Player || isKnownPlayer(id)) {
            due.putIfAbsent(id, due0);
        } else {
            debug("저장 못 하는 대상(접속한 적 없는 UUID · 내려간 몹) — 메모리에만: " + id);
        }
    }

    boolean isKnownPlayer(UUID id) {
        return playerFile(id).exists() || Bukkit.getOfflinePlayer(id).hasPlayedBefore();
    }

    // ── 틱

    void tick(long now) {
        if (!pdcDirty.isEmpty()) {
            for (UUID id : pdcDirty) writePdc(id, now);
            pdcDirty.clear();
        }
        if (!due.isEmpty()) {
            List<UUID> ready = new ArrayList<>();
            for (Iterator<Map.Entry<UUID, Long>> it = due.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Long> e = it.next();
                if (e.getValue() <= now) {
                    ready.add(e.getKey());
                    it.remove();
                }
            }
            for (UUID id : ready) save(id, now);
        }
    }

    // ── 플레이어 · 전역 파일

    /** 나갈 때 — 바로 쓰고 메모리에서 뺀다 */
    void quit(UUID id) {
        due.remove(id);
        save(id, Bukkit.getCurrentTick());
        if (store.subject(id) != null) store.evict(id);
        dispatcher.forget(id);
    }

    private void save(UUID key, long now) {
        if (key.equals(GLOBAL_KEY)) {
            JsonObject j = PersistCodec.write(store.global(), now);
            String text = j == null ? null : JsonText.pretty(j);
            io.execute(() -> writeOrDelete(globalFile.toPath(), text));
            return;
        }
        SubjectState s = store.subject(key);
        JsonObject j = s == null ? null : PersistCodec.write(s, now);
        String text = j == null ? null : JsonText.pretty(j);
        Path p = playerFile(key).toPath();
        io.execute(() -> writeOrDelete(p, text));
        // 접속 안 한 플레이어(오프라인에 `set` 한 것)는 쓰고 나면 메모리에서 뺀다
        if (s != null && Bukkit.getPlayer(key) == null && Bukkit.getEntity(key) == null) store.evict(key);
    }

    private void writeOrDelete(Path p, String text) {
        try {
            if (text == null) {
                Files.deleteIfExists(p);
                return;
            }
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "저장 실패: " + p, e);
        }
    }

    /** IO 스레드를 거쳐 읽는다(앞에 걸린 쓰기가 끝난 뒤) — 없으면 null */
    private JsonObject readViaIo(File f) {
        try {
            return io.submit(() -> readNow(f)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "읽기 실패: " + f, e);
            return null;
        }
    }

    private JsonObject readNow(File f) throws IOException {
        if (!f.isFile()) return null;
        String t = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        return JsonParser.parseString(t).getAsJsonObject();
    }

    /** `AsyncPlayerPreLoginEvent` (비동기 스레드) */
    void preload(UUID id) {
        JsonObject j = readViaIo(playerFile(id));
        if (j != null) preloaded.put(id, j);
    }

    /** 접속(메인) — 미리 읽은 것을 실는다(메모리에 먼저 `set` 된 이름은 안 덮는다) */
    void loadPlayer(UUID id) {
        JsonObject j = preloaded.remove(id);
        if (j == null) j = readViaIo(playerFile(id));
        install(id, j, false);
    }

    /** API 가 접속 안 한 플레이어를 건드리기 전 — 메모리에 없고 파일이 있으면 읽는다 */
    void ensureLoaded(UUID id) {
        if (id == null || store.subject(id) != null) return;
        if (Bukkit.getPlayer(id) != null || Bukkit.getEntity(id) != null) return;
        File f = playerFile(id);
        if (f.isFile()) install(id, readViaIo(f), false);
    }

    /** @return 파일이 있었나(없으면 첫 실행 — 프리셋 `default` 를 전역에) */
    boolean loadGlobal() {
        if (!globalFile.isFile()) return false;
        install(null, readViaIo(globalFile), true);
        return true;
    }

    private void install(UUID id, JsonObject j, boolean global) {
        if (j == null) return;
        List<String> skipped = new ArrayList<>();
        Map<String, Entry> m = PersistCodec.read(j, global, Bukkit.getCurrentTick(), skipped);
        store.install(id, m);
        if (!skipped.isEmpty()) plugin.getLogger().warning("저장 파일에서 못 읽은 상태 " + skipped + " — " + (id == null ? "전역" : id));
    }

    /** `clear all confirm` — 접속 안 한 플레이어 파일까지 */
    void deleteAllPlayerFiles() {
        File[] fs = playersDir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null) return;
        for (File f : fs) {
            UUID id;
            try {
                id = UUID.fromString(f.getName().substring(0, f.getName().length() - 5));
            } catch (IllegalArgumentException e) {
                continue;
            }
            due.remove(id);
            Path p = f.toPath();
            io.execute(() -> writeOrDelete(p, null));
        }
    }

    // ── 몹 PDC

    private void writePdc(UUID id, long now) {
        Entity e = Bukkit.getEntity(id);
        if (e == null || e instanceof Player) return;
        SubjectState s = store.subject(id);
        JsonObject j = s == null ? null : PersistCodec.write(s, now);
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        if (j == null) pdc.remove(pdcKey);
        else pdc.set(pdcKey, PersistentDataType.STRING, JsonText.compact(j));
    }

    /** `EntityAddToWorldEvent` · 켤 때 — PDC 에 있으면 실는다 */
    void loadEntity(Entity e) {
        String t = e.getPersistentDataContainer().get(pdcKey, PersistentDataType.STRING);
        if (t == null) return;
        try {
            install(e.getUniqueId(), JsonParser.parseString(t).getAsJsonObject(), false);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("몹 PDC 를 못 읽었다: " + e.getType() + " " + e.getUniqueId() + " — " + ex.getMessage());
        }
    }

    /** `EntityRemoveFromWorldEvent` — 이 틱에 바뀐 것이 있으면 마저 쓰고 메모리에서 뺀다 */
    void entityRemoved(Entity e) {
        UUID id = e.getUniqueId();
        if (pdcDirty.remove(id)) writePdc(id, Bukkit.getCurrentTick());
        if (store.subject(id) != null) store.evict(id);
        dispatcher.forget(id);
    }

    // ── 끌 때

    void flushAllSync() {
        long now = Bukkit.getCurrentTick();
        for (UUID id : pdcDirty) writePdc(id, now);
        pdcDirty.clear();
        Set<UUID> keys = new LinkedHashSet<>(due.keySet());
        due.clear();
        for (Player p : Bukkit.getOnlinePlayers()) keys.add(p.getUniqueId());
        keys.add(GLOBAL_KEY);
        for (UUID k : keys) save(k, now);
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().warning("저장이 15초 안에 안 끝났다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void debug(String m) {
        if (settings.debug()) plugin.getLogger().info("[debug] " + m);
    }
}
