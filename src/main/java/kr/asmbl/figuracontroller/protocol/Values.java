package kr.asmbl.figuracontroller.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.regex.Pattern;

import static kr.asmbl.figuracontroller.protocol.Protocol.*;

/**
 * 값 = 형 1바이트 + 몸. 서버 · 클라 모두 Gson `JsonElement` 로 다룬다(Paper · Minecraft 둘 다 Gson 이 있다).
 * <p>
 * 수 분류 — **글 모양**으로 가른다: Integer · Long · Short · Byte 는 INT · Double · Float 는 DOUBLE ·
 * 그 밖(JSON 을 읽은 `LazilyParsedNumber` · BigInteger · BigDecimal)은 `toString()` 이 «부호 + 숫자» 이고 64비트에 들면 INT, 아니면 DOUBLE.
 * ⇒ JSON «1» 은 INT · «1.0» 은 DOUBLE (파이썬 참조 구현의 `json.loads` 와 같은 결과).
 * 읽으면 INT → `JsonPrimitive(Long)` · DOUBLE → `JsonPrimitive(Double)` — 다시 쓰면 같은 바이트다.
 */
public final class Values {
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");

    private Values() {}

    public static byte[] encode(JsonElement v) {
        ByteWriter w = new ByteWriter();
        write(w, v);
        return w.toByteArray();
    }

    public static void write(ByteWriter w, JsonElement v) {
        write(w, v, 0);
    }

    private static void write(ByteWriter w, JsonElement v, int depth) {
        if (v == null || v.isJsonNull()) {
            w.writeByte(T_NULL);
            return;
        }
        if (v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isBoolean()) {
                w.writeByte(p.getAsBoolean() ? T_TRUE : T_FALSE);
            } else if (p.isString()) {
                w.writeByte(T_STRING);
                w.writeString(p.getAsString());
            } else {
                Number n = p.getAsNumber();
                Long l = asLong(n);
                if (l != null) {
                    w.writeByte(T_INT);
                    w.writeZigZagLong(l);
                } else {
                    w.writeByte(T_DOUBLE);
                    w.writeDouble(n.doubleValue());
                }
            }
            return;
        }
        if (depth >= MAX_DEPTH) throw new ProtocolException("nesting too deep");
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            w.writeByte(T_ARRAY);
            w.writeUVarInt(a.size());
            for (JsonElement e : a) write(w, e, depth + 1);
            return;
        }
        JsonObject o = v.getAsJsonObject();
        w.writeByte(T_OBJECT);
        w.writeUVarInt(o.size());
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            w.writeString(e.getKey());
            write(w, e.getValue(), depth + 1);
        }
    }

    /** INT 로 실을 수면 그 값 · 아니면 null(DOUBLE) */
    static Long asLong(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) return n.longValue();
        if (n instanceof Double || n instanceof Float) return null;
        String s = n.toString();
        if (!INTEGER.matcher(s).matches()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static JsonElement read(ByteReader r) {
        return read(r, 0);
    }

    private static JsonElement read(ByteReader r, int depth) {
        int t = r.readUByte();
        switch (t) {
            case T_NULL:
                return JsonNull.INSTANCE;
            case T_FALSE:
                return new JsonPrimitive(false);
            case T_TRUE:
                return new JsonPrimitive(true);
            case T_INT:
                return new JsonPrimitive(r.readZigZagLong());
            case T_DOUBLE:
                return new JsonPrimitive(r.readDouble());
            case T_STRING:
                return new JsonPrimitive(r.readString());
            case T_ARRAY: {
                if (depth >= MAX_DEPTH) throw new ProtocolException("nesting too deep");
                int n = count(r);
                JsonArray a = new JsonArray(n);
                for (int i = 0; i < n; i++) a.add(read(r, depth + 1));
                return a;
            }
            case T_OBJECT: {
                if (depth >= MAX_DEPTH) throw new ProtocolException("nesting too deep");
                int n = count(r);
                JsonObject o = new JsonObject();
                for (int i = 0; i < n; i++) {
                    String k = r.readString();
                    o.add(k, read(r, depth + 1));
                }
                return o;
            }
            default:
                throw new ProtocolException("unknown value type " + t);
        }
    }

    /** 개수 — 원소마다 1바이트 이상이므로 남은 바이트보다 많으면 깨진 것 */
    static int count(ByteReader r) {
        int n = r.readUVarInt();
        if (n > r.remaining()) throw new ProtocolException("count exceeds remaining bytes");
        return n;
    }
}
