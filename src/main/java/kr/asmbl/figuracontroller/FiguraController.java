package kr.asmbl.figuracontroller;

import kr.asmbl.figuracontroller.api.FiguraControllerAPI;

/** 다른 플러그인의 입구 — {@code FiguraController.api().set(player, "skin", new JsonPrimitive("RATH4R"))} */
public final class FiguraController {
    private static volatile FiguraControllerAPI api;

    private FiguraController() {}

    /** @throws IllegalStateException 플러그인이 꺼져 있으면 */
    public static FiguraControllerAPI api() {
        FiguraControllerAPI a = api;
        if (a == null) throw new IllegalStateException("FiguraController 가 켜져 있지 않다");
        return a;
    }

    public static boolean isEnabled() {
        return api != null;
    }

    /** ⚠ 플러그인 본체 전용 — 다른 플러그인은 부르지 마라 */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void install(FiguraControllerAPI a) {
        api = a;
    }
}
