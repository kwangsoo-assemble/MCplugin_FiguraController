package kr.asmbl.figuracontroller.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import kr.asmbl.figuracontroller.api.ChangeCause;
import kr.asmbl.figuracontroller.api.StateOptions;
import kr.asmbl.figuracontroller.protocol.Protocol;
import kr.asmbl.figuracontroller.protocol.Values;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * 상태의 유일한 주인(설계 §1 규칙 1) — Bukkit 을 모른다(시험할 수 있게). 대상 = 엔티티 UUID · 전역 = {@code null}.
 * <ul>
 *   <li>값 · 공개 범위가 그대로인 `set` 은 **아무것도 안 한다**(이벤트 · 전송 없음 · 저장 표시만 — 옛 CH 는 같아도 파일 쓰기 + 패킷이었다).</li>
 *   <li>바뀌면 {@link ChangeListener} 들에 알린다 — 보내기({@link Dispatcher}) · Bukkit 이벤트가 여기에 붙는다.</li>
 *   <li>저장할 것이 바뀌면 {@link PersistListener} — 디바운스 · 파일 · PDC 는 밖에서.</li>
 *   <li>빈 대상은 지운다(메모리) — 다시 넣으면 다시 생긴다.</li>
 * </ul>
 */
public final class StateStore {
    public interface ChangeListener {
        /** {@code old} · {@code now} 중 하나는 null 일 수 있다(새로 생김 · 지워짐) */
        void onChange(UUID subject, String name, Entry old, Entry now, ChangeCause cause);
    }

    public interface PersistListener {
        void onPersistDirty(UUID subject);
    }

    /** 이름 길이 상한(UTF-8 바이트) */
    public static final int MAX_NAME = 256;

    private record Expiry(long tick, UUID subject, String name, Entry entry) {}

    private final Map<UUID, SubjectState> subjects = new LinkedHashMap<>();
    private final SubjectState global = new SubjectState(null);
    private final PriorityQueue<Expiry> expiries = new PriorityQueue<>(Comparator.comparingLong(Expiry::tick));
    private final List<ChangeListener> listeners = new ArrayList<>();
    private PersistListener persistListener = s -> {};

    public void addListener(ChangeListener l) {
        listeners.add(l);
    }

    public void setPersistListener(PersistListener l) {
        persistListener = l;
    }

    /** 전역이면 {@code id == null} · 없으면 null */
    public SubjectState subject(UUID id) {
        return id == null ? global : subjects.get(id);
    }

    public SubjectState global() {
        return global;
    }

    /** 메모리의 대상들(전역 제외) */
    public Collection<SubjectState> subjects() {
        return Collections.unmodifiableCollection(subjects.values());
    }

    public static void validateName(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("이름이 비었다");
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME) throw new IllegalArgumentException("이름이 너무 길다(" + MAX_NAME + " 바이트)");
    }

    /** 값을 Entry 로 — 인코딩 · 크기 검사(넘으면 {@link IllegalArgumentException}) */
    public static Entry entry(JsonElement value, StateOptions o, boolean global, long nowTick) {
        JsonElement v = value == null ? JsonNull.INSTANCE : value.deepCopy();
        byte[] enc;
        try {
            enc = Values.encode(v);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("값을 실을 수 없다: " + e.getMessage(), e);
        }
        if (enc.length > Protocol.MAX_VALUE) throw new IllegalArgumentException("값이 너무 크다(" + enc.length + " > " + Protocol.MAX_VALUE + " 바이트)");
        long exp = o.expireTicks() > 0 ? nowTick + o.expireTicks() : -1;
        return new Entry(v, enc, o.persist(), !global && o.privateState(), exp);
    }

    /** @return 값이나 공개 범위가 바뀌었나 */
    public boolean set(UUID id, String name, JsonElement value, StateOptions o, long nowTick, ChangeCause cause) {
        validateName(name);
        Entry now = entry(value, o, id == null, nowTick);
        SubjectState s = id == null ? global : subjects.computeIfAbsent(id, SubjectState::new);
        Entry old = s.entries.put(name, now);
        if (now.expireTick >= 0) expiries.add(new Expiry(now.expireTick, id, name, now));
        if (old != null && old.sameValue(now) && old.priv == now.priv) {
            // 보낼 것 없음 — 저장에 걸리는 속성(persist · 만료)이 달라졌을 때만 저장 표시
            if ((old.persist || now.persist) && (old.persist != now.persist || old.expireTick != now.expireTick)) persistListener.onPersistDirty(id);
            return false;
        }
        if ((old != null && old.persist) || now.persist) persistListener.onPersistDirty(id);
        fire(id, name, old, now, cause);
        return true;
    }

    public boolean unset(UUID id, String name, ChangeCause cause) {
        SubjectState s = subject(id);
        if (s == null) return false;
        Entry old = s.entries.remove(name);
        if (old == null) return false;
        if (id != null && s.entries.isEmpty()) subjects.remove(id);
        if (old.persist) persistListener.onPersistDirty(id);
        fire(id, name, old, null, cause);
        return true;
    }

    /** @return 지운 개수 */
    public int clear(UUID id, ChangeCause cause) {
        SubjectState s = subject(id);
        if (s == null) return 0;
        int n = 0;
        for (String name : new ArrayList<>(s.entries.keySet())) if (unset(id, name, cause)) n++;
        return n;
    }

    /** 전역 + 메모리의 모든 대상 */
    public int clearAll(ChangeCause cause) {
        int n = clear(null, cause);
        for (UUID id : new ArrayList<>(subjects.keySet())) n += clear(id, cause);
        return n;
    }

    /** 만료된 것을 지운다 — 그 사이 다시 `set` 된 것(Entry 가 바뀐 것)은 안 건드린다 */
    public int expire(long nowTick) {
        int n = 0;
        while (!expiries.isEmpty() && expiries.peek().tick() <= nowTick) {
            Expiry e = expiries.poll();
            SubjectState s = subject(e.subject());
            if (s != null && s.entries.get(e.name()) == e.entry() && unset(e.subject(), e.name(), ChangeCause.EXPIRE)) n++;
        }
        return n;
    }

    /**
     * 저장된 것을 실는다(파일 · PDC) — 이벤트 · 저장 표시 없음. **메모리에 이미 있는 이름은 안 덮는다**(메모리가 더 새것이다:
     * 접속 직전 · 엔티티 로드 직전에 `set` 된 것).
     */
    public void install(UUID id, Map<String, Entry> loaded) {
        if (loaded.isEmpty()) return;
        SubjectState s = id == null ? global : subjects.computeIfAbsent(id, SubjectState::new);
        for (Map.Entry<String, Entry> e : loaded.entrySet()) {
            if (s.entries.containsKey(e.getKey())) continue;
            s.entries.put(e.getKey(), e.getValue());
            if (e.getValue().expireTick >= 0) expiries.add(new Expiry(e.getValue().expireTick, id, e.getKey(), e.getValue()));
        }
    }

    /** 메모리에서 뺀다(나간 플레이어 · 사라진 몹) — 상태 «변화» 가 아니므로 이벤트 없음 */
    public SubjectState evict(UUID id) {
        return id == null ? null : subjects.remove(id);
    }

    private void fire(UUID id, String name, Entry old, Entry now, ChangeCause cause) {
        for (ChangeListener l : listeners) l.onChange(id, name, old, now, cause);
    }
}
