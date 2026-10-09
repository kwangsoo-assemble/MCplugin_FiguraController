package kr.asmbl.figuracontroller.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 한 대상(엔티티 UUID · 전역 = null)의 상태들 — 이름 순서 = 처음 넣은 순서 */
public final class SubjectState {
    public final UUID id;
    final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    SubjectState(UUID id) {
        this.id = id;
    }

    public boolean isGlobal() {
        return id == null;
    }

    public Entry get(String name) {
        return entries.get(name);
    }

    /** 읽기 전용으로 쓴다 — 고치려면 {@link StateStore} 를 거친다 */
    public Map<String, Entry> entries() {
        return java.util.Collections.unmodifiableMap(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean hasPersist() {
        for (Entry e : entries.values()) if (e.persist) return true;
        return false;
    }
}
