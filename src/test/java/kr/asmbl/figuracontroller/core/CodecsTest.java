package kr.asmbl.figuracontroller.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import kr.asmbl.figuracontroller.api.ChangeCause;
import kr.asmbl.figuracontroller.api.StateOptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 저장 형식 · 옛 CH 가져오기 · 명령 값 · 프리셋 */
class CodecsTest {
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void persistRoundTrip() {
        StateStore s = new StateStore();
        s.set(A, "skin", new JsonPrimitive("R"), StateOptions.DEFAULT, 1000, ChangeCause.API);
        s.set(A, "secret", new JsonPrimitive(1), StateOptions.DEFAULT.withPrivate(true), 1000, ChangeCause.API);
        s.set(A, "buff", new JsonPrimitive(true), StateOptions.DEFAULT.withExpireTicks(200), 1000, ChangeCause.API);
        s.set(A, "ragdollActive", new JsonPrimitive(true), StateOptions.DEFAULT.withPersist(false), 1000, ChangeCause.API);
        JsonObject j = PersistCodec.write(s.subject(A), 1050);
        assertEquals(3, j.getAsJsonObject("states").size(), "저장 안 하는 상태는 안 쓴다");
        assertEquals(150, j.getAsJsonObject("states").getAsJsonObject("buff").get("expireIn").getAsInt(), "남은 틱");

        Map<String, Entry> back = PersistCodec.read(JsonParser.parseString(j.toString()).getAsJsonObject(), false, 5000, null);
        assertEquals("R", back.get("skin").value.getAsString());
        assertTrue(back.get("secret").priv);
        assertEquals(5150, back.get("buff").expireTick, "읽은 틱 + 남은 틱");
        assertTrue(back.get("skin").persist);
        assertFalse(back.containsKey("ragdollActive"));
    }

    /**
     * ★ 회귀 — 파일에 쓰는 길(JsonText)로 왕복한다. `JsonObject.toString()` 은 null 칸을 쓰지만 기본 Gson 은 **빼고** 써서
     * 위 시험(`toString`)은 초록인데 서버에서는 `nickName: null` 이 저장 뒤 사라졌다(2026-10-10 연기 시험).
     */
    @Test
    void nullValuesSurviveTheFileWriter() {
        StateStore s = new StateStore();
        s.set(A, "nickName", com.google.gson.JsonNull.INSTANCE, StateOptions.DEFAULT, 0, ChangeCause.API);
        s.set(A, "arr", JsonParser.parseString("[null, 1]"), StateOptions.DEFAULT, 0, ChangeCause.API);
        for (String text : new String[]{JsonText.pretty(PersistCodec.write(s.subject(A), 0)), JsonText.compact(PersistCodec.write(s.subject(A), 0))}) {
            List<String> skipped = new ArrayList<>();
            Map<String, Entry> back = PersistCodec.read(JsonParser.parseString(text).getAsJsonObject(), false, 0, skipped);
            assertEquals(List.of(), skipped, text);
            assertTrue(back.get("nickName").value.isJsonNull());
            assertEquals(2, back.get("arr").value.getAsJsonArray().size());
        }
        Map<String, PresetCodec.Item> preset = PresetCodec.read(JsonParser.parseString(JsonText.pretty(PresetCodec.defaults())).getAsJsonObject());
        assertTrue(preset.get("nickName").value().isJsonNull(), "프리셋 default 도 같은 길로 쓴다");
    }

    @Test
    void persistNothingIsNull() {
        StateStore s = new StateStore();
        s.set(A, "t", new JsonPrimitive(1), StateOptions.DEFAULT.withPersist(false), 0, ChangeCause.API);
        assertNull(PersistCodec.write(s.subject(A), 0));
    }

    @Test
    void persistSkipsBrokenRows() {
        JsonObject root = JsonParser.parseString("{\"version\":1,\"states\":{\"ok\":{\"value\":1},\"bad\":5,\"novalue\":{}}}").getAsJsonObject();
        List<String> skipped = new ArrayList<>();
        Map<String, Entry> m = PersistCodec.read(root, false, 0, skipped);
        assertEquals(List.of("ok"), new ArrayList<>(m.keySet()));
        assertEquals(List.of("bad", "novalue"), skipped);
    }

    @Test
    void legacyImport() {
        // 라이브 `persistence/` 꼴 그대로 — update 칸 · remains* · 부라더 찌꺼기 · nickName 의 옛 `update: ""` 버그
        String legacy = "{\"regions\":{\"update\":1791451625467,\"value\":[]},\"remainsLuck\":{\"update\":1,\"value\":0},"
                + "\"remainsUnluck\":{\"update\":1,\"value\":0},\"endingMode\":{\"update\":1,\"value\":false},"
                + "\"nickName\":{\"update\":\"\",\"value\":null},\"broken\":3,\"fcBench\":{\"update\":1,\"value\":1}}";
        LegacyImport.Result r = LegacyImport.parse(JsonParser.parseString(legacy).getAsJsonObject());
        assertEquals(List.of("regions", "nickName"), new ArrayList<>(r.keep().keySet()));
        assertTrue(r.keep().get("nickName").isJsonNull(), "null 은 값이다");
        assertEquals(List.of("remainsLuck", "remainsUnluck", "endingMode", "fcBench"), r.dropped());
        assertEquals(List.of("broken"), r.broken());
    }

    @Test
    void commandValues() {
        assertEquals(new JsonPrimitive("RATH4R"), ValueParse.jsonOrString("RATH4R"));
        assertEquals(new JsonPrimitive(true), ValueParse.jsonOrString("true"));
        assertTrue(ValueParse.jsonOrString("null").isJsonNull());
        assertEquals(new JsonPrimitive("x y"), ValueParse.jsonOrString("\"x y\""));
        assertEquals(new JsonPrimitive("hello world"), ValueParse.jsonOrString("hello world"));
        assertEquals(new JsonPrimitive("1 2"), ValueParse.jsonOrString("1 2"), "끝까지 못 먹으면 글");
        assertEquals(new JsonPrimitive("{bad"), ValueParse.jsonOrString("{bad"));
        assertEquals(new JsonPrimitive("True"), ValueParse.jsonOrString("True"), "엄격 JSON — 대문자 True 는 글");
        JsonElement arr = ValueParse.jsonOrString("[1, 2.5]");
        assertTrue(arr.isJsonArray());
        assertEquals(kr.asmbl.figuracontroller.protocol.Protocol.T_INT, kr.asmbl.figuracontroller.protocol.Values.encode(ValueParse.jsonOrString("3"))[0]);
        assertEquals(kr.asmbl.figuracontroller.protocol.Protocol.T_DOUBLE, kr.asmbl.figuracontroller.protocol.Values.encode(ValueParse.jsonOrString("3.0"))[0]);
    }

    @Test
    void legacyValues() {
        assertEquals(new JsonPrimitive(true), ValueParse.legacy("TRUE"));
        assertTrue(ValueParse.legacy("Null").isJsonNull());
        assertEquals(new JsonPrimitive(7L), ValueParse.legacy("7"));
        assertEquals(new JsonPrimitive(1.5), ValueParse.legacy("1.5"));
        assertEquals(new JsonPrimitive("라더"), ValueParse.legacy("라더"));
        assertEquals(new JsonPrimitive("[1,2]"), ValueParse.legacy("[1,2]"), "옛 문법은 묶음을 못 쓴다");
        assertTrue(ValueParse.isLegacyRemove("~none"));
        assertFalse(ValueParse.isLegacyRemove("none"));
    }

    @Test
    void presets() {
        JsonObject def = PresetCodec.defaults();
        Map<String, PresetCodec.Item> items = PresetCodec.read(def);
        assertEquals(List.of("nickName", "listName", "chatName", "plateName", "skin", "isArmWide", "showNametag"), new ArrayList<>(items.keySet()));
        assertTrue(items.get("skin").value().isJsonNull());
        assertEquals(new JsonPrimitive(true), items.get("showNametag").value());
        assertTrue(items.get("skin").options().persist());

        StateStore s = new StateStore();
        s.set(A, "a", new JsonPrimitive(1), StateOptions.DEFAULT, 0, ChangeCause.API);
        s.set(A, "b", new JsonPrimitive(2), StateOptions.DEFAULT.withPersist(false).withPrivate(true), 0, ChangeCause.API);
        Map<String, PresetCodec.Item> back = PresetCodec.read(PresetCodec.write(s.subject(A), List.of()));
        assertFalse(back.get("b").options().persist());
        assertTrue(back.get("b").options().privateState());
        assertEquals(List.of("a"), new ArrayList<>(PresetCodec.read(PresetCodec.write(s.subject(A), List.of("a"))).keySet()));
        assertFalse(PresetCodec.NAME.matcher("../x").matches());
    }
}
