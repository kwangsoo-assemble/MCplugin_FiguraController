package kr.asmbl.figuracontroller.protocol;

/** 규약 위반 — 읽기(깨진 패킷) · 쓰기(상한 초과 · 대상 없음) 둘 다 */
public final class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }
}
