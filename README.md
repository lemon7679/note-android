# Note — Android 필기 앱 (NAS 중심)

[note-core](https://github.com/lemon7679/note-core)의 필기 엔진(InkEngine)으로 만든 Android용 노트 앱입니다.
[note-ipad](https://github.com/lemon7679/note-ipad)의 설계·디자인("Quiet Ink")과 로직을 그대로 옮겼고,
노트 파일 형식(`.inknote`)과 동기화 규칙이 같아서 **같은 NAS 폴더를 iPad와 Android에서 함께** 쓸 수 있습니다.

- 엔진: note-core C++17 InkEngine을 NDK로 빌드하고 JNI(`app/src/main/cpp/ink_jni.cpp`)로 연결 — iPad와 같은 필기감
- UI: Jetpack Compose + 커스텀 캔버스 뷰(타일 CPU 렌더러), 태블릿(사이드바 고정)과 휴대폰(서랍 메뉴) 모두 지원
- 요구 사항: Android 10(API 29) 이상, arm64-v8a / armeabi-v7a / x86_64

## 기능

**라이브러리 · 파일 관리**
- 저장소: 이 기기 + 연결한 NAS들. 폴더, 노트, PDF, 기타 파일
- 새 노트(표지 7색 · 종이 7종 · A4/B5/A5/Letter/화면 · 직접 입력(mm) · 세로/가로 · 종이 색), PDF 가져와서 필기, 파일 가져오기
- 이름 변경, 이동(이 기기의 노트 · PDF · 파일은 NAS 폴더로도), 복제, 즐겨찾기, 최근 문서, 검색, 정렬(수정일 · 이름 · 종류), 당겨서 새로 고침
- 휴지통(복원 · 영구 삭제), 다른 앱에서 PDF 공유/열기 → "Note"로 가져와 바로 필기

**NAS 연동** (iPad와 같은 코드 구조 · 같은 규칙)
- **Synology DSM**: File Station API(5000/5001) — WebDAV 패키지 없이 동작, 2단계 인증(OTP + 기기 토큰)
- **WebDAV**: Synology/QNAP/TrueNAS/OMV/Nextcloud 등. Depth: infinity를 지원하지 않으면 자동으로 폴더별 조회
- **SMB · 폴더**: 시스템 파일 선택기로 고른 폴더(SMB를 제공하는 저장소 앱, USB, 기기 폴더)를 영구 권한으로 연결
- mDNS(NSD)로 같은 네트워크의 NAS 자동 검색, 연결 테스트(쓰기 권한 확인), NAS 폴더 선택기
- 자체 서명 인증서: 처음 본 인증서 지문을 기억(TOFU)하고, 바뀌면 연결을 거부
- 암호와 DSM 기기 토큰은 Android 키스토어(AES-GCM)로 암호화해 저장
- **동기화**: NAS의 노트 폴더를 기기에 미러로 두고 3-way 비교로 양방향 동기화
  - 노트는 페이지 단위 파일이라 바뀐 페이지만 전송
  - 양쪽에서 같은 노트를 고치면 충돌 → "이 기기 유지 / NAS 유지 / 둘 다 보관"(설정으로 자동 보관 가능)
  - PDF 등 일반 파일은 목록만 받아 두고 열 때 다운로드, "다운로드 항목 제거"
  - 앱 실행·복귀·편집 후·5분마다 자동, Wi-Fi에서만 옵션, 백그라운드 진입 시 마무리
  - 안전장치: NAS가 갑자기 비어 보이거나 폴더가 사라지면 아무것도 지우지 않고 멈춤
  - 열려 있는 노트는 닫힐 때까지 동기화를 미룸
- **NAS 전체 보기**: 동기화 폴더 밖의 PDF도 열람하고 노트 폴더로 가져오기
- 옵션: 노트를 닫을 때 필기가 합쳐진 PDF를 노트 옆에 함께 저장

**필기 (InkEngine)**
- 볼펜 · 만년필 · 연필 · 붓펜 · 캘리그라피 · 마커 · 형광펜, 색 팔레트 + 사용자 색, 굵기 프리셋 3개
- 스타일러스 필압 · 기울기 · 방향, 과거 샘플(historical) 전부 사용, 엔진 내장 예측, 버퍼 없는 입력(unbuffered dispatch)
- 그리고 멈추면 도형(다른 손가락을 대면 완벽한 도형), 형광펜 직선 스냅, 글씨 보정, 손떨림 보정
- 정밀/획 지우개, 형광펜만 지우기, **스타일러스 지우개 끝 · 옆 버튼을 누른 채 쓰면 지우기**, 펜을 띄운 채 S펜 버튼을 누르면 펜 끝에 휠 메뉴(도구 · 색 · 굵기 · 실행 취소 · 펜 설정)
- **낙서로 지우기**: 펜으로 글씨 위를 여러 번 왔다 갔다 문지르면 그 아래 필기가 지워집니다(실행 취소 가능, 설정에서 끌 수 있음)
- 올가미: 이동, 모서리로 크기 조절, 복사·잘라내기·붙여넣기(페이지 간), 복제, 색 변경, **줄 정렬**, **글씨 다듬기**, 삭제
- 텍스트 상자, 이미지 삽입, 레이저 포인터
- 페이지 목록(썸네일, 순서 변경, 앞/뒤 추가, 복제, 삭제, 책갈피), 종이 바꾸기, 표지 바꾸기
- **선택한 페이지로 새 노트 만들기**: PDF 노트 등에서 페이지를 골라 같은 폴더에 별도의 노트로(필기 · 이미지 · 책갈피 포함, 원본은 그대로)
- 실행 취소/다시 실행(페이지 편집 포함), 두 손가락 탭 실행 취소 · 세 손가락 탭 다시 실행
- 연속 세로 스크롤 또는 **옆으로 넘기기**(한 화면에 한 페이지, 밀어서 넘김), 0.5×–5× 확대, 스타일러스 전용 모드(손가락은 스크롤) / 손가락 필기 모드(두 손가락 스크롤)
- 내보내기: PDF(필기는 벡터), 페이지 이미지, NAS 폴더에 PDF 저장

**PDF 뷰어**
- 연속 보기, 확대, 페이지 썸네일, 페이지 스크러버, 공유
- 문서 내 검색(이전/다음, 강조) — Android 15 이상
- "필기 시작": 원본 PDF는 그대로 두고 옆에 `<PDF> 필기.inknote`를 만들어 필기(다시 열면 같은 노트로)

## 설치 (릴리스 APK)

[Releases](https://github.com/lemon7679/note-android/releases)에서 `Note-<버전>.apk`를 받아 설치합니다
(설정에서 "출처를 알 수 없는 앱 설치"를 허용). 또는 `adb install Note-0.1.0.apk`.

새 릴리스는 Actions › **Release** › Run workflow에 버전을 넣으면 APK를 빌드해 올립니다(릴리스 노트: `docs/releases/v<버전>.md`).
모든 릴리스를 같은 키로 서명하려면 저장소 Secrets에 `NOTE_KEYSTORE_BASE64`(JKS 파일의 base64), `NOTE_KEYSTORE_PASSWORD`,
`NOTE_KEY_ALIAS`, `NOTE_KEY_PASSWORD`를 넣으세요. 없으면 그 빌드에서만 쓰는 키로 서명하므로, 다음 버전은 기존 앱을 지우고
설치해야 합니다(이 기기 라이브러리의 노트도 지워지므로 NAS와 먼저 동기화하세요).

## 빌드

```bash
# 1) 두 저장소를 나란히 받기 (note-core가 없으면 CMake가 GitHub main에서 받아 옵니다)
git clone https://github.com/lemon7679/note-core.git
git clone https://github.com/lemon7679/note-android.git
cd note-android

# 2) Android SDK(platform 35, build-tools 35), NDK 27.2, CMake 3.22.1, JDK 17
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 3) 테스트와 빌드
./gradlew testDebugUnitTest      # 동기화 엔진 · NAS 클라이언트 · 파일 형식 (JVM)
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # 연결된 기기에 설치
```

- note-core 위치: `$NOTE_CORE_PATH` → `../note-core` → GitHub(`main`) 순서로 찾습니다.
- 서명된 릴리스: `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword) 또는
  `NOTE_KEYSTORE*` 환경 변수를 두고 `./gradlew assembleRelease`.

## 구조

```
app/src/main/
  cpp/                  ink_jni.cpp — note-core C API의 JNI 바인딩, CMakeLists.txt(엔진 포함)
  java/com/lemon7679/note/
    ink/                InkEngine.kt — StrokeBuilder · InkPage · PenSample (iPad InkBridge와 같은 API)
    core/               플랫폼 독립 핵심 — JVM에서 테스트 (iPad NoteKit 대응)
      Models.kt           NotebookDocument, 종이 템플릿, 페이지 크기 (iPad와 같은 JSON)
      Library.kt          .inknote 패키지, 라이브러리 파일 시스템, 휴지통
      Remote.kt Http.kt   RemoteFileSystem, OkHttp 전송(TOFU 인증서 고정)
      WebDav.kt Synology.kt
      Accounts.kt         NAS 계정, 클라이언트 생성
      SyncPlanner.kt      3-way 규칙(순수 함수)   SyncEngine.kt  실행(코루틴, 직렬화)
      InkOutline.kt       엔진 SVG → 벡터 윤곽(PDF 내보내기)
    app/                AppModel(소스·계정·동기화·즐겨찾기), 경로, 설정, 키스토어, 폴더(SAF) 파일 시스템
    render/             PdfRenderer 래퍼, 종이 템플릿, 페이지 이미지, PDF 내보내기
    editor/             EditorModel(페이지·실행 취소·자동 저장), CanvasView(타일 렌더러·입력), 편집기 화면
    ui/                 디자인 토큰(Quiet Ink), 공용 컴포넌트, 라이브러리, 시트, NAS 연결, 동기화 센터, PDF 뷰어
app/src/test/           JVM 테스트: 동기화 시나리오(두 기기 + 가짜 NAS), WebDAV/Synology 파싱, iPad 파일 호환
app/src/androidTest/    에뮬레이터 스모크 테스트(엔진 JNI, 스타일러스 입력, PDF, WebDAV 실동기화)
docs/                   DESIGN.md(디자인 → Compose 대응), ARCHITECTURE.md
```

## CI

`.github/workflows/ci.yml` — note-core를 함께 받아 실제 엔진으로 검사합니다.
- 단위 테스트(JVM)와 디버그 APK 빌드
- 에뮬레이터(Pixel C 태블릿 · Pixel 6, API 34)에서 스모크 테스트: 엔진 JNI 왕복, 라이브러리 → 편집기에서 스타일러스
  이벤트를 주입해 필기(도형 스냅 포함) → 저장 확인, PDF 필기 노트, PDF 뷰어, 새 노트 · NAS 연결 · 설정 화면,
  러너의 WebDAV 서버(rclone)와 실제 동기화. 최적화(R8)된 릴리스 빌드도 설치해 노트를 열어 봅니다.
  스크린샷은 로그(`@@SHOT`)와 아티팩트로 남깁니다.

## 알려진 한계 / 다음 단계

- 캔버스는 CPU 타일 렌더러입니다(`ink_editor_render_over_rgba` / `ink_builder_render_over_rgba`). GPU(Vulkan/GLES) 렌더러와
  androidx.graphics 전면 버퍼 렌더링을 붙이면 지연이 더 줄어듭니다.
- PDF 내보내기에서 원본 PDF 페이지는 고해상도 이미지로 들어갑니다(Android에는 PDF 페이지를 벡터로 옮기는 API가 없음). 필기는 벡터입니다.
- PDF 목차는 Android PdfRenderer가 제공하지 않아 표시하지 않습니다. 문서 내 검색은 Android 15 이상.
- WebDAV Digest 인증은 지원하지 않습니다(Basic만). SMB는 저장소 제공자 앱을 통한 폴더 선택 방식입니다.
- 실제 스타일러스 기기(Galaxy Tab S Pen 등)와 실제 NAS에서는 아직 손으로 검증하지 않았습니다.
