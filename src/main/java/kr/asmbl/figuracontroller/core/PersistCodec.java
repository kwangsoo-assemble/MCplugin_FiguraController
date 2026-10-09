package kr.asmbl.figuracontroller.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import kr.asmbl.figuracontroller.api.StateOptions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 저장 형식 (플레이어 `players/&lt;uuid&gt;.json` · 전역 `global.json` · 몹 PDC `figuracontroller:states` 문자열 — 셋 다 같다):
 * <pre>{"version": 1, "states": {"skin": {"value": "RATH4R"}, "secret": {"value": 1, "private": true}, "buff": {"value": true, "expireIn": 200}}}</pre>
 * {@code persist} 인 상태만 쓴다 · 만료는 «남은 틱» 으로 적고 읽을 때 지금 틱에 더한다(서버를 껐다 켜도 남은 만큼).
 */
public final class PersistCodec {
    public static final int VERSION = 1;

    private PersistCodec() {}

    /** @return 저장할 것이 없으면 null(파일 · PDC 를 지운다) */
    public static JsonObject write(SubjectState s, long nowTick) {
        JsonObject states = new JsonObject();
        for (Map.Entry<String, Entry> e : s.entries.entrySet()) {
            Entry v = e.getValue();
            if (!v.persist) continue;
            JsonObject o = new JsonObject();
            o.add("value", v.value.deepCopy());
            if (v.priv) o.addProperty("private", true);
            if (v.expireTick >= 0) o.addProperty("expireIn", Math.max(1, v.expireTick - nowTick));
            states.add(e.getKey(), o);
        }
        if (states.isEmpty()) return null;
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("states", states);
        return root;
    }

    /** 깨진 칸은 건너뛴다(한 칸이 나머지를 막지 않게) — 건너뛴 이름은 {@code skipped} 에 */
    public static Map<String, Entry> read(JsonObject root, boolean global, long nowTick, java.util.List<String> skipped) {
        Map<String, Entry> out = new LinkedHashMap<>();
        JsonElement st = root == null ? null : root.get("states");
        if (st == null || !st.isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> e : st.getAsJsonObject().entrySet()) {
            try {
                StateStore.validateName(e.getKey());
                JsonObject o = e.getValue().getAsJsonObject();
                if (!o.has("value")) throw new IllegalArgumentException("value 가 없다");
                boolean priv = o.has("private") && o.get("private").getAsBoolean();
                int expireIn = o.has("expireIn") ? o.get("expireIn").getAsInt() : 0;
                StateOptions opt = new StateOptions(true, priv, Math.max(0, expireIn));
                out.put(e.getKey(), StateStore.entry(o.get("value"), opt, global, nowTick));
            } catch (RuntimeException ex) {
                if (skipped != null) skipped.add(e.getKey());
            }
        }
        return out;
    }
}
