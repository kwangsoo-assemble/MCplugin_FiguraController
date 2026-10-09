# FiguraController — API

서버가 주인인 **Figura 상태 · 신호** 플러그인 (Purpur/Paper 1.21.8 · 내부용).
CH 「플레이어 피구라 컨트롤러」를 갈음한다 (2026-10-10 배포 · 설계 정본: 하네스 `projects/figura_controller/design/컨트롤러_플러그인/plan.md`).

```
[서버]  FiguraController 플러그인 ── 상태 저장소(플레이어 · 몹 · 전역) · 저장 · 만료 · 프리셋 · /fc
          ▲ Java API              ▲ CHFeatures 0.9.0 (fc_* 함수 · 이벤트)
          │                        └ CommandHelper 스크립트
          │  FSB 패킷 하나 «figuracontroller:v1» (이진 · 틱 끝에 한 번 · 바뀐 것만)
          ▼
[클라]  포크 Figura 코어 ── 클라 공용 저장소(아바타 밖) · Lua 전역 `server_data`
          └ 아바타 (플레이어 템플릿 `modelManager.serverData` · 몹 템플릿 `entityManager.state`)
```

필요한 것: 서버 **FiguraFSB** 플러그인 · 클라 **포크 Figura 코어 `0.1.6-but-ai-edited.1` (`d98e5a2` 이후)** — 옛 코어 클라는 상태를 못 받는다(호환 없음).

---

## 1. 개념

### 대상 (subject)
| 대상 | 무엇 | 쓰는 이름 |
|---|---|---|
| 플레이어 | 그 플레이어의 아바타 | 이름 · UUID · 선택자(`@p` …) |
| 몹 | 그 엔티티에 입힌 몹(CEM) 아바타 | UUID · 선택자(`@e[type=husk,limit=1]`) |
| 전역 | 모든 아바타가 «자기 값이 없을 때» 보는 값 | `global` (`~global` 도 된다) |

### 상태 하나의 속성
| 속성 | 기본 | 뜻 |
|---|---|---|
| `persist` | 켬 | 서버가 저장한다(플레이어 = 파일 · 몹 = 엔티티 PDC · 전역 = 파일). 끄면 리로드 · 재시작 · 몹 청크 내림 · **플레이어 퇴장**에 사라진다 |
| `private` | 끔 | 플레이어면 **그 사람 클라에만** · 몹이면 **서버에만**(클라로 안 보낸다) · 전역은 늘 전원(CH 는 오류 · Java · 명령은 무시) |
| `expire` | 0 | 0 보다 크면 그 틱 뒤 저절로 지운다 · 저장할 때 «남은 틱» 으로 적는다 |

### 값
- JSON 값 그대로 — 불 · 수 · 글 · 배열 · 객체 · **null**. 정수와 실수는 다른 값이다(`1` ≠ `1.0`)
- **null 은 «지움» 이 아니다** — 값이 null 인 상태가 있는 것이다. 지우는 것은 `unset`
  ⚠ 클라(Lua)에서는 null 값과 «없음» 이 둘 다 `nil` 이라 구분되지 않는다
- **같은 값으로 다시 쓰면 아무 일도 없다** — 저장 안 함 · 안 보냄 · 이벤트 없음(바이트로 비교)
- 크기: 패킷 몸 30,000 B 상한(FSB 한도 32,746 B) — 값 · 신호 데이터 하나는 인코딩 29,900 B 까지 · 이름은 UTF-8 256 B 까지. 넘으면 오류

### 누가 받나 (상태)
| 상태 | 받는 클라 |
|---|---|
| 플레이어 · 공개 | 그 플레이어를 **보고 있는** 클라(트래킹) + 본인 |
| 플레이어 · `private` | 본인만 |
| 몹 · 공개 | 그 몹을 보고 있는 클라 |
| 몹 · `private` | 아무도(서버에만) |
| 전역 | 준비된(HELLO 를 보낸) 모든 클라 |

- 트래킹이 시작되면(가까이 오면) 그 대상의 상태 **전부**가 가고, 끝나면 클라가 버린다 — 늦게 온 사람 · 다시 온 사람도 지금 값을 본다
- 클라가 접속해 준비되면(HELLO) 전역 + 자기 + 지금 보는 대상 전부를 받는다

### 신호 (signal)
- 일회성 «지금 이것을 해라» — **저장 안 한다** · 늦게 온 사람은 못 받는다(그게 필요하면 상태로)
- 받는 사람(`audience`): `trackers`(기본 — 보는 클라 + 본인) · `self`(본인만) · `all`(준비된 모든 클라) · 플레이어 목록
- ★ **전역 연출(관전 스태프도 같은 화면이어야 하는 것)은 `all`** — 하네스 `knowledge/shared/production_rules.md`
- 같은 틱에서는 상태 변경 **다음에** 신호가 간다
- Flashback 되감기에서 다시 돌 수 있다 — 연출은 짧게(카운트다운)

### 이벤트가 오는 때 (클라)
- 패킷마다가 아니라 **다음 클라 틱 시작**에, 그 사이 «처음 ↔ 지금» 이 다를 때만
- 아바타가 실리면(몹 아바타가 다시 생겨도) **지금 값으로 한 번씩**(`initial = true`)

---

## 2. 명령 `/fc` (`/figuracontroller` · 권한 `figuracontroller.command` · 기본 op)

```
/fc state get    <대상> [이름]
/fc state set    <대상> <이름> <값> [--private] [--transient] [--expire <틱>]
/fc state unset  <대상> <이름>
/fc state clear  <대상> | all confirm          -- all = 전역 + 모든 대상 + 저장 파일
/fc signal       <대상> <이름> [데이터] [--to self|trackers|all]
/fc preset list | show <이름> | save <이름> <대상> [상태…] | load <이름> <대상…> | delete <이름>
/fc status [대상]                               -- 준비된 클라 · 규약판 · 메모리 대상 · 보낸 패킷
/fc import-legacy <폴더> [--dry-run]            -- 옛 CH persistence/ 가져오기 (한 번)
```
- 값 = JSON(`true` · `3` · `1.5` · `"글"` · `[1,2]` · `{"x":1}` · `null`) — JSON 이 아니면 글로 읽는다(`RATH4R`)
- `--transient` = `persist` 끔
- 출력: **친 사람에게만** · 성공 확인은 기본 끔(`commandFeedback`) — 탤런트 화면 · 리플레이에 안 남게. 오류와 조회는 늘 보인다
- ⚠ **옛 문법(임시)**: `/fc <대상> state [이름] [값|~none]` · `/fc <대상> signal <이름> [데이터]` — 아바타가 직접 치는 곳(마도사 · 베리티) 때문에 받아 준다 · 쓰이면 콘솔에 한 번 «옛 문법» · 나중에 지운다

---

## 3. Java API (팀원 플러그인)

```xml
<dependency>  <!-- 이 저장소에서 mvnw install -->
  <groupId>kr.asmbl</groupId><artifactId>figuracontroller</artifactId><version>0.1.0</version><scope>provided</scope>
</dependency>
```
`plugin.yml` 에 `depend: [FiguraController]`(또는 softdepend 뒤 `isPluginEnabled` 확인).

```java
FiguraControllerAPI api = FiguraController.api();               // 또는 Bukkit ServicesManager

api.set(player, "skin", new JsonPrimitive("A"));                  // 바뀌었으면 true
api.set(mob, "look", new JsonPrimitive(2), StateOptions.DEFAULT.withPersist(false).withExpireTicks(200));
api.get(player, "skin");                                          // Optional<JsonElement> (사본)
api.getAll(player);                                               // Map (사본 · 넣은 순서)
api.unset(player, "skin");  api.clear(player);  api.clearAll();
api.sendSignal(player, "SCREENSHAKE", data, Audience.ALL);        // 기본 Audience.TRACKERS
api.global().set("mode", new JsonPrimitive("race"));
api.global().sendSignal("PING", JsonNull.INSTANCE);               // 전원 · Audience.players(...) 면 그 사람들만
api.isReady(player);                                              // 그 클라가 HELLO 를 보냈나(새 코어)
api.applyPreset("default", List.of(uuid1, uuid2));  api.presets();
```
- **메인 스레드에서만** — 아니면 `IllegalStateException`
- `set` 은 즉시 저장소에 반영(같은 틱의 `get` 이 새 값) · 보내기는 **틱 끝에 한 번**(같은 틱에 여러 번 바꾸면 마지막 값)
- 값은 넣을 때 · 꺼낼 때 사본(밖에서 고쳐도 저장소는 안 바뀐다) · 대상이 `UUID` 인 오버로드도 있다(접속 안 한 플레이어 · 내려간 몹)

**이벤트 (Bukkit)**
| 이벤트 | 언제 | 내용 |
|---|---|---|
| `FiguraStateChangeEvent` | 상태가 **바뀐 뒤**(동기) | `getSubject()`(전역이면 null · `isGlobal()`) · `getName()` · `getOldValue()` · `getNewValue()`(지웠으면 null) · `getCause()` = `API` · `COMMAND` · `PRESET` · `EXPIRE` · `CLEAR` · `IMPORT` |
| `FiguraSignalReceiveEvent` | 아바타 → 서버 신호 | `getPlayer()` · `getName()` · `getData()` — **그 플레이어의 호스트 아바타가 보낸 것만**(남의 아바타가 보낸 것은 버린다) |

---

## 4. CommandHelper (CHFeatures 0.9.0)

| 함수 | 돌려줌 | 뜻 |
|---|---|---|
| `fc_state_get(대상, 이름)` | 값 · 없으면 null | **자기 값만**(전역 대체는 클라가 한다) |
| `fc_state_get_all(대상)` | 연관 배열(사본) | |
| `fc_state_set(대상, 이름, 값, [옵션])` | 바뀌었나 | 옵션 `array('persist': bool, 'private': bool, 'expire': 틱)` |
| `fc_states_set(대상, 연관 배열, [옵션])` | 바뀐 수 | |
| `fc_state_unset(대상, 이름)` | 있었나 | (옛 `'~none'`) |
| `fc_state_clear(대상)` | 지운 수 | |
| `fc_signal_send(대상, 이름, [데이터], [받는 사람])` | — | `'trackers'`(기본) · `'self'` · `'all'` · 플레이어 배열 · 전역 대상은 늘 전원(배열이면 그들만) |
| `fc_preset_load(프리셋, 대상 \| 대상 배열)` | 바뀐 수 | 플레이어 · 몹만(전역은 `/fc preset load <이름> global`) |
| `fc_is_ready(플레이어)` | 불 | 그 클라가 HELLO 를 보냈나 |

- 대상 = 플레이어 이름(접속 · 캐시된 오프라인) · UUID(플레이어 · 몹 · 대시 없는 32자도) · `'global'`
- 값 변환은 CHFeatures 가 **직접** 한다(CH `json_decode` 를 안 거친다 — 그건 `1.0` 을 정수 1 로 바꾼다) — 정수/실수 · 빈 배열/빈 연관 배열이 그대로 간다
- 오류: 메인 스레드 아님 → `PluginInternalException` · 너무 큼 → `LengthException` · 모르는 대상 → `PlayerOfflineException` · 그 밖 → `FormatException` · 클로저 같은 값 → `CastException`

**이벤트**
```ms
// 상태가 바뀜 — ★ name 프리필터 필수(없으면 bind 가 BindException) · 같은 값으로 다시 쓰면 안 온다
bind('fc_state_changed', null, array('name': 'swimMode'), @e) {
    // @e: name · subject(UUID | 'global') · player(이름 | null) · old · new · existed · exists · cause
}
// 아바타(호스트) → 서버 신호 — 남의 아바타가 보낸 것은 플러그인이 버린다
bind('fc_signal', null, array('name': 'HITBOX_HIT'), @e) {
    // @e: name · player · uuid · data
}
```
⚠ `bind` 몸은 클로저가 아니다 — **바깥 변수를 못 본다**(필요하면 proc · `import`).

**옛 CH proc (임시 껍데기)** — `includes/system/player_figura_controller/fc_main.ms` 가 옛 이름을 위 함수로 잇는다(쓰이면 콘솔에 proc 마다 한 번). 새 코드는 쓰지 않는다:

| 옛 | 새 |
|---|---|
| `_set_state(@p, n, v)` · `'~none'` | `fc_state_set(@p, n, v)` · `fc_state_unset(@p, n)` |
| `_set_states(@p, array(...))` | `fc_states_set(@p, array(...))` |
| `_get_state(@p, n)` · `_get_states` | `fc_state_get(@p, n)` · `fc_state_get_all(@p)` |
| `_set_global_state(n, v)` · `_get_global_state(n)` | `fc_state_set('global', n, v)` · `fc_state_get('global', n)` |
| `_send_signal(@p, n, d)` | `fc_signal_send(@p, n, d)` |
| `_send_host_signal(@p, n, d)` | `fc_signal_send(@p, n, d, 'self')` |
| `_register_custom_signal_callback(n, closure(@e) {…})` | `bind('fc_signal', null, array('name': n), @e) {…}` |
| `_register_state_change_callback(n, closure(@e) {…})` | `bind('fc_state_changed', null, array('name': n), @e) {…}` |
| `_send_states` · `_send_states_full` · `_init_states` · `_save_states` | 필요 없다(플러그인이 한다) |

---

## 5. Lua (아바타)

### 포크 코어 API — 전역 `server_data` (플레이어 · 몹 아바타 모두)
```lua
server_data:get("skin")                     -- 자기 값 · 없으면 전역 값 · 없으면 nil
server_data:get(entity_or_uuid, "skin")     -- 남(플레이어 · 몹)의 값
server_data:getGlobal("mode")
server_data:getAll([entity_or_uuid])        -- 읽기 전용 표(부를 때마다 새로)
server_data:watch(entity_or_uuid) / :unwatch(...)   -- 남의 이벤트도 받기(지금 값이 initial 로 한 번)
server_data.STATE_CHANGED:register(function(name, new, old, subject, initial) end, "이름")
server_data.SIGNAL:register(function(name, data, subject) end, "이름")
function server_data.STATE_CHANGED(...) end  -- 대입도 등록이다(events 와 같다)
server_data:send("JUMP_PAD", { power = 3 })  -- 아바타 → 서버 · 호스트 플레이어 아바타만(몹 · 논호스트는 오류) · 안 붙었으면 false
```
- ⚠ 메서드는 **콜론(`:`)으로만** — 점으로 부르면 «Use a colon» 오류
- `subject` = UUID 글 · 전역이면 nil · 표 값은 **읽기 전용**(고치면 오류)
- 전역 바뀜은 **자기 값이 없는** 아바타에만 온다 · 전역 신호는 이 클라의 **모든 아바타 인스턴스**에 온다(클라당 한 번이면 `host:isHost()`)

### 하네스 템플릿 (옛 이름 그대로)
- 플레이어 템플릿: `modelManager.serverData[name]` → `{ value = 값 }` 또는 nil(읽기 전용) · `modelManager.events.state_changed(name, old, new)` · `signal_received(name, data)` · `modelManager.sendSignal(name, data)` — 표 값은 고쳐도 되는 사본
- 몹 템플릿: `entityManager.state(name)` · `entityManager.onState(name|nil, fn(name, new, old, initial))` · `entityManager.onSignal(name|nil, fn(name, data))` — 오류 가두기 그대로
- 정본: 하네스 `knowledge/figura/avatar_system.md` §5 · `knowledge/figura/entity_avatar_system.md`

---

## 6. 저장

```
plugins/FiguraController/
  config.yml
  global.json                 전역
  players/<uuid>.json         플레이어 (접속 전 AsyncPlayerPreLogin 에서 미리 읽는다)
  presets/<이름>.json         프리셋 (첫 실행에 default)
엔티티 PDC  figuracontroller:states   몹 (엔티티가 월드에 들어올 때 읽고 · 바뀐 틱 끝에 바로 쓴다 · 월드에서 빠지면 메모리에서 뺀다)
```
- 형식(셋 다 같다): `{"version": 1, "states": {"skin": {"value": "A"}, "secret": {"value": 1, "private": true}, "buff": {"value": true, "expireIn": 200}}}` — `persist` 인 것만
- 쓰는 때: 처음 바뀐 뒤 `saveDelayTicks`(100) 지나 한 번 · 나갈 때 · 끌 때 · 비동기(IO 스레드) · 원자적 쓰기
- 프리셋: `{이름: {value, persist?, private?}}` · `default` = `nickName` · `listName` · `chatName` · `plateName` · `skin` · `isArmWide` = null · `showNametag` = true
- **CH `/rc` 는 상태를 안 건드린다**(옛 CH 컨트롤러는 날렸다)

---

## 7. 설정 `config.yml` (바꾸면 서버를 다시 켠다)
| 키 | 기본 | 뜻 |
|---|---|---|
| `commandFeedback` | false | 명령 성공 확인을 친 사람에게 |
| `debug` | false | 콘솔 진단 줄(HELLO · 깨진 패킷 …) |
| `saveDelayTicks` | 100 | 저장 묶기 |
| `oldCoreWarnTicks` | 200 | 접속 뒤 이만큼 FSB 는 붙었는데 HELLO 가 없으면 콘솔에 «옛 코어?» |

---

## 8. 규약 `figuracontroller:v1` (요약 — 원본은 `src/main/java/.../protocol/`)
- FSB `CustomFSBPacket` 하나 — `id = "figuracontroller:v1".hashCode()` · 몸 = 이진 op 줄
- S2C: `NAME`(이름 사전) · `SUBJECT`(대상 컨텍스트) · `GLOBAL` · `SET` · `UNSET` · `FULL`(+`FULL_CONT` 나눠 보냄) · `DROP`(그만 봄) · `SIGNAL` · `RESET`(세션 새로) · C2S: `HELLO`(규약판) · `SIGNAL`
- 값 태그: `NULL` · `FALSE` · `TRUE` · `INT`(zigzag) · `DOUBLE` · `STRING` · `ARRAY` · `OBJECT` · 깊이 32
- 코덱 **원본은 이 저장소** — 포크 코어 두 판에 사본 · 하네스 `projects/figura_controller/tests/verify_codec.py` 가 파이썬 참조 구현 벡터로 대조
- Flashback: 코어가 녹화 스냅샷마다 저장소 전체(RESET · 이름 사전 · FULL)를 끼워 넣는다 → 되감기 · 앞질러 가기도 그 시점 값(서버 재송신 없음)

---

## 9. 진단
| 증상 | 먼저 볼 것 |
|---|---|
| 아무 상태도 안 온다 | `/fc status` 의 «준비된 클라» 에 그 사람이 있나 — 없으면 옛 코어(콘솔 «옛 코어?») · FSB 미연결 |
| 아바타가 상태를 못 읽는다 | 그 아바타의 시스템 파일이 새 템플릿 판인가(옛 `serverData.lua` 는 `chfc:*` 채널을 기다린다 — 오지 않는다) |
| Lua «Use a colon» | `server_data.get(...)` → `server_data:get(...)` |
| CH `BindException` | `fc_state_changed` 에 `name` 프리필터를 안 줬다 |
| 같은 값을 다시 썼는데 아무 일도 없다 | 정상 — 바뀐 것만 간다. 사건을 상태로 전하려면 값에 일련번호(`judge#7`) |
| 멀리 있는 사람이 신호를 못 받았다 | 기본 받는 사람이 `trackers` — 전역 연출은 `'all'` |
