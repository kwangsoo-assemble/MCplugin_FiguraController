package kr.asmbl.figuracontroller.core;

import java.util.Collection;
import java.util.UUID;

/** {@link Dispatcher} 가 세상에 묻는 것 — Bukkit 구현은 {@code BukkitView} · 시험은 가짜 */
public interface WorldView {
    /** 이 대상을 지금 보는(트래킹하는) 플레이어 — **대상 자신은 빼고** */
    Collection<UUID> trackers(UUID subject);

    /** 이 UUID 가 접속한 플레이어인가 — 비공개 상태를 «본인에게만» 보낼지 «아무에게도 안» 보낼지(몹) 가른다 */
    boolean isPlayer(UUID subject);
}
