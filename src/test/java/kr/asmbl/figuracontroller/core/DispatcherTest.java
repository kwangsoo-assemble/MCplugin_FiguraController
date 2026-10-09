package kr.asmbl.figuracontroller.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import kr.asmbl.figuracontroller.api.Audience;
import kr.asmbl.figuracontroller.api.ChangeCause;
import kr.asmbl.figuracontroller.api.StateOptions;
import kr.asmbl.figuracontroller.protocol.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 설계 §8 «플러그인(JUnit)» — 받는 사람 · 같은 값 · 마지막 값 · 비공개 · 전역 N 패킷 · 트래킹 · 이름 사전 · 신호 */
class DispatcherTest {
    static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID P3 = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    static final UUID M1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final StateOptions PRIV = StateOptions.DEFAULT.withPrivate(true);

    /** 가짜 세상 — 누가 누구를 보나 · 누가 플레이어인가 */
    static final class FakeView implements WorldView {
        final Map<UUID, Set<UUID>> trackers = new HashMap<>();
        final Set<UUID> players = new HashSet<>();

        void see(UUID viewer, UUID subject) {
            trackers.computeIfAbsent(subject, k -> new HashSet<>()).add(viewer);
        }

        void unsee(UUID viewer, UUID subject) {
            trackers.getOrDefault(subject, new HashSet<>()).remove(viewer);
        }

        @Override
        public Collection<UUID> trackers(UUID subject) {
            return trackers.getOrDefault(subject, Set.of());
        }

        @Override
        public boolean isPlayer(UUID subject) {
            return players.contains(subject);
        }
    }

    StateStore store;
    Dispatcher d;
    FakeView view;
    Wire wire;
    long tick;

    @BeforeEach
    void setUp() {
        store = new StateStore();
        d = new Dispatcher(store);
        store.addListener(d);
        view = new FakeView();
        view.players.addAll(List.of(P1, P2, P3));
        wire = new Wire().label(P1, "P1").label(P2, "P2").label(P3, "P3").label(M1, "M1");
    }

    void set(UUID s, String n, String json) {
        set(s, n, json, StateOptions.DEFAULT);
    }

    void set(UUID s, String n, String json, StateOptions o) {
        store.set(s, n, JsonParser.parseString(json), o, tick, ChangeCause.API);
    }

    /** 받은 클라마다 이름 사전을 따라간다(클라처럼) — 시험이 다시 읽어도 같다 */
    Map<UUID, List<byte[]>> flush() {
        tick++;
        Map<UUID, List<byte[]>> out = d.flush(view);
        for (UUID r : out.keySet()) wire.read(out, r);
        return out;
    }

    /** P1 · P2 준비 · P2 가 P1 과 M1 을 · P1 이 M1 을 본다 */
    Map<UUID, List<byte[]>> standardInit() {
        set(null, "showNametag", "true");
        set(P1, "skin", "\"A\"");
        set(P1, "secret", "1", PRIV);
        set(M1, "hp", "3");
        set(M1, "hidden", "1", PRIV);
        view.see(P2, P1);
        view.see(P2, M1);
        view.see(P1, M1);
        d.hello(P1, Protocol.VERSION);
        d.hello(P2, Protocol.VERSION);
        return flush();
    }

    @Test
    void helloInitSendsResetGlobalSelfAndTracked() {
        Map<UUID, List<byte[]>> out = standardInit();
        assertEquals(List.of("RESET", "GLOBAL", "FULL {showNametag=true}", "SUBJECT P1", "FULL {skin=\"A\",secret=1}", "SUBJECT M1", "FULL {hp=3}"),
                wire.read(out, P1));
        assertEquals(List.of("RESET", "GLOBAL", "FULL {showNametag=true}", "SUBJECT P1", "FULL {skin=\"A\"}", "SUBJECT M1", "FULL {hp=3}"),
                wire.read(out, P2), "남에게는 비공개(secret)가 안 가고 몹 비공개(hidden)는 아무에게도 안 간다");
    }

    @Test
    void sameValueSendsNothing() {
        standardInit();
        set(P1, "skin", "\"A\"");
        set(null, "showNametag", "true");
        assertTrue(flush().isEmpty());
    }

    @Test
    void intAndDoubleAreDifferent() {
        standardInit();
        set(M1, "hp", "3.0");
        Map<UUID, List<byte[]>> out = flush();
        assertEquals(List.of("SUBJECT M1", "SET hp=3.0"), wire.read(out, P1));
    }

    @Test
    void lastValueWinsWithinTick() {
        standardInit();
        set(P1, "skin", "\"B\"");
        set(P1, "skin", "\"C\"");
        set(P1, "skin", "\"D\"");
        Map<UUID, List<byte[]>> out = flush();
        assertEquals(List.of("SUBJECT P1", "SET skin=\"D\""), wire.read(out, P1));
        assertEquals(List.of("SUBJECT P1", "SET skin=\"D\""), wire.read(out, P2));
        assertEquals(1, out.get(P1).size());
    }

    @Test
    void privateGoesOnlyToSelf() {
        standardInit();
        set(P1, "secret", "2", PRIV);
        Map<UUID, List<byte[]>> out = flush();
        assertEquals(List.of("SUBJECT P1", "SET secret=2"), wire.read(out, P1));
        assertFalse(out.containsKey(P2));
    }

    @Test
    void publicToPrivateUnsetsForOthers() {
        standardInit();
        set(P1, "skin", "\"E\"", PRIV);
        Map<UUID, List<byte[]>> out = flush();
        assertEquals(List.of("SUBJECT P1", "SET skin=\"E\""), wire.read(out, P1));
        assertEquals(List.of("SUBJECT P1", "UNSET skin"), wire.read(out, P2));
        // 다시 공개 — 남에게 다시 간다
        set(P1, "skin", "\"F\"");
        out = flush();
        assertEquals(List.of("SUBJECT P1", "SET skin=\"F\""), wire.read(out, P2));
    }

    @Test
    void mobPrivateGoesNowhere() {
        standardInit();
        set(M1, "hidden", "2", PRIV);
        assertTrue(flush().isEmpty());
    }

    @Test
    void unsetPublicGoesToAllReceivers() {
        standardInit();
        store.unset(P1, "skin", ChangeCause.API);
        Map<UUID, List<byte[]>> out = flush();
        assertEquals(List.of("SUBJECT P1", "UNSET skin"), wire.read(out, P1));
        assertEquals(List.of("SUBJECT P1", "UNSET skin"), wire.read(out, P2));
    }

    @Test
    void createdAndRemovedInSameTickSendsNothing() {
        standardInit();
        set(P1, "flash", "true");
        store.unset(P1, "flash", ChangeCause.API);
        assertTrue(flush().isEmpty());
    }

    @Test
    void globalIsOnePacketPerReadyClient() {
        standardInit();
        d.hello(P3, Protocol.VERSION);
        flush();
        set(null, "phase", "2");
        set(null, "timer", "30");
        Map<UUID, List<byte[]>> out = flush();
        for (UUID r : List.of(P1, P2, P3)) {
            assertEquals(1, out.get(r).size(), "전역 바뀜 — 클라마다 패킷 하나");
            assertEquals(List.of("GLOBAL", "SET phase=2", "SET timer=30"), wire.read(out, r));
        }
        assertEquals(3, out.size());
    }

    @Test
    void notReadyGetsNothing() {
        standardInit();
        view.see(P3, P1);
        set(P1, "skin", "\"Z\"");
        Map<UUID, List<byte[]>> out = flush();
        assertFalse(out.containsKey(P3), "HELLO 전에는 아무것도 안 보낸다");
    }

    @Test
    void trackStartSendsFullAndStopSendsDrop() {
        standardInit();
        d.hello(P3, Protocol.VERSION);
        flush();
        view.see(P3, M1);
        d.trackStart(P3, M1);
        Map<UUID, List<byte[]>> out = flush();
        assertEquals(List.of("SUBJECT M1", "FULL {hp=3}"), wire.read(out, P3));
        assertTrue(d.knows(P3, M1));
        view.unsee(P3, M1);
        d.trackStop(P3, M1);
        out = flush();
        assertEquals(List.of("SUBJECT M1", "DROP"), wire.read(out, P3));
        assertFalse(d.knows(P3, M1));
        // 이제 M1 이 바뀌어도 P3 에는 안 간다
        set(M1, "hp", "1");
        out = flush();
        assertFalse(out.containsKey(P3));
    }

    /** ★ 회귀 — 같은 틱에 보기 시작 → 끝이면 클라에 아무것도 안 남는다(무작위 이어 붙이기 시험이 찾았다) */
    @Test
    void trackStartThenStopInSameTickLeavesNothing() {
        standardInit();
        d.hello(P3, Protocol.VERSION);
        flush();
        view.see(P3, M1);
        d.trackStart(P3, M1);
        view.unsee(P3, M1);
        assertTrue(d.knows(P3, M1), "이번 틱에 시작이 걸려 있으면 DROP 이 필요할 수 있다");
        d.trackStop(P3, M1);
        Map<UUID, List<byte[]>> out = flush();
        List<String> lines = wire.read(out, P3);
        assertEquals("DROP", lines.get(lines.size() - 1), "FULL 뒤에 DROP — " + lines);
        assertFalse(d.knows(P3, M1));
    }

    @Test
    void trackStartSameTickAsChangeSendsFullOnly() {
        standardInit();
        d.hello(P3, Protocol.VERSION);
        flush();
        set(M1, "hp", "9");
        view.see(P3, M1);
        d.trackStart(P3, M1);
        Map<UUID, List<byte[]>> out = flush();
        assertEquals(List.of("SUBJECT M1", "FULL {hp=9}"), wire.read(out, P3), "FULL 이 최신이라 델타는 건너뛴다");
    }

    @Test
    void nameDictionaryOncePerSession() {
        Map<UUID, List<byte[]>> out = standardInit();
        assertEquals(4, Wire.names(out, P1)); // showNametag · skin · secret · hp
        set(P1, "skin", "\"Q\"");
        out = flush();
        assertEquals(0, Wire.names(out, P1), "이미 알린 이름은 번호만");
        d.hello(P1, Protocol.VERSION);
        out = flush();
        assertEquals("RESET", wire.read(out, P1).get(0));
        assertEquals(4, Wire.names(out, P1), "다시 HELLO — 사전을 처음부터");
    }

    @Test
    void signalsByAudience() {
        standardInit();
        d.hello(P3, Protocol.VERSION);
        flush();
        JsonElement data = new JsonPrimitive(5);
        d.signal(P1, "jump", data, Audience.TRACKERS);
        Map<UUID, List<byte[]>> out = flush();
        assertEquals(List.of("SUBJECT P1", "SIGNAL jump=5"), wire.read(out, P1));
        assertEquals(List.of("SUBJECT P1", "SIGNAL jump=5"), wire.read(out, P2));
        assertFalse(out.containsKey(P3), "P3 는 P1 을 안 본다");

        d.signal(P1, "only", JsonNull.INSTANCE, Audience.SELF);
        out = flush();
        assertEquals(Set.of(P1), out.keySet());

        d.signal(P1, "shake", data, Audience.ALL);
        out = flush();
        assertEquals(Set.of(P1, P2, P3), out.keySet());

        d.signal(null, "flash", data, Audience.TRACKERS);
        out = flush();
        assertEquals(Set.of(P1, P2, P3), out.keySet(), "전역 신호는 늘 전원");
        assertEquals(List.of("GLOBAL", "SIGNAL flash=5"), wire.read(out, P3));

        d.signal(M1, "roar", data, Audience.SELF);
        assertTrue(flush().isEmpty(), "몹의 SELF 는 아무도 아니다");
    }

    @Test
    void signalComesAfterStateInSameTick() {
        standardInit();
        d.signal(P1, "pose", JsonNull.INSTANCE, Audience.TRACKERS);
        set(P1, "skin", "\"S\"");
        Map<UUID, List<byte[]>> out = flush();
        assertEquals(List.of("SUBJECT P1", "SET skin=\"S\"", "SUBJECT P1", "SIGNAL pose=null"), wire.read(out, P2));
    }

    @Test
    void quitRemovesSession() {
        standardInit();
        d.quit(P2);
        set(P1, "skin", "\"X\"");
        Map<UUID, List<byte[]>> out = flush();
        assertFalse(out.containsKey(P2));
        assertFalse(d.isReady(P2));
    }

    @Test
    void wrongVersionIsRefused() {
        assertFalse(d.hello(P1, Protocol.VERSION + 1));
        assertFalse(d.isReady(P1));
    }

    @Test
    void bigSubjectSplitsAcrossPackets() {
        standardInit();
        String big = "\"" + "x".repeat(9000) + "\"";
        for (int i = 0; i < 5; i++) set(M1, "k" + i, big);
        Map<UUID, List<byte[]>> out = flush();
        assertTrue(out.get(P2).size() >= 2);
        for (byte[] p : out.get(P2)) assertTrue(p.length <= Protocol.MAX_BODY);
        long sets = wire.read(out, P2).stream().filter(l -> l.startsWith("SET k")).count();
        assertEquals(5, sets);
    }
}
