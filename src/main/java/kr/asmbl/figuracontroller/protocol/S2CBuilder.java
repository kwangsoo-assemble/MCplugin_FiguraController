package kr.asmbl.figuracontroller.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static kr.asmbl.figuracontroller.protocol.Protocol.*;

/**
 * 한 클라에게 갈 op 줄을 패킷 몸들로 묶는다 — 값은 **미리 인코딩한 바이트**({@link Values#encode})로 받는다
 * (서버는 바뀐 값을 대상마다 한 번만 인코딩하고 받는 사람마다 같은 바이트를 붙인다).
 * <p>
 * 나누기 규칙 (하네스 파이썬 참조 구현 `fc_codec_ref.Builder` 와 바이트 단위로 같아야 한다):
 * <ul>
 *   <li>몸 상한({@link Protocol#MAX_BODY})을 넘으면 op 경계에서 다음 패킷.</li>
 *   <li>{@link #subject} · {@link #global} 은 바로 쓰지 않고 «대상이 필요한 op» 앞에서 쓴다 — 새 패킷이면 그 앞에 다시 쓴다
 *       (대상은 패킷 안에서만 산다).</li>
 *   <li>{@link #full} 이 안 들어가면 들어가는 만큼 FULL(total = 전체 수) · 나머지는 FULL_CONT 로 이어 간다.</li>
 *   <li>op 하나(대상 포함)가 상한을 넘으면 {@link ProtocolException}.</li>
 * </ul>
 */
public final class S2CBuilder {
    private final int max;
    private final List<byte[]> packets = new ArrayList<>();
    private final ByteWriter cur = new ByteWriter(256);
    private final ByteWriter tmp = new ByteWriter(256);
    private byte[] ctx;
    private boolean ctxEmitted;

    public S2CBuilder() {
        this(MAX_BODY);
    }

    public S2CBuilder(int maxBody) {
        this.max = maxBody;
    }

    public S2CBuilder name(int id, String name) {
        tmp.reset();
        tmp.writeUVarInt(OP_NAME);
        tmp.writeUVarInt(id);
        tmp.writeString(name);
        free(tmp.toByteArray());
        return this;
    }

    public S2CBuilder reset() {
        tmp.reset();
        tmp.writeUVarInt(OP_RESET);
        free(tmp.toByteArray());
        return this;
    }

    public S2CBuilder subject(UUID uuid) {
        tmp.reset();
        tmp.writeUVarInt(OP_SUBJECT);
        tmp.writeUUID(uuid);
        ctx = tmp.toByteArray();
        ctxEmitted = false;
        return this;
    }

    public S2CBuilder global() {
        tmp.reset();
        tmp.writeUVarInt(OP_GLOBAL);
        ctx = tmp.toByteArray();
        ctxEmitted = false;
        return this;
    }

    public S2CBuilder set(int nameId, byte[] value) {
        tmp.reset();
        tmp.writeUVarInt(OP_SET);
        tmp.writeUVarInt(nameId);
        tmp.writeBytes(value);
        ctxOp(tmp.toByteArray());
        return this;
    }

    public S2CBuilder unset(int nameId) {
        tmp.reset();
        tmp.writeUVarInt(OP_UNSET);
        tmp.writeUVarInt(nameId);
        ctxOp(tmp.toByteArray());
        return this;
    }

    public S2CBuilder drop() {
        tmp.reset();
        tmp.writeUVarInt(OP_DROP);
        ctxOp(tmp.toByteArray());
        return this;
    }

    public S2CBuilder signal(int nameId, byte[] value) {
        tmp.reset();
        tmp.writeUVarInt(OP_SIGNAL);
        tmp.writeUVarInt(nameId);
        tmp.writeBytes(value);
        ctxOp(tmp.toByteArray());
        return this;
    }

    /** 이 대상의 전부 — {@code nameIds[i]} 의 값이 {@code values[i]}(미리 인코딩한 바이트) */
    public S2CBuilder full(int[] nameIds, byte[][] values) {
        if (ctx == null) throw new ProtocolException("FULL without subject");
        if (nameIds.length != values.length) throw new IllegalArgumentException("nameIds/values length mismatch");
        int n = nameIds.length;
        byte[][] enc = new byte[n][];
        for (int i = 0; i < n; i++) {
            tmp.reset();
            tmp.writeUVarInt(nameIds[i]);
            tmp.writeBytes(values[i]);
            enc[i] = tmp.toByteArray();
        }
        int headLen = ByteWriter.uvarIntSize(OP_FULL) + ByteWriter.uvarIntSize(n);
        int contLen = ByteWriter.uvarIntSize(OP_FULL_CONT);
        int start = 0;
        boolean first = true;
        while (true) {
            int hl = first ? headLen : contLen;
            int avail = max - cur.size() - (ctxEmitted ? 0 : ctx.length);
            int k = 0;
            int total = 0;
            while (start + k < n) {
                int e = enc[start + k].length;
                if (hl + ByteWriter.uvarIntSize(k + 1) + total + e <= avail) {
                    k++;
                    total += e;
                } else {
                    break;
                }
            }
            boolean done = start + k == n;
            boolean fits = k > 0 || (first && n == 0 && hl + ByteWriter.uvarIntSize(0) <= avail);
            if (!fits) {
                if (cur.size() > 0) {
                    flush();
                    continue;
                }
                throw new ProtocolException("a FULL entry exceeds the body limit");
            }
            tmp.reset();
            if (first) {
                tmp.writeUVarInt(OP_FULL);
                tmp.writeUVarInt(n);
            } else {
                tmp.writeUVarInt(OP_FULL_CONT);
            }
            tmp.writeUVarInt(k);
            for (int j = start; j < start + k; j++) tmp.writeBytes(enc[j]);
            ctxOp(tmp.toByteArray());
            start += k;
            first = false;
            if (done) return this;
        }
    }

    /** 아직 아무것도 안 담겼나(대상만 정한 것은 안 센다) */
    public boolean isEmpty() {
        return packets.isEmpty() && cur.size() == 0;
    }

    /** 패킷 몸들 — 빈 묶음이면 빈 목록 */
    public List<byte[]> build() {
        flush();
        return new ArrayList<>(packets);
    }

    private void flush() {
        if (cur.size() > 0) {
            packets.add(cur.toByteArray());
            cur.reset();
        }
        ctxEmitted = false;
    }

    private void free(byte[] b) {
        if (b.length > max) throw new ProtocolException("op exceeds the body limit");
        if (cur.size() + b.length > max) flush();
        cur.writeBytes(b);
    }

    private void ctxOp(byte[] b) {
        if (ctx == null) throw new ProtocolException("op requires a subject");
        int need = b.length + (ctxEmitted ? 0 : ctx.length);
        if (cur.size() + need > max && cur.size() > 0) {
            flush();
            need = b.length + ctx.length;
        }
        if (need > max) throw new ProtocolException("op exceeds the body limit");
        if (!ctxEmitted) {
            cur.writeBytes(ctx);
            ctxEmitted = true;
        }
        cur.writeBytes(b);
    }
}
