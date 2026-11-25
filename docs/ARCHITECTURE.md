# 설계

iPad 앱(note-ipad)과 같은 모듈 경계와 규칙을 따릅니다. 자세한 동기화 규칙 표와 파일 형식은
[note-ipad docs/ARCHITECTURE.md](https://github.com/lemon7679/note-ipad/blob/main/docs/ARCHITECTURE.md)와 같습니다.

```
app (Compose + 커스텀 View)
 ├─ core (JVM만 사용 — 단위 테스트)          ← iPad NoteKit
 └─ ink (Kotlin 래퍼) ── JNI ── InkEngine     ← iPad InkBridge, note-core C API
```

## 엔진 연결 (JNI)

- `app/src/main/cpp/CMakeLists.txt`가 note-core의 `engine/`을 `add_subdirectory`로 포함해 `libnoteink.so`를 만듭니다
  (정적 libc++, 16 KB 페이지 정렬).
- `ink_jni.cpp`는 C API를 1:1로 감쌉니다. 입력 샘플은 float 배열(x, y, 필압, 고도, 방위) + 시간 + 플래그로 한 번에 넘겨
  JNI 호출 수를 줄입니다.
- 렌더링: Android `Bitmap.Config.ARGB_8888`의 메모리 배치는 premultiplied RGBA8이므로 엔진의 `*_render_over_rgba`에
  `AndroidBitmap_lockPixels`로 얻은 버퍼를 그대로 넘깁니다(복사 없음).

## 캔버스 렌더링 (`editor/CanvasView.kt`)

```
CanvasView (페이지 세로 배치, 0.5–5× 확대, 손가락 스크롤/핀치, 관성 스크롤)
 └ 페이지마다
    ├ preview   페이지 전체 저해상도 이미지(스크롤 중 빈 화면 방지, 백그라운드 생성)
    ├ tiles     512 px 타일 = 종이/PDF 배경 + 확정된 잉크 (확대 단계별, 새 단계가 다 될 때까지 이전 타일 유지)
    ├ overlay   그리는 중인 획(형광펜은 BlendMode.MULTIPLY)
    ├ elements  텍스트 상자 · 이미지(페이지 단위 벡터)
    └ decoration 올가미, 선택 상자/손잡이, 떠 있는 선택 이미지, 지우개 커서, 검색 강조, 레이저
```

**타일은 백그라운드에서** (`render/TileWorkers.kt`) — 지도·PDF 뷰어와 같은 구조입니다.
- UI 스레드는 무엇을 그릴지만 정하고 완성된 비트맵을 바꿔 끼웁니다. 래스터화는 작업 스레드 1–3개
  (코어 수 − 1)가 **잉크 스냅숏**(`InkPage.snapshot()`, 엔진의 불변 사본)으로 합니다. 스크롤·확대가 잉크
  렌더링을 기다리지 않습니다.
- 우선순위: 화면 가운데에서 가까운 타일부터, 화면 밖 여백 타일은 마지막. 확대 단계가 바뀌면 대기 중인 이전 단계
  작업은 버립니다.
- 타일마다 `version`이 있어 그 아래 잉크가 바뀔 때마다 올라갑니다. 작업 결과가 그보다 오래됐으면 버립니다
  (빈 타일이면 일단 보여 주고 다시 요청). 새 결과가 올 때까지 이전 비트맵을 계속 그리므로 깜빡이지 않습니다.
- 비트맵 풀(`BitmapPool`)은 UI 스레드와 작업 스레드가 함께 씁니다. PDF 배경 비트맵은 작업 스레드가 읽고 있을 수
  있어 직접 recycle하지 않고 GC에 맡깁니다.

**GPU** (설정 `gpuRendering`, 기본 켜짐) — note-core의 OpenGL ES 3 렌더러(`ink_gpu.h`)를 씁니다.
- 타일: 작업 스레드마다 헤드리스 EGL 컨텍스트와 렌더러를 처음 쓸 때 만들고(`render/GpuTiles.kt`) 스레드가 끝날 때
  정리합니다. 배경(종이/PDF)이 그려진 비트맵을 올려 잉크를 합성하고 다시 읽어 오므로 나머지 타일 처리(풀, 버전,
  부분 갱신)는 그대로입니다. 컨텍스트나 반 정밀도 렌더 타깃이 없으면 그 기기는 CPU 래스터라이저로 돌아갑니다.
  UI 스레드의 작은 부분 갱신(펜 뗌, 지우개 프레임)은 CPU로 합니다 — 두 결과의 차이는 8비트 3단계 이내입니다.
- 라이브 획(`editor/LiveInkLayer.kt`): 캔버스 위의 투명 `SurfaceView` + androidx.graphics `GLFrontBufferedRenderer`.
  매 프레임 엔진이 알려 주는 변경 영역만 프론트 버퍼에서 지우고 다시 그리며(스캔 중인 버퍼를 통째로 지우지 않음),
  펜을 떼면 획을 타일에 넣고 캔버스의 그 프레임이 화면에 넘어간 뒤 레이어를 숨깁니다(틈 대신 한 프레임 겹침).
  형광펜(Multiply)은 아래 페이지가 필요해 캔버스 안의 오버레이로 그립니다.

**확대 단계** — 렌더 배율은 2^(1/3) 간격의 단계 중 현재 배율 이상인 가장 작은 값입니다(타일을 늘려서 흐려지는 일
없이 최대 1.26배 축소해 표시). 같은 단계 안의 작은 핀치는 다시 그리지 않고, 단계가 바뀌면 이전 타일을 늘려
보여 주다가 새 타일로 바꿉니다.

**편집은 그 자리에서, 손상 영역만** — 펜을 뗄 때와 지우는 동안에는 바뀐 영역만 UI 스레드에서 바로 다시 그립니다
(`renderTilePart`). 엔진의 래스터화가 잉크 면적에 비례하고, 정밀 지우개는 지운 부근만 손상 영역으로 알려 주므로
작습니다. 그다음 오버레이를 치우므로 깜빡임이 없습니다.

- 프레임 루프(Choreographer): 그리는 중이면 `StrokeBuilder.tick`(도형 스냅, 햅틱) → `updateGeometry`의 변경 영역에 걸리는
  오버레이 타일만 다시 그림. 지우개는 이동마다가 아니라 프레임마다 한 번 손상 영역을 다시 그림.
- PDF 배경은 타일별로 캐시해 잉크만 바뀔 때 PDF를 다시 그리지 않습니다.
- 입력: `MotionEvent`의 과거 샘플 전부(240 Hz급), 필압, `AXIS_TILT` → 고도, `orientation` → 방위, `requestUnbufferedDispatch`.
  스타일러스는 항상 그리고, 손가락은 설정에 따라 스크롤/확대 또는 필기. 그리는 중 다른 손가락 = 완벽한 도형.
  스타일러스 지우개 끝(`TOOL_TYPE_ERASER`)이나 옆 버튼을 누른 채 쓰기 = 지우개.
  펜을 띄운 채(호버) 옆 버튼을 눌렀다 떼면 `CanvasHost.stylusButtonClicked` → 펜 끝을 감싸는 도구 팔레트 `ToolWheel`(iPad의 Pencil 더블 탭 · 스퀴즈 대응).
  Android 14+의 `KEYCODE_STYLUS_BUTTON_*` 키 이벤트도 같은 클릭으로 받습니다.
- 올가미 이동: 선택한 획을 `setHidden`으로 타일에서 빼고 `renderStrokesOver`로 떠 있는 비트맵에 그려 따라가게 한 뒤,
  놓을 때 `transform` 한 번(실행 취소 1단계)으로 확정.

## 편집 모델 · 저장 · 동기화

iPad와 같습니다: 페이지 잉크는 필요할 때 `pages/<id>.ink`에서 불러오고, 실행 취소는 "어느 페이지를 고쳤는지" 스택과
문서 구조 스냅숏을 함께 쌓아 노트 전체에서 시간 순서대로 되돌립니다. 자동 저장은 편집 2초 뒤, 앱이 멈출 때, 노트를 닫을 때.

저장은 UI 스레드에서 **무엇을 쓸지만 잡고**(바뀐 페이지의 잉크 스냅숏 + 불변 문서 객체, 직렬화 없음) 전용 저장 스레드
하나가 직렬화 · 원자적 파일 쓰기(임시 파일 → rename) · 표지 썸네일을 처리합니다. 저장은 잡은 순서대로 실행되고,
동기 저장(멈춤, 닫기, 내보내기)은 앞서 대기 중인 자동 저장까지 끝난 뒤 돌아오므로 오래된 내용이 나중에 덮어쓰는
일이 없습니다. 실패하면 해당 페이지를 다시 "바뀜"으로 표시해 다음 저장에서 재시도합니다. 페이지 목록 썸네일도
스냅숏으로 백그라운드에서 그립니다.

```
이 기기:   files/Library/                     (라이브러리 루트)
NAS 계정:  files/Note/Mirrors/<계정 ID>/       ⇄  NAS의 노트 폴더
상태:      files/Note/Sync/<계정 ID>.json
휴지통:    files/Note/Trash/<소스>/
계정:      files/Note/accounts.json (암호 제외), 암호는 SharedPreferences에 키스토어 AES-GCM으로
```

- `SyncPlanner`(순수 함수)와 `SyncEngine`은 iPad 코드를 그대로 옮겼습니다. Swift actor 대신 `Mutex`로 한 계정의
  작업을 직렬화하고, 전송은 `Dispatchers.IO`에서 합니다. 단위 테스트가 iPad와 같은 시나리오를 검사합니다.
- 파일 형식 호환: 날짜는 소수점 없는 ISO 8601, id는 대문자 UUID, nil은 생략 — Swift `JSONEncoder`/`JSONDecoder`와
  서로 읽고 쓸 수 있습니다(`IpadCompatibilityTest`). 충돌 사본 이름의 기기 이름은 `Build.MODEL`입니다.
