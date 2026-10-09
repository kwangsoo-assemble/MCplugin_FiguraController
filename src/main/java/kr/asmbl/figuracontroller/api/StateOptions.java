package kr.asmbl.figuracontroller.api;

/**
 * 상태 하나의 속성 (설계 §2.3).
 *
 * @param persist      서버가 저장한다(플레이어 = 파일 · 몹 = PDC · 전역 = 파일) — 끄면 «잠깐 쓰는 것»(리로드 · 재시작 · 몹 청크 내림에 사라진다)
 * @param privateState 플레이어면 **그 플레이어 본인 클라에만** 보낸다 · 몹이면 **서버에만**(안 보낸다) · 전역은 무시(늘 전원)
 * @param expireTicks  0 보다 크면 그 틱 뒤 저절로 지운다(`unset`)
 */
public record StateOptions(boolean persist, boolean privateState, int expireTicks) {
    /** 저장 켬 · 공개 · 만료 없음 */
    public static final StateOptions DEFAULT = new StateOptions(true, false, 0);

    public StateOptions {
        if (expireTicks < 0) throw new IllegalArgumentException("expireTicks < 0");
    }

    public StateOptions withPersist(boolean v) {
        return new StateOptions(v, privateState, expireTicks);
    }

    public StateOptions withPrivate(boolean v) {
        return new StateOptions(persist, v, expireTicks);
    }

    public StateOptions withExpireTicks(int v) {
        return new StateOptions(persist, privateState, v);
    }
}
