package kr.asmbl.figuracontroller.api;

import com.google.gson.JsonElement;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 상태가 **바뀐 뒤** (메인 스레드 · 동기). 값 · 공개 범위가 그대로인 `set` 에는 안 난다.
 * 처리기 안에서 다시 `set` 해도 된다(그 변화도 이벤트가 난다 — 끝없이 돌지 않게 조심).
 */
public final class FiguraStateChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID subject;
    private final String name;
    private final JsonElement oldValue;
    private final JsonElement newValue;
    private final ChangeCause cause;

    public FiguraStateChangeEvent(@Nullable UUID subject, String name, @Nullable JsonElement oldValue,
                                  @Nullable JsonElement newValue, ChangeCause cause) {
        this.subject = subject;
        this.name = name;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.cause = cause;
    }

    /** 대상 엔티티(플레이어 · 몹) UUID · 전역이면 null */
    public @Nullable UUID getSubject() {
        return subject;
    }

    public boolean isGlobal() {
        return subject == null;
    }

    public String getName() {
        return name;
    }

    /** 옛 값(사본) · 없었으면 null — JSON null 은 {@code JsonNull} */
    public @Nullable JsonElement getOldValue() {
        return oldValue == null ? null : oldValue.deepCopy();
    }

    /** 새 값(사본) · 지워졌으면 null */
    public @Nullable JsonElement getNewValue() {
        return newValue == null ? null : newValue.deepCopy();
    }

    public ChangeCause getCause() {
        return cause;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
