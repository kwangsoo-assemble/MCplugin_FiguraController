package kr.asmbl.figuracontroller.bukkit;

import com.google.gson.JsonElement;
import kr.asmbl.figuracontroller.api.Audience;
import kr.asmbl.figuracontroller.api.ChangeCause;
import kr.asmbl.figuracontroller.api.FiguraControllerAPI;
import kr.asmbl.figuracontroller.api.StateOptions;
import kr.asmbl.figuracontroller.core.Dispatcher;
import kr.asmbl.figuracontroller.core.Entry;
import kr.asmbl.figuracontroller.core.PresetCodec;
import kr.asmbl.figuracontroller.core.StateStore;
import kr.asmbl.figuracontroller.core.SubjectState;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link FiguraControllerAPI} — 명령도 이것을 거친다(원인만 다르게). 대상 {@code null} = 전역(내부 전용) */
final class ApiImpl implements FiguraControllerAPI {
    private final StateStore store;
    private final Dispatcher dispatcher;
    private final Storage storage;
    private final PresetStore presets;
    private final GlobalStates global = new Global();

    ApiImpl(StateStore store, Dispatcher dispatcher, Storage storage, PresetStore presets) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.storage = storage;
        this.presets = presets;
    }

    static void main() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("FiguraController API 는 메인 스레드에서만 부른다");
    }

    private static long now() {
        return Bukkit.getCurrentTick();
    }

    // ── 내부(전역 = null · 원인 지정)

    Optional<JsonElement> getInternal(UUID subject, String name) {
        main();
        storage.ensureLoaded(subject);
        SubjectState s = store.subject(subject);
        Entry e = s == null ? null : s.get(name);
        return e == null ? Optional.empty() : Optional.of(e.value.deepCopy());
    }

    Map<String, JsonElement> getAllInternal(UUID subject) {
        main();
        storage.ensureLoaded(subject);
        Map<String, JsonElement> out = new LinkedHashMap<>();
        SubjectState s = store.subject(subject);
        if (s != null) for (Map.Entry<String, Entry> e : s.entries().entrySet()) out.put(e.getKey(), e.getValue().value.deepCopy());
        return out;
    }

    boolean setInternal(UUID subject, String name, JsonElement value, StateOptions o, ChangeCause cause) {
        main();
        Objects.requireNonNull(o, "options");
        storage.ensureLoaded(subject);
        return store.set(subject, name, value, o, now(), cause);
    }

    boolean unsetInternal(UUID subject, String name, ChangeCause cause) {
        main();
        storage.ensureLoaded(subject);
        return store.unset(subject, name, cause);
    }

    int clearInternal(UUID subject, ChangeCause cause) {
        main();
        storage.ensureLoaded(subject);
        return store.clear(subject, cause);
    }

    void signalInternal(UUID subject, String name, JsonElement data, Audience a) {
        main();
        dispatcher.signal(subject, name, data, a == null ? Audience.TRACKERS : a);
    }

    /** @return 바뀐 상태 수 */
    int applyPresetInternal(String preset, Collection<UUID> subjects) {
        main();
        Map<String, PresetCodec.Item> items;
        try {
            items = presets.load(preset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int n = 0;
        for (UUID s : subjects) {
            for (Map.Entry<String, PresetCodec.Item> it : items.entrySet()) {
                if (setInternal(s, it.getKey(), it.getValue().value(), it.getValue().options(), ChangeCause.PRESET)) n++;
            }
        }
        return n;
    }

    // ── 공개 API

    @Override
    public Optional<JsonElement> get(UUID subject, String name) {
        return getInternal(Objects.requireNonNull(subject, "subject"), name);
    }

    @Override
    public Map<String, JsonElement> getAll(UUID subject) {
        return getAllInternal(Objects.requireNonNull(subject, "subject"));
    }

    @Override
    public boolean set(UUID subject, String name, JsonElement value) {
        return set(subject, name, value, StateOptions.DEFAULT);
    }

    @Override
    public boolean set(UUID subject, String name, JsonElement value, StateOptions options) {
        return setInternal(Objects.requireNonNull(subject, "subject"), name, value, options, ChangeCause.API);
    }

    @Override
    public boolean unset(UUID subject, String name) {
        return unsetInternal(Objects.requireNonNull(subject, "subject"), name, ChangeCause.API);
    }

    @Override
    public int clear(UUID subject) {
        return clearInternal(Objects.requireNonNull(subject, "subject"), ChangeCause.API);
    }

    @Override
    public int clearAll() {
        main();
        int n = store.clearAll(ChangeCause.CLEAR);
        storage.deleteAllPlayerFiles();
        return n;
    }

    @Override
    public void sendSignal(UUID subject, String name, JsonElement data) {
        sendSignal(subject, name, data, Audience.TRACKERS);
    }

    @Override
    public void sendSignal(UUID subject, String name, JsonElement data, Audience audience) {
        signalInternal(Objects.requireNonNull(subject, "subject"), name, data, audience);
    }

    @Override
    public GlobalStates global() {
        return global;
    }

    @Override
    public boolean isReady(UUID player) {
        return dispatcher.isReady(player);
    }

    @Override
    public int applyPreset(String preset, Collection<UUID> subjects) {
        for (UUID s : subjects) Objects.requireNonNull(s, "subject");
        return applyPresetInternal(preset, subjects);
    }

    @Override
    public List<String> presets() {
        return presets.list();
    }

    private final class Global implements GlobalStates {
        @Override
        public Optional<JsonElement> get(String name) {
            return getInternal(null, name);
        }

        @Override
        public Map<String, JsonElement> getAll() {
            return getAllInternal(null);
        }

        @Override
        public boolean set(String name, JsonElement value) {
            return setInternal(null, name, value, StateOptions.DEFAULT, ChangeCause.API);
        }

        @Override
        public boolean set(String name, JsonElement value, StateOptions options) {
            return setInternal(null, name, value, options, ChangeCause.API);
        }

        @Override
        public boolean unset(String name) {
            return unsetInternal(null, name, ChangeCause.API);
        }

        @Override
        public int clear() {
            return clearInternal(null, ChangeCause.API);
        }

        @Override
        public void sendSignal(String name, JsonElement data) {
            signalInternal(null, name, data, Audience.ALL);
        }
    }
}
