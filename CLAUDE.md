# FiguraController — 서버가 주인인 Figura 상태 저장소 · 신호 버스 (Purpur 1.21.8 · 내부용)

CH 「플레이어 피구라 컨트롤러」(`includes/system/player_figura_controller/`)를 갈음하는 Java 플러그인.
상태 읽기 · 쓰기 · 신호 보내기 · 받기 — 플레이어 · 몹(CEM 아바타) · 전역. 패키지 `kr.asmbl.figuracontroller`.

- **설계 정본**: 하네스 `projects/figura_controller/design/컨트롤러_플러그인/plan.md` (✅ 2026-10-10) · 진행 기록은 같은 폴더의 `progress.md`
- **GitHub `kwangsoo-assemble/MCplugin_FiguraController` 의 작업본**이다(2026-10-10 — 🙋 «여기에 올려서 관리되게») — 한글 주석 · 이 CLAUDE.md 까지 그대로 올라간다 · MIT
  · 매듭마다 로컬 커밋 → `python ../../../mc_content_production/projects/mc_repositories/tools/repos.py publish figuracontroller` (세션이 자동 · `--force` 금지)
  · README.md 는 영어판 · **원본은 하네스 한글판** `projects/mc_repositories/readme_ko/MCplugin_FiguraController.md` → 옮긴 뒤 `repos.py mark-readme figuracontroller`
  · ★ **버전 · 릴리스** (🙋 2026-10-10): jar 에 들어가는 코드가 바뀌면 `pom.xml` `<version>` 을 올린다 — `0.MINOR.PATCH`(기능 MINOR · 고침 PATCH ·
    규약 `figuracontroller:v1` 이 바뀌면 MINOR 이고 코어도 같이) → 빌드 · 배포 · 🎬 → `repos.py release figuracontroller`(태그 + Release 에 jar) ·
    문서만 바뀌면 안 올린다 · 안 올리면 `VERSION_STALE` · 올리고 안 내면 `UNRELEASED`
- **사용법 정본**: `API.md` (Java · CH · Lua · `/fc` · 공개 범위 · 저장 · 진단) — API · 명령 · 저장 형식을 바꾸면 **같은 커밋에서** 고친다 (팀원에게 이 파일을 넘긴다)

## 빌드 · 시험

- `JAVA_HOME` 을 **JDK** 로 (`C:\Program Files\Java\jdk-21`) — JRE 면 «No compiler is provided» 로 죽는다
- `.\mvnw.cmd test` · `.\mvnw.cmd package` → `target/figuracontroller-<버전>.jar`
- 의존: `paper-api`(provided · Gson 도 여기서) · FSB `figura-fsb-spigot` 0.1.6(provided · CHFeatures 와 같은 로컬 install-file)

## ★ 코덱(`protocol/`)은 이 저장소가 **원본**이다 (설계 §9-D)

- 포크 코어(`group_figura/figura_core_internal/common/src/main/java/kr/asmbl/figuracontroller/protocol/`)에 **같은 파일을 그대로** 복사한다 — 코어 쪽을 고치지 마라
- (옛 규칙 — 2026-10-10 폐기) 공개판 코어에 주석만 영어인 사본이 있어 코덱의 문자열 리터럴을 영어로 썼다 — 그 관례는 그대로 두어도 해가 없다
- 규약을 바꾸면 하네스의 파이썬 참조 구현 `projects/figura_controller/tests/fc_codec_ref.py` 도 같이 고치고 벡터를 다시 만든다:
  `python fc_codec_ref.py --write <이 저장소>/src/test/resources/figuracontroller/vectors.json` (생성물 — 손으로 고치지 마라)
- 대조: 하네스 `python projects/figura_controller/tests/verify_codec.py` — ① 벡터 신선도 ② 코어 사본 == 원본 ③ 코어 사본 × 벡터(Minecraft Gson)

## 업데이트 규칙

- 매듭마다 로컬 커밋 · 하네스 `projects/figura_controller/progress.md` 에 엔트리
- 출력은 촬영용 출력 규율을 따른다 — 명령 응답은 친 사람에게만 · 콘솔은 `debug` 설정일 때만
