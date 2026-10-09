package kr.asmbl.figuracontroller.completion;

import com.google.gson.JsonElement;
import kr.asmbl.figuracontroller.core.ValueParse;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * `/fc` 탭 자동완성 규칙 — 데이터 폴더 `completions/*.yml` 을 **전부 합쳐** 쓴다 (2026-10-10 · 하네스 projects/figura_controller T12).
 * <pre>
 * lists:   이름 → 값 목록                 (후보에서 $이름 으로 부른다)
 * states:  상태 이름 → 값 후보             (/fc state set &lt;대상&gt; &lt;이름&gt; &lt;값&gt;)
 * signals: 신호 이름 → 데이터 후보          (/fc signal &lt;대상&gt; &lt;이름&gt; [데이터])
 * </pre>
 * 이름 칸 = 정확한 이름 · {@code *}(아무 글자) · {@code |}(여럿). 후보 하나 = 글자 그대로 · {@code $목록} · {@code $online} ·
 * {@code $used}(상태만) · {@code 시작..끝}(정수) · {@code $$글}({@code $} 로 시작하는 글자).
 * <p>
 * ⓘ `core` 가 아니라 따로 둔 까닭 — `core` 는 **Gson 만 쓰는 Bukkit 없는 층**이라는 계약이 있다(하네스 `projects/figura_controller/tests/verify_core_serverdata.py` 가
 * 그 층만 javac 로 묶어 클라 모델과 이어 돌린다). 이 클래스는 SnakeYAML 을 쓴다.
 * ★ YAML 은 **글자로만** 읽는다(암묵 해석기를 껐다) — 후보는 «적은 그대로» 명령에 들어가야 하고,
 * YAML 1.1 은 {@code on} · {@code no} · {@code 1:30} · {@code 2026-10-10} 을 불 · 수 · 날짜로 바꿔 버린다(하네스 기억 «YAML boolean 키 함정»).
 */
public final class CompletionRules {
    /** 정수 범위 하나가 낼 수 있는 후보 수 */
    public static final int MAX_RANGE = 200;
    /** {@code $used} 가 낼 수 있는 후보 수 */
    public static final int MAX_USED = 50;

    private static final Pattern RANGE = Pattern.compile("(-?[0-9]{1,9})\\.\\.(-?[0-9]{1,9})");

    /** 후보를 펼 때 바깥에서 받는 것 */
    public interface Context {
        /** 지금 접속한 플레이어 이름 */
        List<String> online();

        /** 지금 메모리에 있는 그 상태의 값들 — {@link #renderArg} 꼴 */
        List<String> used(String stateName);
    }

    private record Rule(List<Pattern> patterns, List<String> sources, String where) {}

    private final Map<String, List<String>> lists;
    private final List<Rule> states;
    private final List<Rule> signals;
    private final Set<String> stateNames;
    private final Set<String> signalNames;
    private final List<String> warnings;
    private final int files;

    private CompletionRules(Map<String, List<String>> lists, List<Rule> states, List<Rule> signals, Set<String> stateNames,
                            Set<String> signalNames, List<String> warnings, int files) {
        this.lists = lists;
        this.states = states;
        this.signals = signals;
        this.stateNames = stateNames;
        this.signalNames = signalNames;
        this.warnings = warnings;
        this.files = files;
    }

    public static CompletionRules empty() {
        return new CompletionRules(Map.of(), List.of(), List.of(), Set.of(), Set.of(), List.of(), 0);
    }

    // ── 읽기

    /** YAML 을 **글자로만** 읽는다 — 맵 · 목록 · 글(String). 문법 오류면 {@link IllegalArgumentException} */
    public static Object parseYaml(String text) {
        LoaderOptions lo = new LoaderOptions();
        DumperOptions dumper = new DumperOptions();
        Resolver textOnly = new Resolver() {
            @Override
            protected void addImplicitResolvers() {
                // 비운다 — 따옴표 없는 글도 전부 글(tag:str)
            }
        };
        try {
            return new Yaml(new SafeConstructor(lo), new Representer(dumper), dumper, lo, textOnly).load(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage() == null ? e.toString() : e.getMessage().replace('\n', ' '), e);
        }
    }

    /**
     * 파일들(이름 순 · {@link #parseYaml} 결과)로 규칙을 만든다. 잘못 적은 것은 **건너뛰고** {@link #warnings()} 에 남긴다 —
     * 자동완성 하나 틀렸다고 나머지가 죽으면 안 된다.
     */
    public static CompletionRules build(Map<String, Object> roots) {
        List<String> warn = new ArrayList<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        // 목록 먼저 — 어느 파일의 규칙이든 어느 파일의 목록을 부를 수 있다
        for (Map.Entry<String, Object> f : roots.entrySet()) {
            Map<String, Object> root = rootMap(f.getKey(), f.getValue(), warn);
            if (root == null) continue;
            Object ls = root.get("lists");
            if (ls == null) continue;
            if (!(ls instanceof Map<?, ?> lm)) {
                warn.add(f.getKey() + ": lists 는 «이름: [값, …]» 꼴이어야 한다");
                continue;
            }
            for (Map.Entry<?, ?> e : lm.entrySet()) {
                String name = String.valueOf(e.getKey());
                String where = f.getKey() + ": lists." + name;
                List<String> items = scalars(e.getValue(), where, warn);
                if (items == null) continue;
                for (String it : items) if (it.startsWith("$") && !it.startsWith("$$")) warn.add(where + " — 목록 안에서는 " + it + " 를 못 부른다(글자로 둔다)");
                List<String> into = lists.computeIfAbsent(name, k -> new ArrayList<>());
                if (!into.isEmpty()) warn.add(where + " — 같은 이름의 목록이 이미 있다 · 뒤에 이어 붙인다");
                for (String it : items) into.add(it.startsWith("$$") ? it.substring(1) : it);
            }
        }
        List<Rule> states = new ArrayList<>();
        List<Rule> signals = new ArrayList<>();
        Set<String> stateNames = new TreeSet<>();
        Set<String> signalNames = new TreeSet<>();
        for (Map.Entry<String, Object> f : roots.entrySet()) {
            Map<String, Object> root = rootMap(f.getKey(), f.getValue(), null);
            if (root == null) continue;
            for (String k : root.keySet()) {
                if (!k.equals("lists") && !k.equals("states") && !k.equals("signals")) warn.add(f.getKey() + ": 모르는 칸 «" + k + "» — lists · states · signals 만 읽는다");
            }
            rules(f.getKey(), "states", root.get("states"), lists, states, stateNames, false, warn);
            rules(f.getKey(), "signals", root.get("signals"), lists, signals, signalNames, true, warn);
        }
        return new CompletionRules(freeze(lists), List.copyOf(states), List.copyOf(signals), Collections.unmodifiableSet(stateNames),
                Collections.unmodifiableSet(signalNames), List.copyOf(warn), roots.size());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rootMap(String file, Object root, List<String> warn) {
        if (root == null) return Map.of();                      // 빈 파일
        if (root instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        if (warn != null) warn.add(file + ": 맨 위가 «lists: / states: / signals:» 꼴이 아니다 — 파일을 건너뛴다");
        return null;
    }

    private static void rules(String file, String section, Object raw, Map<String, List<String>> lists, List<Rule> out,
                              Set<String> exactNames, boolean signal, List<String> warn) {
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> m)) {
            if (!(raw instanceof String s && s.isEmpty())) warn.add(file + ": " + section + " 는 «이름: [후보, …]» 꼴이어야 한다");
            return;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            String where = file + ": " + section + "." + key;
            List<String> sources = scalars(e.getValue(), where, warn);
            if (sources == null) continue;
            List<String> kept = new ArrayList<>();
            for (String s : sources) {
                if (s.startsWith("$$") || !s.startsWith("$")) {
                    Matcher r = RANGE.matcher(s);
                    if (r.matches()) {
                        long a = Long.parseLong(r.group(1));
                        long b = Long.parseLong(r.group(2));
                        if (a > b) {
                            warn.add(where + " — 범위 " + s + " 는 작은 수가 먼저다 · 건너뛴다");
                            continue;
                        }
                        if (b - a + 1 > MAX_RANGE) warn.add(where + " — 범위 " + s + " 가 " + MAX_RANGE + "개를 넘는다 · 앞 " + MAX_RANGE + "개만");
                    }
                    kept.add(s);
                } else if (s.equals("$online")) {
                    kept.add(s);
                } else if (s.equals("$used")) {
                    if (signal) warn.add(where + " — $used 는 상태에만 있다(신호는 저장하지 않는다) · 건너뛴다");
                    else kept.add(s);
                } else if (lists.containsKey(s.substring(1))) {
                    kept.add(s);
                } else {
                    warn.add(where + " — 모르는 목록 " + s + " · 건너뛴다(목록은 lists: 에 · 고정 이름은 $online · $used)");
                }
            }
            List<Pattern> pats = new ArrayList<>();
            for (String alt : key.split("\\|", -1)) {
                if (alt.isEmpty()) {
                    warn.add(where + " — 빈 이름(| 앞뒤) · 건너뛴다");
                    continue;
                }
                pats.add(glob(alt));
                if (!alt.contains("*")) exactNames.add(alt);
            }
            if (!pats.isEmpty()) out.add(new Rule(List.copyOf(pats), List.copyOf(kept), where));
        }
    }

    /** 글 하나 · 글 목록 → 목록. 그 밖(맵 · 목록 안의 목록)은 경고 */
    private static List<String> scalars(Object v, String where, List<String> warn) {
        if (v instanceof String s) return List.of(s);
        if (!(v instanceof List<?> l)) {
            warn.add(where + " — 후보는 [값, …] 꼴이어야 한다 · 건너뛴다");
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof String s) out.add(s);
            else warn.add(where + " — 목록 안에 목록 · 맵은 못 쓴다 · 그 칸을 건너뛴다(JSON 값이면 따옴표로 감싼다: '{\"x\":1}')");
        }
        return out;
    }

    private static Pattern glob(String s) {
        StringBuilder b = new StringBuilder();
        int from = 0;
        for (int i = s.indexOf('*'); i >= 0; i = s.indexOf('*', from)) {
            b.append(Pattern.quote(s.substring(from, i))).append(".*");
            from = i + 1;
        }
        b.append(Pattern.quote(s.substring(from)));
        return Pattern.compile(b.toString());
    }

    private static Map<String, List<String>> freeze(Map<String, List<String>> m) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : m.entrySet()) out.put(e.getKey(), List.copyOf(e.getValue()));
        return Collections.unmodifiableMap(out);
    }

    // ── 쓰기

    /** 그 상태의 값 후보 — 맞는 규칙이 하나도 없으면 null(부르는 쪽이 기본 후보를 낸다) */
    public List<String> stateValues(String name, Context ctx) {
        return expand(states, name, ctx);
    }

    /** 그 신호의 데이터 후보 — 맞는 규칙이 없으면 null */
    public List<String> signalData(String name, Context ctx) {
        return expand(signals, name, ctx);
    }

    private List<String> expand(List<Rule> rules, String name, Context ctx) {
        LinkedHashSet<String> out = null;
        for (Rule r : rules) {
            boolean hit = false;
            for (Pattern p : r.patterns()) if (p.matcher(name).matches()) hit = true;
            if (!hit) continue;
            if (out == null) out = new LinkedHashSet<>();
            for (String s : r.sources()) {
                if (s.startsWith("$$")) out.add(s.substring(1));
                else if (s.equals("$online")) out.addAll(ctx.online());
                else if (s.equals("$used")) {
                    List<String> u = ctx.used(name);
                    out.addAll(u.size() > MAX_USED ? u.subList(0, MAX_USED) : u);
                } else if (s.startsWith("$")) out.addAll(lists.getOrDefault(s.substring(1), List.of()));
                else {
                    Matcher m = RANGE.matcher(s);
                    if (m.matches()) {
                        long a = Long.parseLong(m.group(1));
                        long b = Math.min(Long.parseLong(m.group(2)), a + MAX_RANGE - 1);
                        for (long i = a; i <= b; i++) out.add(Long.toString(i));
                    } else out.add(s);
                }
            }
        }
        return out == null ? null : new ArrayList<>(out);
    }

    /** 규칙에 정확히 적힌 상태 이름 — 아무도 아직 안 가졌어도 이름 자동완성에 띄운다 */
    public Set<String> stateNames() {
        return stateNames;
    }

    public Set<String> signalNames() {
        return signalNames;
    }

    public List<String> warnings() {
        return warnings;
    }

    public int fileCount() {
        return files;
    }

    public int stateRuleCount() {
        return states.size();
    }

    public int signalRuleCount() {
        return signals.size();
    }

    public Set<String> listNames() {
        return lists.keySet();
    }

    /**
     * 값을 «명령에 칠 꼴» 로 — 그대로 다시 쳤을 때 같은 값이 되게({@link ValueParse#jsonOrString}).
     * 글 {@code RATH4R} → {@code RATH4R} · 글 {@code "true"} → {@code "true"}(따옴표째 — 아니면 불이 된다) · 띄어 쓴 글 → JSON 글.
     */
    public static String renderArg(JsonElement v) {
        if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
            String s = v.getAsString();
            if (!s.isEmpty() && s.chars().noneMatch(Character::isWhitespace) && ValueParse.jsonOrString(s).equals(v)) return s;
        }
        return String.valueOf(v);
    }

    /** {@code $used} 꼴 — 값들을 처음 본 순서로 · 겹치면 하나 */
    public static List<String> renderAll(Collection<JsonElement> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (JsonElement v : values) {
            out.add(renderArg(v));
            if (out.size() >= MAX_USED) break;
        }
        return new ArrayList<>(out);
    }
}
