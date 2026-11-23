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
    ├ preview   페이지 전체 저해상도 이미지(스크롤 중 빈 화면 방지)
    ├ tiles     512 px 타일 = 종이/PDF 배경 + 확정된 잉크 (확대가 끝나면 새 배율로, 다 될 때까지 이전 타일 유지)
    ├ overlay   그리는 중인 획(형광펜은 BlendMode.MULTIPLY)
    ├ elements  텍스트 상자 · 이미지(페이지 단위 벡터)
    └ decoration 올가미, 선택 상자/손잡이, 떠 있는 선택 이미지, 지우개 커서, 검색 강조, 레이저
```

- 프레임 루프(Choreographer): 그리는 중이면 `StrokeBuilder.tick`(도형 스냅, 햅틱) → `updateGeometry`의 변경 영역에 걸리는
  오버레이 타일만 다시 그림. 남는 시간에 화면 중앙에 가까운 페이지부터 타일 작업(그리는 중 2 ms, 평소 7 ms 예산).
- 펜을 떼면 확정 → 잉크 타일을 같은 프레임에 다시 그린 뒤 오버레이를 치우므로 깜빡임이 없습니다.
- PDF 배경은 타일별로 캐시해 잉크만 바뀔 때 PDF를 다시 그리지 않습니다.
- 입력: `MotionEvent`의 과거 샘플 전부(240 Hz급), 필압, `AXIS_TILT` → 고도, `orientation` → 방위, `requestUnbufferedDispatch`.
  스타일러스는 항상 그리고, 손가락은 설정에 따라 스크롤/확대 또는 필기. 그리는 중 다른 손가락 = 완벽한 도형.
  스타일러스 지우개 끝(`TOOL_TYPE_ERASER`)이나 옆 버튼 = 지우개(iPad의 Pencil 더블 탭 대응).
- 올가미 이동: 선택한 획을 `setHidden`으로 타일에서 빼고 `renderStrokesOver`로 떠 있는 비트맵에 그려 따라가게 한 뒤,
  놓을 때 `transform` 한 번(실행 취소 1단계)으로 확정.

## 편집 모델 · 저장 · 동기화

iPad와 같습니다: 페이지 잉크는 필요할 때 `pages/<id>.ink`에서 불러오고, 실행 취소는 "어느 페이지를 고쳤는지" 스택과
문서 구조 스냅숏을 함께 쌓아 노트 전체에서 시간 순서대로 되돌립니다. 자동 저장은 편집 2초 뒤, 앱이 멈출 때, 노트를 닫을 때.

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
