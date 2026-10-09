package kr.asmbl.figuracontroller.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

/** 명령 글 → 값 */
public final class ValueParse {
    private static final TypeAdapter<JsonElement> ADAPTER = new Gson().getAdapter(JsonElement.class);

    private ValueParse() {}

    /**
     * **엄격한 JSON** 이면 그 값(`true` · `3` · `1.5` · `"글"` · `[1,2]` · `{"x":1}` · `null`) · 아니면 **글 그대로**(`RATH4R`).
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
}
