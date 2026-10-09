package kr.asmbl.figuracontroller.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import kr.asmbl.figuracontroller.api.Audience;
import kr.asmbl.figuracontroller.api.ChangeCause;
import kr.asmbl.figuracontroller.protocol.Protocol;
import kr.asmbl.figuracontroller.protocol.S2CBuilder;
import kr.asmbl.figuracontroller.protocol.Values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 틱 동안 쌓인 것(변화 · 트래킹 · 신호 · HELLO)을 **틱 끝에 받는 클라마다 패킷 묶음 하나로** 만든다(설계 §4.2 · §2.4) — Bukkit 을 모른다.
 * <p>
 * 받는 사람 — 플레이어 P 의 공개 상태: P 를 보는 클라 + P 자신 · 비공개: P 자신만 · 몹 M 의 공개: M 을 보는 클라 · 비공개: 아무도 ·
 * 전역: 준비된 모든 클라. «준비됨» = 코어가 HELLO 를 보냈다(FSB 연결 전에 보낸 것은 조용히 버려진다 — research §2 ①).
 * <p>
 * 한 틱 안의 순서(받는 클라마다): ① HELLO 초기화(RESET · 전역 FULL · 자기 FULL · 보는 대상 FULL) ② 트래킹 시작 FULL · 끝 DROP
 * ③ 변화 델타(전역 → 대상) ④ 신호(부른 순서). 그 틱에 FULL 을 받은 (클라, 대상)은 ③ 을 건너뛴다(FULL 이 이미 최신이다).
 * 같은 틱에 같은 상태가 여러 번 바뀌면 **마지막 값 하나**(델타는 플러시 때 저장소의 지금 값을 읽는다).
 */
public final class Dispatcher implements StateStore.ChangeListener {

    /** 받는 클라 하나 — 이름 사전 · 이미 보낸 대상 */
    static final class Session {
        final UUID player;
        final Map<String, Integer> names = new HashMap<>();
        int nextName;
        final Set<UUID> known = new HashSet<>();

        Session(UUID player) {
            this.player = player;
        }
    }

    /** 그 틱 첫 변화 «전» 의 모습 — 지금 모습은 플러시 때 저장소에서 읽는다 */
    private record Dirty(boolean oldExisted, boolean oldPriv) {}

    private record Track(UUID subject, boolean start) {}

    private record Signal(UUID subject, String name, byte[] data, Audience audience) {}

    /** 클라 하나의 이번 틱 출력 */
    private static final class Out {
        final S2CBuilder b = new S2CBuilder();
        final Set<UUID> covered = new HashSet<>();
        boolean globalCovered;
    }

    private final StateStore store;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Set<UUID> pendingInit = new LinkedHashSet<>();
    private final Map<UUID, List<Track>> tracks = new LinkedHashMap<>();
    private final LinkedHashMap<String, Dirty> globalDirty = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashMap<String, Dirty>> dirty = new LinkedHashMap<>();
    private final List<Signal> signals = new ArrayList<>();

    public Dispatcher(StateStore store) {
        this.store = store;
    }

    // ── 세션

    /** 코어가 HELLO 를 보냈다 · @return 규약판이 맞아 받아들였나 */
    public boolean hello(UUID player, int version) {
        if (version != Protocol.VERSION) return false;
        sessions.put(player, new Session(player));
        pendingInit.add(player);
        tracks.remove(player);
        return true;
    }

    public void quit(UUID player) {
        sessions.remove(player);
        pendingInit.remove(player);
        tracks.remove(player);
    }

    public boolean isReady(UUID player) {
        return sessions.containsKey(player);
    }

    public Set<UUID> ready() {
        return java.util.Collections.unmodifiableSet(sessions.keySet());
    }

    /**
     * 그만 볼 때 DROP 이 필요할 수 있나 — 이 클라에 이 대상을 보냈거나, **이번 틱에 보기 시작이 걸려 있다**(틱 끝에 FULL 이 나간다).
     * ★ 뒤의 것이 없으면 «같은 틱에 보기 시작 → 끝» 에서 끝이 걸러져 FULL 만 나가고 클라에 남는다(2026-10-10 무작위 이어 붙이기 시험 실측).
     */
    public boolean knows(UUID player, UUID subject) {
        Session s = sessions.get(player);
        if (s == null) return false;
        if (s.known.contains(subject)) return true;
        List<Track> l = tracks.get(player);
        if (l != null) for (Track t : l) if (t.start() && t.subject().equals(subject)) return true;
        return false;
    }

    // ── 들어오는 것

    public void trackStart(UUID player, UUID subject) {
        if (!sessions.containsKey(player) || pendingInit.contains(player)) return;
        tracks.computeIfAbsent(player, k -> new ArrayList<>()).add(new Track(subject, true));
    }

    public void trackStop(UUID player, UUID subject) {
        if (!sessions.containsKey(player) || pendingInit.contains(player)) return;
        tracks.computeIfAbsent(player, k -> new ArrayList<>()).add(new Track(subject, false));
    }

    /** 신호 — 저장 안 함 · 틱 끝에(같은 틱의 상태 변화 뒤에) 간다. 대상 null = 전역(늘 전원 — {@link Audience#players} 만 예외) */
    public void signal(UUID subject, String name, JsonElement data, Audience audience) {
        StateStore.validateName(name);
        byte[] enc = Values.encode(data == null ? JsonNull.INSTANCE : data);
        if (enc.length > Protocol.MAX_VALUE) throw new IllegalArgumentException("신호 데이터가 너무 크다(" + enc.length + " 바이트)");
        signals.add(new Signal(subject, name, enc, audience));
    }

    @Override
    public void onChange(UUID subject, String name, Entry old, Entry now, ChangeCause cause) {
        LinkedHashMap<String, Dirty> m = subject == null ? globalDirty : dirty.computeIfAbsent(subject, k -> new LinkedHashMap<>());
        m.putIfAbsent(name, new Dirty(old != null, old != null && old.priv));
    }

    /** 메모리에서 빠진 대상(사라진 몹) — 쌓인 델타를 버린다(보는 클라에는 트래킹 끝 DROP 이 간다) */
    public void forget(UUID subject) {
        dirty.remove(subject);
    }

    // ── 틱 끝

    /** @return 받는 클라 → 패킷 몸들(빈 클라는 없다) */
    public Map<UUID, List<byte[]>> flush(WorldView view) {
        Map<UUID, Out> outs = new LinkedHashMap<>();
        Map<UUID, Collection<UUID>> trackerCache = new HashMap<>();

        // ① HELLO 초기화
        for (UUID r : pendingInit) {
            Session ses = sessions.get(r);
            if (ses == null) continue;
            Out o = out(outs, r);
            o.b.reset();
            ses.names.clear();
            ses.nextName = 0;
            ses.known.clear();
            writeFull(ses, o, store.global(), view);
            o.globalCovered = true;
            for (SubjectState s : store.subjects()) {
                if (r.equals(s.id) || trackers(view, trackerCache, s.id).contains(r)) {
                    writeFull(ses, o, s, view);
                    o.covered.add(s.id);
                }
            }
        }

        // ② 트래킹 시작 · 끝 (부른 순서)
        for (Map.Entry<UUID, List<Track>> e : tracks.entrySet()) {
            Session ses = sessions.get(e.getKey());
            if (ses == null) continue;
            for (Track t : e.getValue()) {
                Out o = out(outs, ses.player);
                if (t.start()) {
                    SubjectState s = store.subject(t.subject());
                    if (s != null) writeFull(ses, o, s, view);
                } else if (ses.known.remove(t.subject())) {
                    o.b.subject(t.subject());
                    o.b.drop();
                }
                o.covered.add(t.subject());
            }
        }

        // ③-a 전역 델타
        if (!globalDirty.isEmpty()) {
            for (Session ses : sessions.values()) {
                Out o = outs.get(ses.player);
                if (o != null && o.globalCovered) continue;
                boolean ctx = false;
                for (Map.Entry<String, Dirty> d : globalDirty.entrySet()) {
                    Entry now = store.global().get(d.getKey());
                    if (now == null && !ses.names.containsKey(d.getKey())) continue;
                    if (o == null) o = out(outs, ses.player);
                    if (now != null) {
                        int id = nameId(ses, o, d.getKey());
                        if (!ctx) {
                            o.b.global();
                            ctx = true;
                        }
                        o.b.set(id, now.encoded);
                    } else if (d.getValue().oldExisted()) {
                        if (!ctx) {
                            o.b.global();
                            ctx = true;
                        }
                        o.b.unset(ses.names.get(d.getKey()));
                    }
                }
            }
        }

        // ③-b 대상 델타
        for (Map.Entry<UUID, LinkedHashMap<String, Dirty>> e : dirty.entrySet()) {
            UUID sid = e.getKey();
            boolean isPlayer = view.isPlayer(sid);
            SubjectState s = store.subject(sid);
            for (UUID r : receivers(view, trackerCache, sid, isPlayer)) {
                Session ses = sessions.get(r);
                Out o = outs.get(r);
                if (o != null && o.covered.contains(sid)) continue;
                boolean ctx = false;
                for (Map.Entry<String, Dirty> d : e.getValue().entrySet()) {
                    Entry now = s == null ? null : s.get(d.getKey());
                    boolean visNow = now != null && visible(now.priv, isPlayer, r, sid);
                    boolean visBefore = d.getValue().oldExisted() && visible(d.getValue().oldPriv(), isPlayer, r, sid);
                    if (visNow) {
                        if (o == null) o = out(outs, r);
                        int id = nameId(ses, o, d.getKey());
                        if (!ctx) {
                            o.b.subject(sid);
                            ctx = true;
                        }
                        o.b.set(id, now.encoded);
                        ses.known.add(sid);
                    } else if (visBefore && ses.names.containsKey(d.getKey())) {
                        if (o == null) o = out(outs, r);
                        if (!ctx) {
                            o.b.subject(sid);
                            ctx = true;
                        }
                        o.b.unset(ses.names.get(d.getKey()));
                    }
                }
            }
        }

        // ④ 신호
        for (Signal sig : signals) {
            Collection<UUID> to = signalReceivers(view, trackerCache, sig);
            for (UUID r : to) {
                Session ses = sessions.get(r);
                if (ses == null) continue;
                Out o = out(outs, r);
                int id = nameId(ses, o, sig.name());
                if (sig.subject() == null) o.b.global();
                else o.b.subject(sig.subject());
                o.b.signal(id, sig.data());
            }
        }

        pendingInit.clear();
        tracks.clear();
        globalDirty.clear();
        dirty.clear();
        signals.clear();

        Map<UUID, List<byte[]>> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, Out> e : outs.entrySet()) {
            List<byte[]> packets = e.getValue().b.build();
            if (!packets.isEmpty()) result.put(e.getKey(), packets);
        }
        return result;
    }

    // ── 도움

    private static Out out(Map<UUID, Out> outs, UUID r) {
        return outs.computeIfAbsent(r, k -> new Out());
    }

    /** 비공개 — 플레이어면 본인만 · 몹이면 아무도 */
    private static boolean visible(boolean priv, boolean subjectIsPlayer, UUID receiver, UUID subject) {
        return !priv || (subjectIsPlayer && receiver.equals(subject));
    }

    private static Collection<UUID> trackers(WorldView view, Map<UUID, Collection<UUID>> cache, UUID sid) {
        return cache.computeIfAbsent(sid, view::trackers);
    }

    /** 보는 클라(준비된) + 본인(준비된 플레이어) */
    private List<UUID> receivers(WorldView view, Map<UUID, Collection<UUID>> cache, UUID sid, boolean isPlayer) {
        List<UUID> out = new ArrayList<>();
        if (isPlayer && sessions.containsKey(sid)) out.add(sid);
        for (UUID t : trackers(view, cache, sid)) if (sessions.containsKey(t) && !t.equals(sid)) out.add(t);
        return out;
    }

    private Collection<UUID> signalReceivers(WorldView view, Map<UUID, Collection<UUID>> cache, Signal sig) {
        Audience a = sig.audience();
        if (a.kind() == Audience.Kind.PLAYERS) return a.playerSet();
        if (sig.subject() == null || a.kind() == Audience.Kind.ALL) return sessions.keySet();
        boolean isPlayer = view.isPlayer(sig.subject());
        if (a.kind() == Audience.Kind.SELF) return isPlayer && sessions.containsKey(sig.subject()) ? List.of(sig.subject()) : List.of();
        return receivers(view, cache, sig.subject(), isPlayer);
    }

    /** 이 클라 세션의 이름 번호 — 처음이면 NAME op 를 먼저 쓴다 */
    private static int nameId(Session ses, Out o, String name) {
        Integer id = ses.names.get(name);
        if (id != null) return id;
        int n = ses.nextName++;
        ses.names.put(name, n);
        o.b.name(n, name);
        return n;
    }

    /** 이 클라가 볼 수 있는 상태만으로 FULL — 하나도 없으면 안 쓴다(클라에 이 대상이 없는 것과 같다) */
    private void writeFull(Session ses, Out o, SubjectState s, WorldView view) {
        boolean isPlayer = !s.isGlobal() && view.isPlayer(s.id);
        List<String> names = new ArrayList<>();
        List<byte[]> vals = new ArrayList<>();
        for (Map.Entry<String, Entry> e : s.entries.entrySet()) {
            if (s.isGlobal() || visible(e.getValue().priv, isPlayer, ses.player, s.id)) {
                names.add(e.getKey());
                vals.add(e.getValue().encoded);
            }
        }
        if (names.isEmpty()) {
            // 이미 보낸 대상인데 지금 볼 것이 없으면 비운다
            if (!s.isGlobal() && ses.known.remove(s.id)) {
                o.b.subject(s.id);
                o.b.drop();
            }
            return;
        }
        int[] ids = new int[names.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = nameId(ses, o, names.get(i));
        if (s.isGlobal()) o.b.global();
        else o.b.subject(s.id);
        o.b.full(ids, vals.toArray(new byte[0][]));
        if (!s.isGlobal()) ses.known.add(s.id);
    }
}
