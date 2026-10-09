package kr.asmbl.figuracontroller.api;

/** 상태가 왜 바뀌었나 — {@link FiguraStateChangeEvent#getCause()} */
public enum ChangeCause {
    /** Java API(팀원 플러그인 · CHFeatures) */
    API,
    /** `/fc` 명령 */
    COMMAND,
    /** 프리셋 적용 */
    PRESET,
    /** 만료(`expireTicks`) */
    EXPIRE,
    /** `clear` */
    CLEAR,
    /** 옛 CH 저장 가져오기 */
    IMPORT
}
