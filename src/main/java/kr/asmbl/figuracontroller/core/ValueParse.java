package kr.asmbl.figuracontroller.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;
import java.util.regex.Pattern;

/** 명령 글 → 값 */
public final class ValueParse {
    private static final TypeAdapter<JsonElement> ADAPTER = new Gson().getAdapter(JsonElement.class);
    private static final Pattern INT = Pattern.compile("-?[0-9]+");
    private static final Pattern DEC = Pattern.compile("-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?");

    private ValueParse() {}

    /**
     * 새 문법 — **엄격한 JSON** 이면 그 값(`true` · `3` · `1.5` · `"글"` · `[1,2]` · `{"x":1}` · `null`) · 아니면 **글 그대로**(`RATH4R`).
     * ⚠ Gson 의 느슨한 해석은 따옴표 없는 낱말도 글로 읽어 «JSON 인지» 를 못 가린다 — 그래서 엄격 모드로 읽고 끝까지 먹었는지 본다.
     */
    public static JsonElement jsonOrString(String raw) {
        try {
            JsonReader r = new JsonReader(new StringReader(raw));
            r.setStrictness(Strictness.STRICT);
            JsonElement v = ADAPTER.read(r);
            if (r.peek() == JsonToken.END_DOCUMENT) return v;
        } catch (IOException | RuntimeException ignored) {
            // JSON 이 아니다
        }
        return new JsonPrimitive(raw);
    }

    /** 옛 문법(CH `_convert_string`) — `true` · `false` · `null`(대소문자 무시) · 수 · 그 밖은 글. 묶음은 못 쓴다 */
    public static JsonElement legacy(String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "true":
                return new JsonPrimitive(true);
            case "false":
                return new JsonPrimitive(false);
            case "null":
                return JsonNull.INSTANCE;
            default:
                break;
        }
        if (INT.matcher(raw).matches()) {
            try {
                return new JsonPrimitive(Long.parseLong(raw));
            } catch (NumberFormatException ignored) {
                return new JsonPrimitive(Double.parseDouble(raw));
            }
        }
        if (DEC.matcher(raw).matches()) return new JsonPrimitive(Double.parseDouble(raw));
        return new JsonPrimitive(raw);
    }

    /** 옛 문법의 «지움» 표시 */
    public static boolean isLegacyRemove(String raw) {
        return "~none".equals(raw);
    }
}
