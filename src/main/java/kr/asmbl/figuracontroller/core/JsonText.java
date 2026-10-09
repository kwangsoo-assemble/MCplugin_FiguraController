package kr.asmbl.figuracontroller.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

/**
 * 파일 · PDC 에 쓰는 JSON 글 — ★ **`serializeNulls()` 필수**. Gson 기본은 값이 `null` 인 칸을 **빼고** 쓴다 →
 * `{"nickName": {"value": null}}` 이 `{"nickName": {}}` 가 되어 다시 읽을 때 «value 가 없다» 로 버려진다
 * (2026-10-10 연기 시험 실측 — 프리셋 `default` 가 첫 실행에 안 먹었다). 파일을 쓰는 곳은 전부 이것을 거친다.
 */
public final class JsonText {
    private static final Gson PRETTY = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Gson COMPACT = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private JsonText() {}

    public static String pretty(JsonElement e) {
        return PRETTY.toJson(e);
    }

    public static String compact(JsonElement e) {
        return COMPACT.toJson(e);
    }
}
