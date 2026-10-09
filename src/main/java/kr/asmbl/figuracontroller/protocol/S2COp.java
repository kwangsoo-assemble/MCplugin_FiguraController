package kr.asmbl.figuracontroller.protocol;

import com.google.gson.JsonElement;

import java.util.List;
import java.util.UUID;

import static kr.asmbl.figuracontroller.protocol.Protocol.*;

/**
 * 서버 → 클라 op 하나(읽은 모양). {@link #write} 는 같은 바이트를 다시 쓴다 — 시험(읽고 다시 쓰면 같은가) · Flashback 스냅샷용.
 * 서버가 보낼 때는 값을 한 번만 인코딩하려고 {@link S2CBuilder}(미리 인코딩한 값 바이트)를 쓴다.
 */
public sealed interface S2COp {
    int op();

    void write(ByteWriter w);

    record Entry(int nameId, JsonElement value) {}

    record NameOp(int id, String name) implements S2COp {
        public int op() { return OP_NAME; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_NAME);
            w.writeUVarInt(id);
            w.writeString(name);
        }
    }

    record SubjectOp(UUID uuid) implements S2COp {
        public int op() { return OP_SUBJECT; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_SUBJECT);
            w.writeUUID(uuid);
        }
    }

    record GlobalOp() implements S2COp {
        public int op() { return OP_GLOBAL; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_GLOBAL);
        }
    }

    record SetOp(int nameId, JsonElement value) implements S2COp {
        public int op() { return OP_SET; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_SET);
            w.writeUVarInt(nameId);
            Values.write(w, value);
        }
    }

    record UnsetOp(int nameId) implements S2COp {
        public int op() { return OP_UNSET; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_UNSET);
            w.writeUVarInt(nameId);
        }
    }

    /** 이 대상을 {@code total} 개로 갈아 끼운다 — {@code entries} 가 그보다 적으면 나머지는 뒤따르는 {@link FullContOp} */
    record FullOp(int total, List<Entry> entries) implements S2COp {
        public int op() { return OP_FULL; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_FULL);
            w.writeUVarInt(total);
            writeEntries(w, entries);
        }
    }

    record FullContOp(List<Entry> entries) implements S2COp {
        public int op() { return OP_FULL_CONT; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_FULL_CONT);
            writeEntries(w, entries);
        }
    }

    record DropOp() implements S2COp {
        public int op() { return OP_DROP; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_DROP);
        }
    }

    record SignalOp(int nameId, JsonElement value) implements S2COp {
        public int op() { return OP_SIGNAL; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_SIGNAL);
            w.writeUVarInt(nameId);
            Values.write(w, value);
        }
    }

    record ResetOp() implements S2COp {
        public int op() { return OP_RESET; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_RESET);
        }
    }

    private static void writeEntries(ByteWriter w, List<Entry> entries) {
        w.writeUVarInt(entries.size());
        for (Entry e : entries) {
            w.writeUVarInt(e.nameId());
            Values.write(w, e.value());
        }
    }
}
