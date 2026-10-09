package kr.asmbl.figuracontroller.protocol;

import com.google.gson.JsonElement;

import static kr.asmbl.figuracontroller.protocol.Protocol.*;

/** 클라 → 서버 — 한 패킷 = op 하나(드물어서 이름 사전 없이 글자로) */
public final class C2S {
    private C2S() {}

    public sealed interface Op permits Hello, Signal {}

    /** 코어가 FSB 악수 뒤 한 번 — 서버는 이걸 받아야 그 클라를 «준비됨» 으로 본다 */
    public record Hello(int version) implements Op {}

    /** 아바타 → 서버 신호(호스트만 — 서버가 `avatarOwner == 보낸 사람` 으로 거른다) */
    public record Signal(String name, JsonElement data) implements Op {}

    public static byte[] hello(int version) {
        ByteWriter w = new ByteWriter(8);
        w.writeUVarInt(C2S_HELLO);
        w.writeUVarInt(version);
        return w.toByteArray();
    }

    public static byte[] signal(String name, JsonElement data) {
        ByteWriter w = new ByteWriter();
        w.writeUVarInt(C2S_SIGNAL);
        w.writeString(name);
        Values.write(w, data);
        return w.toByteArray();
    }

    public static byte[] write(Op op) {
        if (op instanceof Hello h) return hello(h.version());
        Signal s = (Signal) op;
        return signal(s.name(), s.data());
    }

    public static Op read(byte[] body) {
        ByteReader r = new ByteReader(body);
        int op = r.readUVarInt();
        Op out;
        if (op == C2S_HELLO) {
            out = new Hello(r.readUVarInt());
        } else if (op == C2S_SIGNAL) {
            String name = r.readString();
            out = new Signal(name, Values.read(r));
        } else {
            throw new ProtocolException("unknown C2S op " + op);
        }
        if (r.remaining() != 0) throw new ProtocolException("trailing bytes after C2S op");
        return out;
    }
}
