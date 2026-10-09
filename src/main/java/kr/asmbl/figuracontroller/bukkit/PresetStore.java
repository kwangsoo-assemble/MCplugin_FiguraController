package kr.asmbl.figuracontroller.bukkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.asmbl.figuracontroller.core.JsonText;
import kr.asmbl.figuracontroller.core.PresetCodec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 프리셋 파일 `presets/&lt;이름&gt;.json` — 관리 명령 전용이라 메인 스레드에서 바로 읽고 쓴다(파일이 작다) */
final class PresetStore {
    private final File dir;

    PresetStore(File dir) {
        this.dir = dir;
    }

    void ensureDefault() throws IOException {
        dir.mkdirs();
        File f = file("default");
        if (!f.exists()) Files.writeString(f.toPath(), JsonText.pretty(PresetCodec.defaults()), StandardCharsets.UTF_8);
    }

    private File file(String name) {
        if (!PresetCodec.NAME.matcher(name).matches()) throw new IllegalArgumentException("프리셋 이름은 영문 · 숫자 · _ · - (64자까지): " + name);
        return new File(dir, name + ".json");
    }

    List<String> list() {
        List<String> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File f : fs) out.add(f.getName().substring(0, f.getName().length() - 5));
        out.sort(null);
        return out;
    }

    boolean exists(String name) {
        return file(name).isFile();
    }

    Map<String, PresetCodec.Item> load(String name) throws IOException {
        File f = file(name);
        if (!f.isFile()) throw new IllegalArgumentException("프리셋이 없다: " + name);
        return PresetCodec.read(JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject());
    }

    String raw(String name) throws IOException {
        File f = file(name);
        if (!f.isFile()) throw new IllegalArgumentException("프리셋이 없다: " + name);
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }

    void save(String name, JsonObject content) throws IOException {
        dir.mkdirs();
        Files.writeString(file(name).toPath(), JsonText.pretty(content), StandardCharsets.UTF_8);
    }

    boolean delete(String name) {
        return file(name).delete();
    }
}
