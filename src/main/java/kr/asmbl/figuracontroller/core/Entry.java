package kr.asmbl.figuracontroller.core;

import com.google.gson.JsonElement;

import java.util.Arrays;

/**
 * 상태 하나 — 바꿀 수 없다(바뀌면 새 Entry 로 갈아 끼운다). 값은 **바뀔 때 한 번** 인코딩해 둔다(받는 사람마다 같은 바이트 · 설계 §4.2).
 * {@link #value} 는 저장소 소유 — 밖으로 줄 때는 사본을 만든다.
 */
public final class Entry {
    public final JsonElement value;
    public final byte[] encoded;
    public final boolean persist;
    /** 플레이어 = 본인만 · 몹 = 서버만 · 전역은 늘 거짓 */
    public final boolean priv;
    /** 이 틱에 저절로 지운다 · -1 = 없음 */
    public final long expireTick;

    public Entry(JsonElement value, byte[] encoded, boolean persist, boolean priv, long expireTick) {
        this.value = value;
        this.encoded = encoded;
        this.persist = persist;
        this.priv = priv;
        this.expireTick = expireTick;
    }

    /** 값이 같은가 — **바이트로** 본다(Gson `equals` 는 1 과 1.0 을 같게 본다 · INT/DOUBLE 이 갈리지 않는다) */
    public boolean sameValue(Entry o) {
        return Arrays.equals(encoded, o.encoded);
    }
}
