package kr.asmbl.figuracontroller.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 신호를 받는 사람 (설계 §2.5). 기본은 {@link #TRACKERS}.
 * ⚠ 전역 연출(관전 스태프까지 같은 화면을 봐야 하는 것)은 {@link #ALL} 을 쓴다 — 하네스 `production_rules.md`.
 * 전역 신호는 대상이 없으므로 {@link #players} 가 아니면 언제나 전원이다.
 */
public final class Audience {
    public enum Kind { SELF, TRACKERS, ALL, PLAYERS }

    /** 그 플레이어 본인만(대상이 몹이면 아무도 없다) */
    public static final Audience SELF = new Audience(Kind.SELF, Set.of());
    /** 그 대상을 보는 클라 + (플레이어면) 본인 */
    public static final Audience TRACKERS = new Audience(Kind.TRACKERS, Set.of());
    /** 준비된(HELLO 를 보낸) 모든 클라 */
    public static final Audience ALL = new Audience(Kind.ALL, Set.of());

    private final Kind kind;
    private final Set<UUID> players;

    private Audience(Kind kind, Set<UUID> players) {
        this.kind = kind;
        this.players = players;
    }

    /** 이 플레이어들만(준비된 사람 중에서) */
    public static Audience players(Collection<UUID> players) {
        return new Audience(Kind.PLAYERS, Set.copyOf(players));
    }

    public Kind kind() {
        return kind;
    }

    public Set<UUID> playerSet() {
        return players;
    }

    /** `self` · `trackers` · `all` (명령 `--to`) */
    public static Audience parse(String s) {
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "self" -> SELF;
            case "trackers" -> TRACKERS;
            case "all" -> ALL;
            default -> throw new IllegalArgumentException("audience: self · trackers · all");
        };
    }

    @Override
    public String toString() {
        return kind == Kind.PLAYERS ? "players" + players : kind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
