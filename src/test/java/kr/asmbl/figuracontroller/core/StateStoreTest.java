package kr.asmbl.figuracontroller.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import kr.asmbl.figuracontroller.api.ChangeCause;
import kr.asmbl.figuracontroller.api.StateOptions;
import kr.asmbl.figuracontroller.protocol.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    StateStore store;
    List<String> events;
    List<UUID> persistDirty;

    @BeforeEach
    void setUp() {
        store = new StateStore();
        events = new ArrayList<>();
        persistDirty = new ArrayList<>();
        store.addListener((s, n, o, now, c) -> events.add(n + ":" + (o == null ? "-" : o.value) + "→" + (now == null ? "-" : now.value) + ":" + c));
        store.setPersistListener(persistDirty::add);
    }

    @Test
    void sameValueIsNoop() {
        assertTrue(store.set(A, "skin", new JsonPrimitive("R"), StateOptions.DEFAULT, 0, ChangeCause.API));
        assertFalse(store.set(A, "skin", new JsonPrimitive("R"), StateOptions.DEFAULT, 0, ChangeCause.API));
        assertEquals(1, events.size());
        assertEquals(1, persistDirty.size());
    }

    @Test
    void oneAndOnePointZeroDiffer() {
        store.set(A, "n", JsonParser.parseString("1"), StateOptions.DEFAULT, 0, ChangeCause.API);
        assertTrue(store.set(A, "n", JsonParser.parseString("1.0"), StateOptions.DEFAULT, 0, ChangeCause.API));
    }

    @Test
    void visibilityChangeIsAChange() {
        store.set(A, "s", new JsonPrimitive(1), StateOptions.DEFAULT, 0, ChangeCause.API);
        assertTrue(store.set(A, "s", new JsonPrimitive(1), StateOptions.DEFAULT.withPrivate(true), 0, ChangeCause.API));
    }

    @Test
    void persistOnlyChangeMarksSaveButNoEvent() {
        store.set(A, "s", new JsonPrimitive(1), StateOptions.DEFAULT.withPersist(false), 0, ChangeCause.API);
        assertEquals(0, persistDirty.size(), "저장 안 하는 상태는 저장 표시가 없다");
        assertFalse(store.set(A, "s", new JsonPrimitive(1), StateOptions.DEFAULT, 0, ChangeCause.API));
        assertEquals(1, events.size());
        assertEquals(1, persistDirty.size());
    }

    @Test
    void storesACopy() {
        JsonObject o = new JsonObject();
        o.addProperty("x", 1);
        store.set(A, "obj", o, StateOptions.DEFAULT, 0, ChangeCause.API);
        o.addProperty("x", 2);
        assertEquals(1, store.subject(A).get("obj").value.getAsJsonObject().get("x").getAsInt());
    }

    @Test
    void expiry() {
        store.set(A, "buff", new JsonPrimitive(true), StateOptions.DEFAULT.withExpireTicks(5), 100, ChangeCause.API);
        assertEquals(0, store.expire(104));
        assertEquals(1, store.expire(105));
        assertNull(store.subject(A), "빈 대상은 메모리에서 빠진다");
        assertTrue(events.get(events.size() - 1).endsWith(":EXPIRE"));
    }

    @Test
    void resetBeforeExpiryKeepsTheNewOne() {
        store.set(A, "buff", new JsonPrimitive(true), StateOptions.DEFAULT.withExpireTicks(5), 100, ChangeCause.API);
        store.set(A, "buff", new JsonPrimitive(true), StateOptions.DEFAULT, 101, ChangeCause.API);
        assertEquals(0, store.expire(200));
        assertNotNull(store.subject(A).get("buff"));
    }

    @Test
    void tooBigIsRejected() {
        JsonPrimitive big = new JsonPrimitive("z".repeat(Protocol.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> store.set(A, "big", big, StateOptions.DEFAULT, 0, ChangeCause.API));
        assertNull(store.subject(A));
    }

    @Test
    void globalIgnoresPrivate() {
        store.set(null, "g", new JsonPrimitive(1), StateOptions.DEFAULT.withPrivate(true), 0, ChangeCause.API);
        assertFalse(store.global().get("g").priv);
    }

    @Test
    void installKeepsNewerMemory() {
        store.set(A, "skin", new JsonPrimitive("memory"), StateOptions.DEFAULT, 0, ChangeCause.API);
        Entry disk1 = StateStore.entry(new JsonPrimitive("disk"), StateOptions.DEFAULT, false, 0);
        Entry disk2 = StateStore.entry(new JsonPrimitive(7), StateOptions.DEFAULT, false, 0);
        store.install(A, Map.of("skin", disk1, "other", disk2));
        assertEquals("memory", store.subject(A).get("skin").value.getAsString());
        assertEquals(7, store.subject(A).get("other").value.getAsInt());
        assertEquals(1, events.size(), "install 은 이벤트가 없다");
    }

    @Test
    void clearAndClearAll() {
        store.set(A, "a", new JsonPrimitive(1), StateOptions.DEFAULT, 0, ChangeCause.API);
        store.set(A, "b", new JsonPrimitive(2), StateOptions.DEFAULT, 0, ChangeCause.API);
        store.set(null, "g", new JsonPrimitive(3), StateOptions.DEFAULT, 0, ChangeCause.API);
        assertEquals(3, store.clearAll(ChangeCause.CLEAR));
        assertTrue(store.global().isEmpty());
        assertNull(store.subject(A));
    }

    @Test
    void nameRules() {
        assertThrows(IllegalArgumentException.class, () -> store.set(A, "", new JsonPrimitive(1), StateOptions.DEFAULT, 0, ChangeCause.API));
        assertThrows(IllegalArgumentException.class, () -> store.set(A, "x".repeat(300), new JsonPrimitive(1), StateOptions.DEFAULT, 0, ChangeCause.API));
    }
}
