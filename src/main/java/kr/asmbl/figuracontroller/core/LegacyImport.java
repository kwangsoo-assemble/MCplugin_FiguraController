package kr.asmbl.figuracontroller.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 옛 CH 컨트롤러 저장(`persistence/&lt;uuid&gt;.json` · `~global.json` — 칸마다 `{"update": …, "value": …}`)을 읽는다 (설계 §4.9).
 * 버리는 것: `remains*`(포션 주기 갱신 — 🙋 Q1) · 부라더 전역 찌꺼기(research §9-8) · `update`(새 틀엔 없다 — 옛 `""` 버그도 같이 사라진다).
 */
public final class LegacyImport {
    /** 부라더가 남긴 전역 찌꺼기 · T0 기준선 측정용 `fcBench`(전역 · 측정한 사람에게 남아 있었다 — 2026-10-10 실측) */
    public static final Set<String> JUNK = Set.of("trollUi", "endingMode", "raceRankHud", "raceMode", "performanceMode", "fcBench");

    public record Result(Map<String, JsonElement> keep, List<String> dropped, List<String> broken) {}

    private LegacyImport() {}

    public static boolean dropped(String name) {
        return name.startsWith("remains") || JUNK.contains(name);
    }

    public static Result parse(JsonObject legacy) {
        Map<String, JsonElement> keep = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : legacy.entrySet()) {
            String name = e.getKey();
            if (dropped(name)) {
                dropped.add(name);
                continue;
            }
            JsonElement v = e.getValue();
            if (!v.isJsonObject() || !v.getAsJsonObject().has("value")) {
                broken.add(name);
                continue;
            }
            try {
                StateStore.validateName(name);
            } catch (IllegalArgumentException ex) {
                broken.add(name);
                continue;
            }
            keep.put(name, v.getAsJsonObject().get("value").deepCopy());
        }
        return new Result(keep, dropped, broken);
    }
}
