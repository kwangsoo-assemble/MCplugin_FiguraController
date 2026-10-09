package kr.asmbl.figuracontroller.protocol;

/**
 * figuracontroller:v1 — 서버 플러그인 ↔ 포크 코어 이진 규약의 상수.
 * <p>
 * ★ 이 패키지(`protocol`)의 원본은 FiguraController 플러그인 저장소다. 포크 코어에는 **같은 파일을 그대로** 복사한다
 * (FSB `server-common` 과 같은 운영 — 하네스 `projects/figura_controller/tests/verify_codec.py` 가 두 쪽이 같은지 잰다).
 * 바꿀 때는 하네스의 파이썬 참조 구현(`fc_codec_ref.py`)도 같이 고치고 시험 벡터를 다시 만든다.
 * <p>
 * 실어 나르는 것: FSB `CustomFSBPacket`(`figura:ping/server`)의 `id` = {@link #CHANNEL_ID}, 몸 = 아래 op 줄.
 */
public final class Protocol {
    public static final String CHANNEL = "figuracontroller:v1";
    /** FSB 는 이름 글자가 아니라 `String.hashCode()` 를 싣는다 */
    public static final int CHANNEL_ID = CHANNEL.hashCode();
    /** 클라가 HELLO 로 알리는 규약판 */
    public static final int VERSION = 1;
    /** 한 패킷 몸 상한 — FSB 한도(32,746 B)에 여유를 둔다 */
    public static final int MAX_BODY = 30_000;
    /** 값 하나의 인코딩 상한 — 이보다 크면 `set` 이 거절한다(op 머리 · 대상 몫 여유) */
    public static final int MAX_VALUE = 29_900;
    /** 묶음(ARRAY · OBJECT) 깊이 상한 */
    public static final int MAX_DEPTH = 32;

    // ── 서버 → 클라 op
    /** uvarint id · string — 이름 사전(클라 세션 단위 · 패킷을 넘어 산다) */
    public static final int OP_NAME = 1;
    /** uuid — 다음 op 들의 대상(패킷 안에서만 산다) */
    public static final int OP_SUBJECT = 2;
    /** 대상 = 전역 */
    public static final int OP_GLOBAL = 3;
    /** uvarint nameId · value */
    public static final int OP_SET = 4;
    /** uvarint nameId */
    public static final int OP_UNSET = 5;
    /** uvarint total · uvarint n · (uvarint nameId · value) × n — 이 대상을 total 개로 갈아 끼운다 */
    public static final int OP_FULL = 6;
    /** 이 대상을 클라 저장소에서 지운다 */
    public static final int OP_DROP = 7;
    /** uvarint nameId · value — 일회성 신호 */
    public static final int OP_SIGNAL = 8;
    /** 클라 저장소 · 이름 사전을 전부 비운다(Flashback 스냅샷 머리) */
    public static final int OP_RESET = 9;
    /** uvarint n · (uvarint nameId · value) × n — 앞 FULL 의 나머지(패킷을 넘을 때) */
    public static final int OP_FULL_CONT = 10;

    // ── 클라 → 서버 op (한 패킷 = op 하나)
    /** uvarint 규약판 */
    public static final int C2S_HELLO = 1;
    /** string name · value */
    public static final int C2S_SIGNAL = 2;

    // ── 값 형
    public static final int T_NULL = 0;
    public static final int T_FALSE = 1;
    public static final int T_TRUE = 2;
    /** zigzag varint 64 */
    public static final int T_INT = 3;
    /** 8 바이트 · 큰 끝 */
    public static final int T_DOUBLE = 4;
    /** uvarint 바이트 수 + UTF-8 */
    public static final int T_STRING = 5;
    /** uvarint n + 값 n */
    public static final int T_ARRAY = 6;
    /** uvarint n + (string 키 · 값) n */
    public static final int T_OBJECT = 7;

    private Protocol() {}

    /** 대상(SUBJECT · GLOBAL)이 먼저 있어야 하는 op */
    public static boolean needsContext(int op) {
        return op == OP_SET || op == OP_UNSET || op == OP_FULL || op == OP_DROP || op == OP_SIGNAL || op == OP_FULL_CONT;
    }
}
