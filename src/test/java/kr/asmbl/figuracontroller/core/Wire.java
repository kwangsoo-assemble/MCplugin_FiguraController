package kr.asmbl.figuracontroller.core;

import kr.asmbl.figuracontroller.protocol.S2COp;
import kr.asmbl.figuracontroller.protocol.S2CReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 시험용 — 받는 클라마다 이름 사전을 들고 패킷을 읽기 쉬운 줄로 */
final class Wire {
    private final Map<UUID, String> labels = new HashMap<>();
    private final Map<UUID, Map<Integer, String>> dicts = new HashMap<>();

    Wire label(UUID id, String label) {
        labels.put(id, label);
        return this;
    }

    /** 이번 flush 에서 이 클라가 받은 것 — 패킷 경계는 «|» */
    List<String> read(Map<UUID, List<byte[]>> out, UUID receiver) {
        List<String> lines = new ArrayList<>();
        List<byte[]> packets = out.get(receiver);
        if (packets == null) return lines;
        Map<Integer, String> dict = dicts.computeIfAbsent(receiver, k -> new HashMap<>());
        boolean first = true;
        for (byte[] p : packets) {
            if (!first) lines.add("|");
            first = false;
            for (S2COp op : S2CReader.read(p)) {
                if (op instanceof S2COp.NameOp n) {
                    dict.put(n.id(), n.name());
                    continue; // 이름 사전은 줄로 안 센다 — names() 로 따로 본다
                }
                lines.add(render(op, dict));
                if (op instanceof S2COp.ResetOp) dict.clear();
            }
        }
        return lines;
    }

    /** 이번 flush 에서 이 클라가 받은 NAME op 수 */
    static int names(Map<UUID, List<byte[]>> out, UUID receiver) {
        int n = 0;
        List<byte[]> packets = out.get(receiver);
        if (packets == null) return 0;
        for (byte[] p : packets) for (S2COp op : S2CReader.read(p)) if (op instanceof S2COp.NameOp) n++;
        return n;
    }

    private String render(S2COp op, Map<Integer, String> dict) {
        if (op instanceof S2COp.SubjectOp s) return "SUBJECT " + labels.getOrDefault(s.uuid(), s.uuid().toString());
        if (op instanceof S2COp.GlobalOp) return "GLOBAL";
        if (op instanceof S2COp.SetOp s) return "SET " + dict.get(s.nameId()) + "=" + s.value();
        if (op instanceof S2COp.UnsetOp u) return "UNSET " + dict.get(u.nameId());
        if (op instanceof S2COp.FullOp f) return "FULL " + entries(f.entries(), dict);
        if (op instanceof S2COp.FullContOp f) return "FULL_CONT " + entries(f.entries(), dict);
        if (op instanceof S2COp.DropOp) return "DROP";
        if (op instanceof S2COp.SignalOp s) return "SIGNAL " + dict.get(s.nameId()) + "=" + s.value();
        if (op instanceof S2COp.ResetOp) return "RESET";
        return op.toString();
    }

    private static String entries(List<S2COp.Entry> es, Map<Integer, String> dict) {
        StringBuilder sb = new StringBuilder("{");
        for (S2COp.Entry e : es) {
            if (sb.length() > 1) sb.append(",");
            sb.append(dict.get(e.nameId())).append("=").append(e.value());
        }
        return sb.append("}").toString();
    }
}
