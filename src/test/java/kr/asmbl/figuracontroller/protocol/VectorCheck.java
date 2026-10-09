package kr.asmbl.figuracontroller.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 시험 벡터(`vectors.json` — 하네스 파이썬 참조 구현이 만든다)로 코덱을 잰다. JUnit 없이 돈다:
 * 플러그인에서는 {@code VectorTest} 가 부르고, 하네스 `verify_codec.py` 는 **포크 코어의 사본**과 이 파일을 javac 로 묶어
 * {@code main} 을 돌린다 — 같은 벡터로 두 쪽을 잰다(설계 §9-D).
 */
public final class VectorCheck {
    private static final HexFormat HEX = HexFormat.of();
    private static final String[] OP_NAMES = {null, "NAME", "SUBJECT", "GLOBAL", "SET", "UNSET", "FULL", "DROP", "SIGNAL", "RESET", "FULL_CONT"};

    public record Result(int passed, List<String> failures) {}

    private VectorCheck() {}

    public static Result run(String json) {
        JsonObject v = JsonParser.parseString(json).getAsJsonObject();
        List<String> fail = new ArrayList<>();
        int ok = 0;

        // 상수
        if (v.get("channelId").getAsInt() != Protocol.CHANNEL_ID || !v.get("channel").getAsString().equals(Protocol.CHANNEL)) fail.add("상수: channel");
        else ok++;
        if (v.get("version").getAsInt() != Protocol.VERSION || v.get("maxBody").getAsInt() != Protocol.MAX_BODY
                || v.get("maxValue").getAsInt() != Protocol.MAX_VALUE || v.get("maxDepth").getAsInt() != Protocol.MAX_DEPTH) fail.add("상수: version · max*");
        else ok++;

        // 값
        for (JsonElement e : v.getAsJsonArray("values")) {
            JsonObject c = e.getAsJsonObject();
            String name = "값 " + c.get("name").getAsString();
            try {
                byte[] want = HEX.parseHex(c.get("hex").getAsString());
                byte[] got = Values.encode(JsonParser.parseString(c.get("json").getAsString()));
                if (!HEX.formatHex(got).equals(HEX.formatHex(want))) {
                    fail.add(name + ": 인코딩 " + HEX.formatHex(got) + " ≠ " + HEX.formatHex(want));
                    continue;
                }
                ByteReader r = new ByteReader(want);
                JsonElement back = Values.read(r);
                if (r.remaining() != 0) {
                    fail.add(name + ": 읽고 남은 바이트 " + r.remaining());
                    continue;
                }
                if (!HEX.formatHex(Values.encode(back)).equals(HEX.formatHex(want))) {
                    fail.add(name + ": 읽고 다시 쓰면 다르다");
                    continue;
                }
                ok++;
            } catch (RuntimeException ex) {
                fail.add(name + ": " + ex);
            }
        }

        // 서버 → 클라
        for (JsonElement e : v.getAsJsonArray("s2c")) {
            JsonObject c = e.getAsJsonObject();
            String name = "S2C " + c.get("name").getAsString();
            try {
                List<byte[]> got = build(c.get("max").getAsInt(), c.getAsJsonArray("ops"));
                JsonArray want = c.getAsJsonArray("packets");
                JsonArray shape = c.getAsJsonArray("shape");
                if (got.size() != want.size()) {
                    fail.add(name + ": 패킷 수 " + got.size() + " ≠ " + want.size());
                    continue;
                }
                boolean good = true;
                for (int i = 0; i < got.size() && good; i++) {
                    String gh = HEX.formatHex(got.get(i));
                    if (!gh.equals(want.get(i).getAsString())) {
                        fail.add(name + ": 패킷 " + i + " " + gh + " ≠ " + want.get(i).getAsString());
                        good = false;
                        break;
                    }
                    List<S2COp> ops = S2CReader.read(got.get(i));
                    JsonArray sh = shape.get(i).getAsJsonArray();
                    List<String> names = new ArrayList<>();
                    ByteWriter w = new ByteWriter();
                    for (S2COp op : ops) {
                        names.add(OP_NAMES[op.op()]);
                        op.write(w);
                    }
                    List<String> wantNames = new ArrayList<>();
                    for (JsonElement s : sh) wantNames.add(s.getAsString());
                    if (!names.equals(wantNames)) {
                        fail.add(name + ": 패킷 " + i + " 모양 " + names + " ≠ " + wantNames);
                        good = false;
                    } else if (!HEX.formatHex(w.toByteArray()).equals(gh)) {
                        fail.add(name + ": 패킷 " + i + " 읽고 다시 쓰면 다르다");
                        good = false;
                    }
                }
                if (good) ok++;
            } catch (RuntimeException ex) {
                fail.add(name + ": " + ex);
            }
        }

        // 서버 → 클라 — 쓰기가 거절해야 하는 것
        for (JsonElement e : v.getAsJsonArray("s2cErrors")) {
            JsonObject c = e.getAsJsonObject();
            String name = "S2C 거절 " + c.get("name").getAsString();
            try {
                build(c.get("max").getAsInt(), c.getAsJsonArray("ops"));
                fail.add(name + ": 거절하지 않았다");
            } catch (ProtocolException ex) {
                ok++;
            } catch (RuntimeException ex) {
                fail.add(name + ": 다른 예외 " + ex);
            }
        }

        // 클라 → 서버
        for (JsonElement e : v.getAsJsonArray("c2s")) {
            JsonObject c = e.getAsJsonObject();
            String name = "C2S " + c.get("name").getAsString();
            try {
                JsonArray op = c.getAsJsonArray("op");
                byte[] got = op.get(0).getAsString().equals("HELLO")
                        ? C2S.hello(op.get(1).getAsInt())
                        : C2S.signal(op.get(1).getAsString(), JsonParser.parseString(op.get(2).getAsString()));
                String gh = HEX.formatHex(got);
                if (!gh.equals(c.get("hex").getAsString())) {
                    fail.add(name + ": " + gh + " ≠ " + c.get("hex").getAsString());
                    continue;
                }
                C2S.Op back = C2S.read(got);
                String kind = back instanceof C2S.Hello ? "HELLO" : "SIGNAL";
                if (!kind.equals(op.get(0).getAsString()) || !HEX.formatHex(C2S.write(back)).equals(gh)) {
                    fail.add(name + ": 읽고 다시 쓰면 다르다");
                    continue;
                }
                ok++;
            } catch (RuntimeException ex) {
                fail.add(name + ": " + ex);
            }
        }

        // 깨진 패킷 — ProtocolException 이어야 한다(다른 예외면 받는 쪽이 못 잡는다)
        for (JsonElement e : v.getAsJsonArray("invalid")) {
            JsonObject c = e.getAsJsonObject();
            String name = "무효 " + c.get("dir").getAsString() + " " + c.get("name").getAsString();
            byte[] b = HEX.parseHex(c.get("hex").getAsString());
            try {
                if (c.get("dir").getAsString().equals("s2c")) S2CReader.read(b);
                else C2S.read(b);
                fail.add(name + ": 받아들였다");
            } catch (ProtocolException ex) {
                ok++;
            } catch (RuntimeException ex) {
                fail.add(name + ": 다른 예외 " + ex);
            }
        }
        return new Result(ok, fail);
    }

    static List<byte[]> build(int max, JsonArray ops) {
        S2CBuilder b = new S2CBuilder(max);
        for (JsonElement e : ops) {
            JsonArray op = e.getAsJsonArray();
            switch (op.get(0).getAsString()) {
                case "NAME" -> b.name(op.get(1).getAsInt(), op.get(2).getAsString());
                case "SUBJECT" -> b.subject(UUID.fromString(op.get(1).getAsString()));
                case "GLOBAL" -> b.global();
                case "SET" -> b.set(op.get(1).getAsInt(), value(op.get(2)));
                case "UNSET" -> b.unset(op.get(1).getAsInt());
                case "FULL" -> {
                    JsonArray en = op.get(1).getAsJsonArray();
                    int[] ids = new int[en.size()];
                    byte[][] vals = new byte[en.size()][];
                    for (int i = 0; i < en.size(); i++) {
                        ids[i] = en.get(i).getAsJsonArray().get(0).getAsInt();
                        vals[i] = value(en.get(i).getAsJsonArray().get(1));
                    }
                    b.full(ids, vals);
                }
                case "DROP" -> b.drop();
                case "SIGNAL" -> b.signal(op.get(1).getAsInt(), value(op.get(2)));
                case "RESET" -> b.reset();
                default -> throw new IllegalArgumentException("모르는 벡터 op " + op.get(0));
            }
        }
        return b.build();
    }

    private static byte[] value(JsonElement jsonText) {
        return Values.encode(JsonParser.parseString(jsonText.getAsString()));
    }

    /** {@code java VectorCheck <vectors.json>} — 실패가 있으면 종료 코드 1 */
    public static void main(String[] args) throws IOException {
        Result r = run(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        for (String f : r.failures()) System.out.println("FAIL " + f);
        System.out.println("VECTORS passed=" + r.passed() + " failed=" + r.failures().size());
        System.exit(r.failures().isEmpty() ? 0 : 1);
    }
}
