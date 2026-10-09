package kr.asmbl.figuracontroller.bukkit;

import kr.asmbl.figuracontroller.completion.CompletionRules;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 자동완성 파일 `completions/*.yml` — **탭을 칠 때마다 폴더가 바뀌었나 보고**(파일 이름 · 크기 · 고친 시각) 바뀌었으면 다시 읽는다.
 * 그래서 파일을 저장하면 재시작 없이 다음 탭부터 반영된다. 경고는 다시 읽을 때 콘솔에 한 번씩(촬영용 출력 규율 — 친 사람 화면에는 안 낸다).
 */
final class CompletionStore {
    private final File dir;
    private final Logger log;
    private String signature;
    private CompletionRules rules = CompletionRules.empty();
    private int unreadable;

    CompletionStore(File dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    /** 폴더가 **없을 때만** 본보기 `default.yml` 을 놓는다 — 지운 파일을 되살리지 않는다 */
    void ensureDefault(JavaPlugin plugin) {
        if (dir.exists()) return;
        plugin.saveResource("completions/default.yml", false);
    }

    CompletionRules rules() {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".yml") || n.endsWith(".yaml"));
        if (fs == null) fs = new File[0];
        Arrays.sort(fs);
        StringBuilder sig = new StringBuilder();
        for (File f : fs) sig.append(f.getName()).append('\0').append(f.length()).append('\0').append(f.lastModified()).append('\n');
        String s = sig.toString();
        if (s.equals(signature)) return rules;
        signature = s;
        Map<String, Object> roots = new LinkedHashMap<>();
        int bad = 0;
        for (File f : fs) {
            try {
                roots.put(f.getName(), CompletionRules.parseYaml(Files.readString(f.toPath(), StandardCharsets.UTF_8)));
            } catch (IOException | IllegalArgumentException e) {
                bad++;
                log.warning("자동완성 파일을 못 읽었다 — " + f.getName() + ": " + e.getMessage());
            }
        }
        unreadable = bad;
        rules = CompletionRules.build(roots);
        for (String w : rules.warnings()) log.warning("자동완성 — " + w);
        return rules;
    }

    /** `/fc status` 한 줄 */
    String summary() {
        CompletionRules r = rules();
        return "자동완성 completions/ — 파일 " + r.fileCount() + (unreadable > 0 ? " §c(못 읽음 " + unreadable + ")§7" : "")
                + " · 목록 " + r.listNames().size() + " · 상태 규칙 " + r.stateRuleCount() + " · 신호 규칙 " + r.signalRuleCount()
                + (r.warnings().isEmpty() ? "" : " · §e경고 " + r.warnings().size() + "(콘솔)§7");
    }
}
