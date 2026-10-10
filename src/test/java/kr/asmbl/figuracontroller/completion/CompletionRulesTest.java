package kr.asmbl.figuracontroller.completion;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import kr.asmbl.figuracontroller.core.ValueParse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 탭 자동완성 규칙 (T12) — 파일 읽기 · 목록 · 이름 패턴 · 범위 · $online · $used · 경고 · 기본 파일 */
class CompletionRulesTest {
    private static final CompletionRules.Context CTX = new CompletionRules.Context() {
        @Override
        public List<String> online() {
            return List.of("RATH4R", "aaaa_");
        }

        @Override
        public List<String> used(String stateName) {
            return stateName.equals("phase") ? List.of("lobby", "\"true\"") : List.of();
        }
    };

    private static CompletionRules rules(String... yamls) {
        Map<String, Object> roots = new LinkedHashMap<>();
        for (int i = 0; i < yamls.length; i++) roots.put("f" + i + ".yml", CompletionRules.parseYaml(yamls[i]));
        return CompletionRules.build(roots);
    }

    @Test
    void scalarsStayText() {
        // YAML 1.1 이면 on → true · no → false · 1:30 → 90 · 날짜 → Date 가 된다 — 후보는 적은 그대로여야 한다
        CompletionRules r = rules("states:\n  x: [on, no, '1:30', 1:30, 2026-10-10, null, true, 3, 1.50, ~, 'a b', '\"q\"', '{\"x\":1}']\n");
        assertEquals(List.of("on", "no", "1:30", "2026-10-10", "null", "true", "3", "1.50", "~", "a b", "\"q\"", "{\"x\":1}"),
                r.stateValues("x", CTX));
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
    }

    @Test
    void listsPatternsAndOrder() {
        CompletionRules r = rules(
                "lists:\n  ko: [라더, 덕개]\nstates:\n  nickName|plateName: [$ko, null]\n  \"*Name\": [extra, 라더]\n",
                "states:\n  plateName: [last]\n");
        assertEquals(List.of("라더", "덕개", "null", "extra", "last"), r.stateValues("plateName", CTX), "규칙을 파일 · 적은 순으로 합치고 겹치면 하나");
        assertEquals(List.of("라더", "덕개", "null", "extra"), r.stateValues("nickName", CTX));
        assertEquals(List.of("extra", "라더"), r.stateValues("chatName", CTX), "* 패턴");
        assertNull(r.stateValues("skin", CTX), "맞는 규칙이 없으면 null — 부르는 쪽이 true · false · null");
        assertEquals(List.of("nickName", "plateName"), new ArrayList<>(r.stateNames()), "정확한 이름만 이름 후보로 — * 패턴은 아니다");
    }

    @Test
    void dynamicSources() {
        CompletionRules r = rules("states:\n  seeker: [$online, none]\n  phase: [$used, lobby, end]\n  disguise: [0..3]\n  money: $$5\n");
        assertEquals(List.of("RATH4R", "aaaa_", "none"), r.stateValues("seeker", CTX));
        assertEquals(List.of("lobby", "\"true\"", "end"), r.stateValues("phase", CTX), "$used 다음 글자 · 겹치면 하나");
        assertEquals(List.of("0", "1", "2", "3"), r.stateValues("disguise", CTX));
        assertEquals(List.of("$5"), r.stateValues("money", CTX), "글 하나도 된다 · $$ 는 $ 로 시작하는 글자");
    }

    @Test
    void rangeCapAndReverse() {
        CompletionRules r = rules("states:\n  big: [0..1000]\n  bad: [5..1]\n");
        assertEquals(CompletionRules.MAX_RANGE, r.stateValues("big", CTX).size());
        assertEquals(List.of(), r.stateValues("bad", CTX), "거꾸로 된 범위는 건너뛴다 — 규칙은 남아 기본 후보도 안 뜬다");
        assertEquals(2, r.warnings().size(), r.warnings().toString());
    }

    @Test
    void emptyListSuppressesDefaults() {
        assertEquals(List.of(), rules("states:\n  secret: []\n").stateValues("secret", CTX));
    }

    @Test
    void signals() {
        CompletionRules r = rules("lists:\n  pw: ['{\"power\":1}']\nsignals:\n  SCREENSHAKE: [$pw, $used]\n  \"HIT_*\": [$online]\n");
        assertEquals(List.of("{\"power\":1}"), r.signalData("SCREENSHAKE", CTX), "$used 는 신호에 없다 — 빼고 경고");
        assertEquals(List.of("RATH4R", "aaaa_"), r.signalData("HIT_A", CTX));
        assertEquals(List.of("SCREENSHAKE"), new ArrayList<>(r.signalNames()));
        assertNull(r.stateValues("SCREENSHAKE", CTX), "상태와 신호는 따로다");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("$used")), r.warnings().toString());
    }

    @Test
    void mistakesAreSkippedWithWarnings() {
        CompletionRules r = rules(
                "states:\n  a: [$nope, ok]\n  b: {x: 1}\n  c: [[1, 2], fine]\nlist:\n  x: [1]\n",
                "- just a list\n",
                "");
        assertEquals(List.of("ok"), r.stateValues("a", CTX), "모르는 목록은 건너뛰고 나머지는 산다");
        assertNull(r.stateValues("b", CTX));
        assertEquals(List.of("fine"), r.stateValues("c", CTX));
        String all = String.join("\n", r.warnings());
        assertTrue(all.contains("$nope") && all.contains("f0.yml: states.b") && all.contains("목록 안에 목록")
                && all.contains("모르는 칸 «list»") && all.contains("f1.yml: 맨 위"), all);
        assertEquals(3, r.fileCount(), "빈 파일도 센다");
        assertThrows(IllegalArgumentException.class, () -> CompletionRules.parseYaml("states: [unclosed"));
    }

    @Test
    void listCannotCallList() {
        CompletionRules r = rules("lists:\n  a: [x, $b, $$c]\n  b: [y]\nstates:\n  s: [$a]\n");
        assertEquals(List.of("x", "$b", "$c"), r.stateValues("s", CTX), "목록 안의 $이름 은 글자로 둔다 · $$ 는 $");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("$b")), r.warnings().toString());
    }

    @Test
    void renderArgRoundTrips() {
        assertEquals("RATH4R", CompletionRules.renderArg(new JsonPrimitive("RATH4R")));
        assertEquals("라더", CompletionRules.renderArg(new JsonPrimitive("라더")));
        assertEquals("\"true\"", CompletionRules.renderArg(new JsonPrimitive("true")), "글 true 는 따옴표째 — 아니면 불로 읽힌다");
        assertEquals("\"3\"", CompletionRules.renderArg(new JsonPrimitive("3")));
        assertEquals("\"a b\"", CompletionRules.renderArg(new JsonPrimitive("a b")));
        assertEquals("\"\"", CompletionRules.renderArg(new JsonPrimitive("")));
        assertEquals("3", CompletionRules.renderArg(new JsonPrimitive(3)));
        assertEquals("true", CompletionRules.renderArg(new JsonPrimitive(true)));
        assertEquals("null", CompletionRules.renderArg(JsonNull.INSTANCE));
        assertEquals("{\"x\":[1,2]}", CompletionRules.renderArg(JsonParser.parseString("{\"x\": [1, 2]}")));
        for (String s : List.of("RATH4R", "\"true\"", "\"a b\"", "3", "null", "{\"x\":[1,2]}")) {
            assertEquals(s, CompletionRules.renderArg(ValueParse.jsonOrString(s)), "다시 치면 같은 값: " + s);
        }
        assertEquals(List.of("a", "\"true\""), CompletionRules.renderAll(List.of(new JsonPrimitive("a"), new JsonPrimitive("true"), new JsonPrimitive("a"))));
    }

    @Test
    void shippedDefaultFile() throws IOException {
        String text;
        try (InputStream in = CompletionRulesTest.class.getResourceAsStream("/completions/default.yml")) {
            assertTrue(in != null, "jar 에 completions/default.yml 이 있어야 한다");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, Object> roots = new LinkedHashMap<>();
        roots.put("default.yml", CompletionRules.parseYaml(text));
        CompletionRules r = CompletionRules.build(roots);
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
        List<String> skin = r.stateValues("skin", CTX);
        List<String> nick = r.stateValues("plateName", CTX);
        assertEquals(15, skin.size(), "탤런트 14 + null");
        assertEquals(skin.size(), nick.size(), "계정 · 한글 목록은 짝이 맞아야 한다");
        assertEquals("RATH4R", skin.get(0));
        assertEquals("라더", nick.get(0));
        assertEquals("null", skin.get(skin.size() - 1));
        assertFalse(r.stateNames().isEmpty());
        assertTrue(r.stateNames().containsAll(List.of("skin", "nickName", "listName", "chatName", "plateName", "isArmWide", "showNametag",
                "nametagShowSpectator", "nametagShowInvisibility")));
    }
}
