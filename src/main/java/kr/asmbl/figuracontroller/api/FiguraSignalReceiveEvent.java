package kr.asmbl.figuracontroller.api;

import com.google.gson.JsonElement;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 아바타 → 서버 신호 (메인 스레드). **호스트 아바타만** — 남의 아바타 · 몹 아바타가 보낸 것은 버린다(설계 §4.5 · research §9-4).
 */
public final class FiguraSignalReceiveEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String name;
    private final JsonElement data;

    public FiguraSignalReceiveEvent(Player player, String name, JsonElement data) {
        super(player);
        this.name = name;
        this.data = data;
    }

    public String getName() {
        return name;
    }

    /** 데이터(사본) — 없으면 {@code JsonNull} */
    public JsonElement getData() {
        return data.deepCopy();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
