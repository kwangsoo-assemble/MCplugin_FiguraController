package kr.asmbl.figuracontroller.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 벡터가 못 보는 것 — 읽은 값의 «뜻» · 실제 상한(30,000 B)에서의 나누기 · API 로 만든 수의 분류 */
class CodecTest {
    private static JsonElement roundTrip(JsonElement v) {
        ByteReader r = new ByteReader(Values.encode(v));
        JsonElement back = Values.read(r);
        assertEquals(0, r.remaining());
        return back;
    }

    @Test
    void numbersKeepTheirKind() {
        JsonPrimitive i = roundTrip(JsonParser.parseString("5")).getAsJsonPrimitive();
        assertEquals(Long.class, i.getAsNumber().getClass());
        assertEquals(5L, i.getAsLong());
        JsonPrimitive d = roundTrip(JsonParser.parseString("1.0")).getAsJsonPrimitive();
        assertEquals(Double.class, d.getAsNumber().getClass());
        // API 로 만든 수 — 팀원 · CHFeatures 가 넣는 꼴
        assertEquals(Protocol.T_INT, Values.encode(new JsonPrimitive(7))[0]);
        assertEquals(Protocol.T_INT, Values.encode(new JsonPrimitive(7L))[0]);
        assertEquals(Protocol.T_DOUBLE, Values.encode(new JsonPrimitive(7.0))[0]);
        assertEquals(Protocol.T_DOUBLE, Values.encode(new JsonPrimitive(0.5f))[0]);
        assertEquals(Protocol.T_INT, Values.encode(new JsonPrimitive(new java.math.BigDecimal("100")))[0]);
        assertEquals(Protocol.T_DOUBLE, Values.encode(new JsonPrimitive(new java.math.BigDecimal("1E+3")))[0]);
        assertEquals(Long.MIN_VALUE, roundTrip(new JsonPrimitive(Long.MIN_VALUE)).getAsLong());
    }

    @Test
    void stringsAndBundles() {
        assertEquals("잠뜰 🐟", roundTrip(new JsonPrimitive("잠뜰 🐟")).getAsString());
        JsonObject o = JsonParser.parseString("{\"z\":1,\"a\":[true,null,\"x\"]}").getAsJsonObject();
        JsonElement back = roundTrip(o);
        assertEquals(o, back);
        assertEquals(List.of("z", "a"), new ArrayList<>(back.getAsJsonObject().keySet()));
    }

    @Test
    void depthLimit() {
        JsonElement v = new JsonArray();
        for (int i = 1; i < Protocol.MAX_DEPTH; i++) {
            JsonArray a = new JsonArray();
            a.add(v);
            v = a;
        }
        roundTrip(v); // 32 겹 — 된다
        JsonArray over = new JsonArray();
        over.add(v);
        assertThrows(ProtocolException.class, () -> Values.encode(over));
    }

    @Test
    void splitsAtRealMaxBody() {
        UUID u = UUID.randomUUID();
        byte[] big = Values.encode(new JsonPrimitive("x".repeat(9_000)));
        S2CBuilder b = new S2CBuilder().subject(u);
        for (int i = 0; i < 7; i++) b.set(i, big);
        List<byte[]> packets = b.build();
        assertEquals(3, packets.size()); // 9 KB × 7 = 63 KB → 3 · 3 · 1
        int sets = 0;
        for (byte[] p : packets) {
            assertTrue(p.length <= Protocol.MAX_BODY);
            List<S2COp> ops = S2CReader.read(p);
            assertInstanceOf(S2COp.SubjectOp.class, ops.get(0)); // 새 패킷마다 대상을 다시 쓴다
            assertEquals(u, ((S2COp.SubjectOp) ops.get(0)).uuid());
            for (S2COp op : ops) if (op instanceof S2COp.SetOp) sets++;
        }
        assertEquals(7, sets);
    }

    @Test
    void fullSplitsWithTotal() {
        byte[] big = Values.encode(new JsonPrimitive("y".repeat(12_000)));
        int[] ids = {0, 1, 2, 3, 4};
        byte[][] vals = {big, big, big, big, big};
        List<byte[]> packets = new S2CBuilder().global().full(ids, vals).build();
        int entries = 0;
        int fulls = 0;
        for (byte[] p : packets) {
            for (S2COp op : S2CReader.read(p)) {
                if (op instanceof S2COp.FullOp f) {
                    fulls++;
                    assertEquals(5, f.total());
                    entries += f.entries().size();
                } else if (op instanceof S2COp.FullContOp c) {
                    entries += c.entries().size();
                }
            }
        }
        assertEquals(1, fulls);
        assertEquals(5, entries);
        assertEquals(3, packets.size());
    }

    @Test
    void rejectsOversizedValue() {
        byte[] huge = Values.encode(new JsonPrimitive("z".repeat(Protocol.MAX_BODY)));
        S2CBuilder b = new S2CBuilder().subject(UUID.randomUUID());
        assertThrows(ProtocolException.class, () -> b.set(0, huge));
        assertThrows(ProtocolException.class, () -> new S2CBuilder().set(0, Values.encode(new JsonPrimitive(1))));
    }

    @Test
    void emptyBuilder() {
        S2CBuilder b = new S2CBuilder().subject(UUID.randomUUID());
        assertTrue(b.isEmpty());
        assertEquals(0, b.build().size());
    }

    @Test
    void c2s() {
        assertEquals(new C2S.Hello(Protocol.VERSION), C2S.read(C2S.hello(Protocol.VERSION)));
        C2S.Signal s = (C2S.Signal) C2S.read(C2S.signal("jump", JsonParser.parseString("{\"h\":2}")));
        assertEquals("jump", s.name());
        assertEquals(2L, s.data().getAsJsonObject().get("h").getAsLong());
    }
}
