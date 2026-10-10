package kr.asmbl.figuracontroller.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import kr.asmbl.figuracontroller.api.StateOptions;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 프리셋 `presets/&lt;이름&gt;.json` = {@code {"상태 이름": {"value": …, "persist": true, "private": false}}} (설계 §4.4).
 * `persist` · `private` 는 빼면 기본값(켬 · 끔).
 */
public final class PresetCodec {
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    public record Item(JsonElement value, StateOptions options) {}

    private PresetCodec() {}

    public static Map<String, Item> read(JsonObject root) {
        Map<String, Item> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            StateStore.validateName(e.getKey());
            JsonObject o = e.getValue().getAsJsonObject();
            if (!o.has("value")) throw new IllegalArgumentException(e.getKey() + ": value 가 없다");
            boolean persist = !o.has("persist") || o.get("persist").getAsBoolean();
            boolean priv = o.has("private") && o.get("private").getAsBoolean();
            out.put(e.getKey(), new Item(o.get("value").deepCopy(), new StateOptions(persist, priv, 0)));
        }
        return out;
    }

    /** 대상의 지금 상태에서 — {@code names} 가 비면 전부 */
    public static JsonObject write(SubjectState s, Collection<String> names) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Entry> e : s.entries.entrySet()) {
            if (!names.isEmpty() && !names.contains(e.getKey())) continue;
            JsonObject o = new JsonObject();
            o.add("value", e.getValue().value.deepCopy());
            if (!e.getValue().persist) o.addProperty("persist", false);
            if (e.getValue().priv) o.addProperty("private", true);
            root.add(e.getKey(), o);
        }
        return root;
    }

    /** 첫 프리셋 `default` — 옛 CH 기본 전역(`remains*` 는 뺀다 — 🙋 Q1 · research §6)
     *  + 이름표 정책 둘 = false(관전 · 투명이면 이름표 숨김 — 0.2.1 · 하네스 template_promotions N1).
     *  ⚠ 첫 실행에만 쓴다 — 이미 있는 서버의 presets/default.json 은 그대로다(손으로 더한다) */
    public static JsonObject defaults() {
        JsonObject root = new JsonObject();
        for (String n : new String[]{"nickName", "listName", "chatName", "plateName", "skin", "isArmWide"}) {
            JsonObject o = new JsonObject();
            o.add("value", JsonNull.INSTANCE);
            root.add(n, o);
        }
        JsonObject show = new JsonObject();
        show.add("value", new JsonPrimitive(true));
        root.add("showNametag", show);
        for (String n : new String[]{"nametagShowSpectator", "nametagShowInvisibility"}) {
            JsonObject o = new JsonObject();
            o.add("value", new JsonPrimitive(false));
            root.add(n, o);
        }
        return root;
    }
}
