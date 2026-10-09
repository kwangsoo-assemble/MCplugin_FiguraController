package kr.asmbl.figuracontroller.api;

import com.google.gson.JsonElement;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 서버가 주인인 Figura 상태 · 신호 (설계 §4.5). 얻는 법: {@code FiguraController.api()} 또는 Bukkit {@code ServicesManager}.
 * <ul>
 *   <li>모든 호출은 **메인 스레드**에서 — 아니면 {@link IllegalStateException}.</li>
 *   <li>`set` 은 즉시 저장소에 반영된다(같은 틱의 `get` 이 새 값을 본다) · 클라로 보내는 것은 **틱 끝에 한 번**(같은 틱에 여러 번 바꾸면 마지막 값).</li>
 *   <li>값은 Gson {@link JsonElement} — 넣을 때 · 꺼낼 때 사본을 만든다(밖에서 고쳐도 저장소는 안 바뀐다).</li>
 *   <li>대상 = 엔티티 UUID(플레이어 · 몹) · 전역은 {@link #global()}.</li>
 * </ul>
 */
public interface FiguraControllerAPI {

    Optional<JsonElement> get(UUID subject, String name);

    /** 그 대상의 상태 전부(사본 · 이름 순서 = 넣은 순서) */
    Map<String, JsonElement> getAll(UUID subject);

    /** @return 값이나 공개 범위가 바뀌었나(그대로면 아무것도 안 보낸다) */
    boolean set(UUID subject, String name, JsonElement value);

    boolean set(UUID subject, String name, JsonElement value, StateOptions options);

    /** @return 있던 것을 지웠나 */
    boolean unset(UUID subject, String name);

    /** @return 지운 개수 */
    int clear(UUID subject);

    /** 전역 + 메모리의 모든 대상 + 저장 파일(접속 안 한 플레이어 포함). 내려간 청크의 몹 PDC 는 못 지운다. @return 지운 개수 */
    int clearAll();

    /** 신호 — 받는 사람 {@link Audience#TRACKERS} */
    void sendSignal(UUID subject, String name, JsonElement data);

    void sendSignal(UUID subject, String name, JsonElement data, Audience audience);

    GlobalStates global();

    /** 이 플레이어의 클라가 HELLO 를 보냈나(새 코어 · 받을 준비) */
    boolean isReady(UUID player);

    /** 프리셋을 대상들에 적용 · @return 바뀐 상태 수 */
    int applyPreset(String preset, Collection<UUID> subjects);

    List<String> presets();

    // ── 엔티티 편의

    default Optional<JsonElement> get(Entity subject, String name) {
        return get(subject.getUniqueId(), name);
    }

    default Map<String, JsonElement> getAll(Entity subject) {
        return getAll(subject.getUniqueId());
    }

    default boolean set(Entity subject, String name, JsonElement value) {
        return set(subject.getUniqueId(), name, value);
    }

    default boolean set(Entity subject, String name, JsonElement value, StateOptions options) {
        return set(subject.getUniqueId(), name, value, options);
    }

    default boolean unset(Entity subject, String name) {
        return unset(subject.getUniqueId(), name);
    }

    default int clear(Entity subject) {
        return clear(subject.getUniqueId());
    }

    default void sendSignal(Entity subject, String name, JsonElement data) {
        sendSignal(subject.getUniqueId(), name, data);
    }

    default void sendSignal(Entity subject, String name, JsonElement data, Audience audience) {
        sendSignal(subject.getUniqueId(), name, data, audience);
    }

    default boolean isReady(Player player) {
        return isReady(player.getUniqueId());
    }

    /** 전역 상태 — 늘 전원(준비된 클라)에게 */
    interface GlobalStates {
        Optional<JsonElement> get(String name);

        Map<String, JsonElement> getAll();

        boolean set(String name, JsonElement value);

        /** {@code privateState} 는 무시된다 */
        boolean set(String name, JsonElement value, StateOptions options);

        boolean unset(String name);

        int clear();

        /** 전원에게 */
        void sendSignal(String name, JsonElement data);
    }
}
