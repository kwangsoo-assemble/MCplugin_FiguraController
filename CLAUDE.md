# FiguraController — 서버가 주인인 Figura 상태 저장소 · 신호 버스 (Purpur 1.21.8 · 내부용)

CH 「플레이어 피구라 컨트롤러」(`includes/system/player_figura_controller/`)를 갈음하는 Java 플러그인.
상태 읽기 · 쓰기 · 신호 보내기 · 받기 — 플레이어 · 몹(CEM 아바타) · 전역. 패키지 `kr.asmbl.figuracontroller`.

- **설계 정본**: 하네스 `projects/figura_controller/design/컨트롤러_플러그인/plan.md` (✅ 2026-10-10) · 진행 기록은 같은 폴더의 `progress.md`
- **내부판만** 있다(🙋 «내부용») — 공개판 없음 · 로컬 git · 원격 없음
- **사용법 정본**: `API.md` (Java · CH · Lua · `/fc` · 공개 범위 · 저장 · 진단) — API · 명령 · 저장 형식을 바꾸면 **같은 커밋에서** 고친다 (팀원에게 이 파일을 넘긴다)

## 빌드 · 시험

- `JAVA_HOME` 을 **JDK** 로 (`C:\Program Files\Java\jdk-21`) — JRE 면 «No compiler is provided» 로 죽는다
- `.\mvnw.cmd test` · `.\mvnw.cmd package` → `target/figuracontroller-<버전>.jar`
- 의존: `paper-api`(provided · Gson 도 여기서) · FSB `figura-fsb-spigot` 0.1.6(provided · CHFeatures 와 같은 로컬 install-file)

## ★ 코덱(`protocol/`)은 이 저장소가 **원본**이다 (설계 §9-D)

- 포크 코어(`group_figura/figura_core_internal/common/src/main/java/kr/asmbl/figuracontroller/protocol/`)에 **같은 파일을 그대로** 복사한다 — 코어 쪽을 고치지 마라
- 공개판 코어(`figura_core_but_ai_edited`)에는 **주석만 영어**인 사본 — 그래서 코덱의 **문자열 리터럴은 영어**로 쓴다(공개판 한글 0)
- 규약을 바꾸면 하네스의 파이썬 참조 구현 `projects/figura_controller/tests/fc_codec_ref.py` 도 같이 고치고 벡터를 다시 만든다:
  `python fc_codec_ref.py --write <이 저장소>/src/test/resources/figuracontroller/vectors.json` (생성물 — 손으로 고치지 마라)
- 대조: 하네스 `python projects/figura_controller/tests/verify_codec.py` — ① 벡터 신선도 ② 코어 사본 == 원본 ③ 코어 사본 × 벡터(Minecraft Gson)

## 업데이트 규칙

- 매듭마다 로컬 커밋 · 하네스 `projects/figura_controller/progress.md` 에 엔트리
- 출력은 촬영용 출력 규율을 따른다 — 명령 응답은 친 사람에게만 · 콘솔은 `debug` 설정일 때만
