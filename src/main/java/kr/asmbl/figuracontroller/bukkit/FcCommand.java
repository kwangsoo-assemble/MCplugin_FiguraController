package kr.asmbl.figuracontroller.bukkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.asmbl.figuracontroller.api.Audience;
import kr.asmbl.figuracontroller.api.ChangeCause;
import kr.asmbl.figuracontroller.api.StateOptions;
import kr.asmbl.figuracontroller.core.Dispatcher;
import kr.asmbl.figuracontroller.core.Entry;
import kr.asmbl.figuracontroller.core.LegacyImport;
import kr.asmbl.figuracontroller.core.PresetCodec;
import kr.asmbl.figuracontroller.core.StateStore;
import kr.asmbl.figuracontroller.core.SubjectState;
import kr.asmbl.figuracontroller.core.ValueParse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * `/fc` (설계 §4.6). 대상 = 플레이어 이름 · 선택자(`@e[type=husk,limit=1]`) · UUID · `global`.
 * 값 = 엄격한 JSON(`true` · `3` · `1.5` · `"글"` · `[1,2]` · `{"x":1}` · `null`) · 아니면 글 그대로(`RATH4R`).
 * <p>
 * 출력 — 촬영용 출력 규율: **성공 확인은 기본 끔**(`config.yml` `commandFeedback`) · 오류와 조회(get · status · preset list/show/save/delete · import)는 친 사람에게 ·
 * 콘솔은 조용히. ★ 옛 문법 별칭(임시 — T11 에서 지운다): `/fc <대상> state [이름] [값|~none]` · `/fc <대상> signal <이름> [데이터]` — 아바타가 직접 친다(research §6 ⚠).
 */
final class FcCommand implements TabExecutor {
    interface Host {
        Settings settings();

        String version();

        /** 보낸 패킷 · 바이트 (켠 뒤로) */
        long[] sentStats();

        java.util.logging.Logger logger();
    }

    private static final List<String> SUBS = List.of("state", "signal", "preset", "status", "import-legacy");

    private final Host host;
    private final ApiImpl api;
    private final StateStore store;
    private final Dispatcher dispatcher;
    private final Storage storage;
    private final PresetStore presets;
    private boolean legacyWarned;

    FcCommand(Host host, ApiImpl api, StateStore store, Dispatcher dispatcher, Storage storage, PresetStore presets) {
        this.host = host;
        this.api = api;
        this.store = store;
        this.dispatcher = dispatcher;
        this.storage = storage;
        this.presets = presets;
    }

    static final class CmdError extends RuntimeException {
        CmdError(String m) {
            super(m);
        }
    }

    record Flags(boolean priv, boolean transientState, int expire, Audience to, boolean dryRun, List<String> rest) {}

    static Flags flags(String[] args, int from) {
        boolean priv = false;
        boolean tr = false;
        boolean dry = false;
        int exp = 0;
        Audience to = null;
        List<String> rest = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--private" -> priv = true;
                case "--transient" -> tr = true;
                case "--dry-run" -> dry = true;
                case "--expire" -> {
                    if (i + 1 >= args.length) throw new CmdError("--expire <틱>");
                    try {
                        exp = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        throw new CmdError("--expire 는 틱(정수)이다");
                    }
                    if (exp <= 0) throw new CmdError("--expire 는 1 이상");
                }
                case "--to" -> {
                    if (i + 1 >= args.length) throw new CmdError("--to self|trackers|all");
                    try {
                        to = Audience.parse(args[++i]);
                    } catch (IllegalArgumentException e) {
                        throw new CmdError(e.getMessage());
                    }
                }
                default -> rest.add(a);
            }
        }
        return new Flags(priv, tr, exp, to, dry, rest);
    }

    // ── 실행

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        try {
            if (args.length == 0) {
                usage(s);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "state" -> state(s, args);
                case "signal" -> signal(s, args);
                case "preset" -> preset(s, args);
                case "status" -> status(s, args);
                case "import-legacy" -> importLegacy(s, args);
                case "help" -> usage(s);
                default -> {
                    if (args.length >= 2 && (args[1].equalsIgnoreCase("state") || args[1].equalsIgnoreCase("signal"))) legacy(s, args);
                    else usage(s);
                }
            }
        } catch (CmdError | IllegalArgumentException e) {
            s.sendMessage("§c" + e.getMessage());
        } catch (IOException e) {
            s.sendMessage("§c파일 오류: " + e.getMessage());
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage("§e/fc state get <대상> [이름]");
        s.sendMessage("§e/fc state set <대상> <이름> <값> §7[--private] [--transient] [--expire <틱>]");
        s.sendMessage("§e/fc state unset <대상> <이름> §7· §e/fc state clear <대상> | all confirm");
        s.sendMessage("§e/fc signal <대상> <이름> [데이터] §7[--to self|trackers|all]");
        s.sendMessage("§e/fc preset list | show <이름> | save <이름> <대상> [상태…] | load <이름> <대상…> | delete <이름>");
        s.sendMessage("§e/fc status [대상] §7· §e/fc import-legacy <폴더> [--dry-run]");
        s.sendMessage("§7대상 = 플레이어 이름 · 선택자 · UUID · global — 값 = JSON(아니면 글)");
    }

    private void feedback(CommandSender s, String m) {
        if (host.settings().commandFeedback()) s.sendMessage("§7" + m);
    }

    private void state(CommandSender s, String[] args) {
        if (args.length < 3) throw new CmdError("/fc state get|set|unset|clear <대상> …");
        String op = args[1].toLowerCase(Locale.ROOT);
        if (op.equals("clear") && args[2].equalsIgnoreCase("all")) {
            if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) throw new CmdError("전역 + 모든 대상 + 저장 파일까지 지운다 — /fc state clear all confirm");
            int n = api.clearAll();
            s.sendMessage("§7모두 지웠다 — 상태 " + n + "개 · 저장 파일");
            return;
        }
        List<UUID> targets = targets(s, args[2]);
        switch (op) {
            case "get" -> {
                for (UUID t : targets) printStates(s, t, args.length >= 4 ? args[3] : null);
            }
            case "set" -> {
                if (args.length < 5) throw new CmdError("/fc state set <대상> <이름> <값> [--private] [--transient] [--expire <틱>]");
                String name = args[3];
                Flags f = flags(args, 4);
                if (f.rest().isEmpty()) throw new CmdError("값이 없다");
                if (f.priv() && targets.contains(null)) throw new CmdError("전역에는 --private 를 못 쓴다(늘 전원)");
                JsonElement v = ValueParse.jsonOrString(String.join(" ", f.rest()));
                StateOptions o = new StateOptions(!f.transientState(), f.priv(), f.expire());
                int changed = 0;
                for (UUID t : targets) if (api.setInternal(t, name, v, o, ChangeCause.COMMAND)) changed++;
                feedback(s, name + " = " + v + " — 대상 " + targets.size() + " · 바뀜 " + changed);
            }
            case "unset" -> {
                if (args.length < 4) throw new CmdError("/fc state unset <대상> <이름>");
                int n = 0;
                for (UUID t : targets) if (api.unsetInternal(t, args[3], ChangeCause.COMMAND)) n++;
                feedback(s, args[3] + " 지움 — " + n + "대상");
            }
            case "clear" -> {
                int n = 0;
                for (UUID t : targets) n += api.clearInternal(t, ChangeCause.CLEAR);
                feedback(s, "지움 — 상태 " + n + "개");
            }
            default -> throw new CmdError("/fc state get|set|unset|clear");
        }
    }

    private void signal(CommandSender s, String[] args) {
        if (args.length < 3) throw new CmdError("/fc signal <대상> <이름> [데이터] [--to self|trackers|all]");
        List<UUID> targets = targets(s, args[1]);
        String name = args[2];
        Flags f = flags(args, 3);
        JsonElement data = f.rest().isEmpty() ? JsonNull.INSTANCE : ValueParse.jsonOrString(String.join(" ", f.rest()));
        for (UUID t : targets) {
            Audience a = f.to() != null ? f.to() : (t == null ? Audience.ALL : Audience.TRACKERS);
            api.signalInternal(t, name, data, a);
        }
        feedback(s, "신호 " + name + " — 대상 " + targets.size());
    }

    private void preset(CommandSender s, String[] args) throws IOException {
        if (args.length < 2) throw new CmdError("/fc preset list | show | save | load | delete");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> s.sendMessage("§7프리셋: §f" + String.join(" · ", presets.list()));
            case "show" -> {
                if (args.length < 3) throw new CmdError("/fc preset show <이름>");
                for (String line : presets.raw(args[2]).split("\n")) s.sendMessage("§7" + line);
            }
            case "save" -> {
                if (args.length < 4) throw new CmdError("/fc preset save <이름> <대상> [상태…]");
                List<UUID> t = targets(s, args[3]);
                if (t.size() != 1) throw new CmdError("대상은 하나만");
                storage.ensureLoaded(t.get(0));
                SubjectState st = store.subject(t.get(0));
                List<String> names = Arrays.asList(Arrays.copyOfRange(args, 4, args.length));
                JsonObject j = st == null ? new JsonObject() : PresetCodec.write(st, names);
                if (j.isEmpty()) throw new CmdError("담을 상태가 없다");
                presets.save(args[2], j);
                s.sendMessage("§7프리셋 " + args[2] + " 저장 — 상태 " + j.size() + "개");
            }
            case "load" -> {
                if (args.length < 4) throw new CmdError("/fc preset load <이름> <대상…>");
                List<UUID> targets = new ArrayList<>();
                for (int i = 3; i < args.length; i++) targets.addAll(targets(s, args[i]));
                int n = api.applyPresetInternal(args[2], targets);
                feedback(s, "프리셋 " + args[2] + " — 대상 " + targets.size() + " · 바뀜 " + n);
            }
            case "delete" -> {
                if (args.length < 3) throw new CmdError("/fc preset delete <이름>");
                s.sendMessage(presets.delete(args[2]) ? "§7프리셋 " + args[2] + " 지움" : "§c프리셋이 없다: " + args[2]);
            }
            default -> throw new CmdError("/fc preset list | show | save | load | delete");
        }
    }

    private void status(CommandSender s, String[] args) {
        if (args.length >= 2) {
            for (UUID t : targets(s, args[1])) statusOf(s, t);
            return;
        }
        long[] st = host.sentStats();
        List<String> ready = new ArrayList<>();
        List<String> noHello = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (dispatcher.isReady(p.getUniqueId())) ready.add(p.getName());
            else if (org.figuramc.figura.server.api.FiguraFSBApi.isConnected(p.getUniqueId())) noHello.add(p.getName());
        }
        int players = 0;
        int mobs = 0;
        int other = 0;
        for (SubjectState ss : store.subjects()) {
            Entity e = Bukkit.getEntity(ss.id);
            if (e instanceof Player) players++;
            else if (e != null) mobs++;
            else other++;
        }
        s.sendMessage("§eFiguraController " + host.version() + " §7· 규약 figuracontroller:v1 (판 " + kr.asmbl.figuracontroller.protocol.Protocol.VERSION + ")");
        s.sendMessage("§7준비된 클라 " + ready.size() + ": §f" + String.join(" ", ready));
        if (!noHello.isEmpty()) s.sendMessage("§cFSB 연결 · HELLO 없음(옛 코어?) " + noHello.size() + ": §f" + String.join(" ", noHello));
        s.sendMessage("§7메모리 대상: 접속한 플레이어 " + players + " · 몹 " + mobs + " · 그 밖(접속 안 함 · 저장 대기) " + other + " · 전역 상태 " + store.global().entries().size());
        s.sendMessage("§7저장 대기 " + storage.pendingSaves() + " · 보낸 패킷 " + st[0] + " · " + st[1] + " B (켠 뒤로)");
    }

    private void statusOf(CommandSender s, UUID t) {
        storage.ensureLoaded(t);
        SubjectState ss = store.subject(t);
        int n = ss == null ? 0 : ss.entries().size();
        int priv = 0;
        int tr = 0;
        if (ss != null) for (Entry e : ss.entries().values()) {
            if (e.priv) priv++;
            if (!e.persist) tr++;
        }
        s.sendMessage("§e" + describe(t) + " §7— 상태 " + n + " (비공개 " + priv + " · 저장 안 함 " + tr + ")");
        if (t == null) {
            s.sendMessage("§7받는 사람: 준비된 전원 " + dispatcher.ready().size());
            return;
        }
        List<String> rec = new ArrayList<>();
        if (dispatcher.isReady(t)) rec.add("(본인)");
        Entity e = Bukkit.getEntity(t);
        if (e != null) for (Player p : e.getTrackedBy()) if (dispatcher.isReady(p.getUniqueId())) rec.add(p.getName());
        s.sendMessage("§7공개 상태를 받는 클라 " + rec.size() + ": §f" + String.join(" ", rec));
    }

    private void importLegacy(CommandSender s, String[] args) throws IOException {
        Flags f = flags(args, 1);
        if (f.rest().isEmpty()) throw new CmdError("/fc import-legacy <폴더> [--dry-run] — 예: plugins/CommandHelper/includes/system/player_figura_controller/persistence");
        File dir = new File(f.rest().get(0));
        if (!dir.isAbsolute()) dir = new File(System.getProperty("user.dir"), f.rest().get(0));
        if (!dir.isDirectory()) throw new CmdError("폴더가 없다: " + dir);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) throw new CmdError("JSON 이 없다: " + dir);
        Arrays.sort(files);
        int subjects = 0;
        int kept = 0;
        Set<String> dropped = new TreeSet<>();
        List<String> broken = new ArrayList<>();
        List<String> badFiles = new ArrayList<>();
        for (File file : files) {
            String base = file.getName().substring(0, file.getName().length() - 5);
            UUID subj;
            if (base.equals("~global")) {
                subj = null;
            } else {
                try {
                    subj = UUID.fromString(base);
                } catch (IllegalArgumentException e) {
                    badFiles.add(file.getName());
                    continue;
                }
            }
            LegacyImport.Result r;
            try {
                r = LegacyImport.parse(JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (RuntimeException e) {
                badFiles.add(file.getName());
                continue;
            }
            subjects++;
            kept += r.keep().size();
            dropped.addAll(r.dropped());
            for (String b : r.broken()) broken.add(base + ":" + b);
            if (!f.dryRun()) {
                if (subj != null) storage.declarePlayer(subj); // 옛 저장은 플레이어만 — 이 서버에 접속한 적이 없어도 파일로
                for (Map.Entry<String, JsonElement> e : r.keep().entrySet()) {
                    api.setInternal(subj, e.getKey(), e.getValue(), StateOptions.DEFAULT, ChangeCause.IMPORT);
                }
            }
        }
        s.sendMessage((f.dryRun() ? "§e[시험] " : "§a") + "가져오기 — 대상 " + subjects + " · 상태 " + kept + (f.dryRun() ? " (안 썼다)" : ""));
        if (!dropped.isEmpty()) s.sendMessage("§7버림: " + String.join(" ", dropped));
        if (!broken.isEmpty()) s.sendMessage("§c깨진 칸: " + String.join(" ", broken));
        if (!badFiles.isEmpty()) s.sendMessage("§c못 읽은 파일: " + String.join(" ", badFiles));
        host.logger().info("import-legacy " + dir + (f.dryRun() ? " (dry-run)" : "") + " — 대상 " + subjects + " · 상태 " + kept + " · 버림 " + dropped);
    }

    /** 옛 문법 — 옛 CH 와 같게: 대상은 접속한 플레이어 · `~global` · 바꾸면 아무 말 안 함 · 지움 = `~none` */
    private void legacy(CommandSender s, String[] args) {
        if (!legacyWarned) {
            legacyWarned = true;
            host.logger().info("옛 /fc 문법이 쓰였다(임시 별칭 — 나중에 지운다): /fc " + String.join(" ", args));
        }
        boolean global = args[0].equals("~global");
        UUID subj = null;
        if (!global) {
            Player p = Bukkit.getPlayerExact(args[0]);
            if (p == null) return;
            subj = p.getUniqueId();
        }
        if (args[1].equalsIgnoreCase("state")) {
            if (args.length <= 3) {
                legacyPrint(s, subj, args.length == 3 ? args[2] : null);
                return;
            }
            String raw = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
            if (ValueParse.isLegacyRemove(raw)) api.unsetInternal(subj, args[2], ChangeCause.COMMAND);
            else api.setInternal(subj, args[2], ValueParse.legacy(raw), StateOptions.DEFAULT, ChangeCause.COMMAND);
        } else {
            if (global || args.length < 3) return;
            JsonElement data = args.length > 3 ? ValueParse.legacy(String.join(" ", Arrays.copyOfRange(args, 3, args.length))) : JsonNull.INSTANCE;
            api.signalInternal(subj, args[2], data, Audience.TRACKERS);
        }
    }

    /** 옛 출력 — 플레이어면 전역과 합쳐 보이고 전역에서만 온 것은 회색 */
    private void legacyPrint(CommandSender s, UUID subj, String name) {
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, Boolean> fromGlobal = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : store.global().entries().entrySet()) {
            merged.put(e.getKey(), e.getValue().value.toString());
            fromGlobal.put(e.getKey(), subj != null);
        }
        SubjectState ss = subj == null ? null : store.subject(subj);
        if (ss != null) for (Map.Entry<String, Entry> e : ss.entries().entrySet()) {
            merged.put(e.getKey(), e.getValue().value.toString());
            fromGlobal.put(e.getKey(), false);
        }
        String who = subj == null ? "글로벌" : describe(subj);
        if (name != null) {
            if (merged.containsKey(name)) s.sendMessage((fromGlobal.get(name) ? "§7" : "") + name + " : " + merged.get(name));
            else s.sendMessage(name + " : §c(데이터 없음)");
            return;
        }
        s.sendMessage(who + " 상태 :");
        if (merged.isEmpty()) s.sendMessage(" §7(데이터 없음)");
        for (Map.Entry<String, String> e : merged.entrySet()) s.sendMessage((fromGlobal.get(e.getKey()) ? "§7" : "") + " " + e.getKey() + " : " + e.getValue());
    }

    private void printStates(CommandSender s, UUID t, String name) {
        storage.ensureLoaded(t);
        SubjectState ss = store.subject(t);
        String who = describe(t);
        if (name != null) {
            Entry e = ss == null ? null : ss.get(name);
            s.sendMessage("§e" + who + " §7· §f" + name + (e == null ? " §7(없음)" : " = " + render(e)));
            return;
        }
        int n = ss == null ? 0 : ss.entries().size();
        s.sendMessage("§e" + who + " §7— 상태 " + n);
        if (ss != null) for (Map.Entry<String, Entry> e : ss.entries().entrySet()) s.sendMessage(" §f" + e.getKey() + " = " + render(e.getValue()));
    }

    private static String render(Entry e) {
        String v = e.value.toString();
        if (v.length() > 200) v = v.substring(0, 200) + "…";
        StringBuilder sb = new StringBuilder(v);
        if (e.priv) sb.append(" §8[비공개]");
        if (!e.persist) sb.append(" §8[저장 안 함]");
        if (e.expireTick >= 0) sb.append(" §8[만료 ").append(Math.max(0, e.expireTick - Bukkit.getCurrentTick())).append("틱]");
        return sb.toString();
    }

    /** null = 전역 */
    private static String describe(UUID t) {
        if (t == null) return "전역";
        Player p = Bukkit.getPlayer(t);
        if (p != null) return p.getName();
        Entity e = Bukkit.getEntity(t);
        if (e != null) {
            net.kyori.adventure.text.Component cn = e.customName();
            String n = cn == null ? "" : "(" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(cn) + ")";
            return e.getType().name().toLowerCase(Locale.ROOT) + n + " " + t.toString().substring(0, 8);
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(t);
        return op.getName() != null ? op.getName() + " (접속 안 함)" : t.toString();
    }

    /** null 원소 = 전역 */
    private static List<UUID> targets(CommandSender s, String t) {
        List<UUID> out = new ArrayList<>();
        if (t.equalsIgnoreCase("global") || t.equals("~global")) {
            out.add(null);
            return out;
        }
        if (t.startsWith("@")) {
            List<Entity> es;
            try {
                es = Bukkit.selectEntities(s, t);
            } catch (IllegalArgumentException e) {
                throw new CmdError("선택자 오류: " + e.getMessage());
            }
            if (es.isEmpty()) throw new CmdError("선택자에 걸린 대상이 없다: " + t);
            for (Entity e : es) out.add(e.getUniqueId());
            return out;
        }
        try {
            out.add(UUID.fromString(t));
            return out;
        } catch (IllegalArgumentException ignored) {
            // 이름이다
        }
        Player p = Bukkit.getPlayerExact(t);
        if (p != null) {
            out.add(p.getUniqueId());
            return out;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(t);
        if (op != null) {
            out.add(op.getUniqueId());
            return out;
        }
        throw new CmdError("대상을 못 찾았다: " + t + " (플레이어 이름 · 선택자 · UUID · global)");
    }

    // ── 탭

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1];
        List<String> c = new ArrayList<>();
        if (args.length <= 1) {
            c.addAll(SUBS);
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "state" -> {
                    if (args.length == 2) c.addAll(List.of("get", "set", "unset", "clear"));
                    else if (args.length == 3) {
                        c.addAll(targetHints());
                        if (args[1].equalsIgnoreCase("clear")) c.add("all");
                    } else if (args.length == 4) {
                        if (args[1].equalsIgnoreCase("clear") && args[2].equalsIgnoreCase("all")) c.add("confirm");
                        else c.addAll(nameHints(s, args[2]));
                    } else if (args[1].equalsIgnoreCase("set")) {
                        if (args.length == 5) c.addAll(List.of("true", "false", "null"));
                        else c.addAll(List.of("--private", "--transient", "--expire"));
                    }
                }
                case "signal" -> {
                    if (args.length == 2) c.addAll(targetHints());
                    else if (args.length >= 4) {
                        if (args[args.length - 2].equals("--to")) c.addAll(List.of("self", "trackers", "all"));
                        else c.add("--to");
                    }
                }
                case "preset" -> {
                    if (args.length == 2) c.addAll(List.of("list", "show", "save", "load", "delete"));
                    else if (args.length == 3 && !args[1].equalsIgnoreCase("save")) c.addAll(presets.list());
                    else if (args.length >= 4 && (args[1].equalsIgnoreCase("load") || (args[1].equalsIgnoreCase("save") && args.length == 4))) c.addAll(targetHints());
                }
                case "status" -> {
                    if (args.length == 2) c.addAll(targetHints());
                }
                case "import-legacy" -> {
                    if (args.length == 2) c.add("plugins/CommandHelper/includes/system/player_figura_controller/persistence");
                    else c.add("--dry-run");
                }
                default -> {
                    // 옛 문법은 안 권한다
                }
            }
        }
        List<String> out = new ArrayList<>();
        String l = last.toLowerCase(Locale.ROOT);
        for (String x : c) if (x.toLowerCase(Locale.ROOT).startsWith(l)) out.add(x);
        return out;
    }

    private static List<String> targetHints() {
        List<String> c = new ArrayList<>(List.of("global", "@s", "@p", "@a", "@e[type=", "@e[limit=1,sort=nearest]"));
        for (Player p : Bukkit.getOnlinePlayers()) c.add(p.getName());
        return c;
    }

    private List<String> nameHints(CommandSender s, String target) {
        Set<String> names = new TreeSet<>(store.global().entries().keySet());
        try {
            for (UUID t : targets(s, target)) {
                if (t == null) continue;
                SubjectState ss = store.subject(t);
                if (ss != null) names.addAll(ss.entries().keySet());
            }
        } catch (RuntimeException ignored) {
            // 아직 다 안 친 대상
        }
        return new ArrayList<>(names);
    }
}
