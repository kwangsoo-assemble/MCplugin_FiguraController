package kr.asmbl.figuracontroller.protocol;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

import static kr.asmbl.figuracontroller.protocol.Protocol.*;

/**
 * 서버 → 클라 패킷 몸 → op 목록. 모양만 검사한다(대상 없이 대상이 필요한 op · 모르는 op · 깨진 길이 · FULL n &gt; total).
 * 이름 사전 · FULL 이어 붙이기 같은 **상태**는 받는 쪽(코어 저장소)이 다룬다 — 패킷을 넘어 살기 때문이다.
 * 깨진 패킷이면 {@link ProtocolException} — 앞에서 읽은 op 도 돌려주지 않는다(반쪽 적용을 막는다).
 */
public final class S2CReader {
    private S2CReader() {}

    public static List<S2COp> read(byte[] body) {
        ByteReader r = new ByteReader(body);
        List<S2COp> out = new ArrayList<>();
        boolean ctx = false;
        while (r.remaining() > 0) {
            int op = r.readUVarInt();
            if (needsContext(op) && !ctx) throw new ProtocolException("op without subject: " + op);
            switch (op) {
                case OP_NAME -> {
                    int id = r.readUVarInt();
                    out.add(new S2COp.NameOp(id, r.readString()));
                }
                case OP_SUBJECT -> {
                    out.add(new S2COp.SubjectOp(r.readUUID()));
                    ctx = true;
                }
                case OP_GLOBAL -> {
                    out.add(new S2COp.GlobalOp());
                    ctx = true;
                }
                case OP_SET -> {
                    int id = r.readUVarInt();
                    out.add(new S2COp.SetOp(id, Values.read(r)));
                }
                case OP_UNSET -> out.add(new S2COp.UnsetOp(r.readUVarInt()));
                case OP_FULL -> {
                    int total = r.readUVarInt();
                    List<S2COp.Entry> e = entries(r);
                    if (e.size() > total) throw new ProtocolException("FULL n > total");
                    out.add(new S2COp.FullOp(total, e));
                }
                case OP_FULL_CONT -> out.add(new S2COp.FullContOp(entries(r)));
                case OP_DROP -> out.add(new S2COp.DropOp());
                case OP_SIGNAL -> {
                    int id = r.readUVarInt();
                    out.add(new S2COp.SignalOp(id, Values.read(r)));
                }
                case OP_RESET -> out.add(new S2COp.ResetOp());
                default -> throw new ProtocolException("unknown op " + op);
            }
        }
        return out;
    }

    private static List<S2COp.Entry> entries(ByteReader r) {
        int n = Values.count(r);
        List<S2COp.Entry> e = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = r.readUVarInt();
            JsonElement v = Values.read(r);
            e.add(new S2COp.Entry(id, v));
        }
        return e;
    }
}
