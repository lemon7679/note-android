# 디자인 → 코드

note-ipad의 Claude Design 캔버스("Note iPad UI", 아트보드 7개)와 토큰("Quiet Ink")을 Jetpack Compose로 옮겼습니다.
iPad 쪽 대응표는 [note-ipad docs/DESIGN.md](https://github.com/lemon7679/note-ipad/blob/main/docs/DESIGN.md).

| 아트보드 | 화면 | iPad | Android |
|---|---|---|---|
| 라이브러리 · NAS | 사이드바, 경로 헤더, 칩, 폴더 카드, 노트·PDF 카드와 동기화 배지 | `RootView`, `LibraryView` | `ui/RootScreen.kt`, `ui/LibraryScreen.kt` |
| 노트 편집기 | 상단 바(52), 도구 줄(56), 페이지 목록(132), 올가미 메뉴, 쪽 표시, 도형 힌트 | `NotebookEditorView`, `Canvas/*` | `editor/EditorScreen.kt`, `editor/CanvasView.kt` |
| PDF 뷰어 | 썸네일, 검색 막대, 스크러버, "필기 시작" | `PDFReaderView` | `ui/PdfReaderScreen.kt` |
| 저장소 연결 | 방식 카드 3개, 검색 목록, 서버/계정 그룹, 연결 테스트 | `NASConnectView` | `ui/NasScreens.kt` |
| 새 노트 | 빈 노트/PDF/폴더, 표지 색, 종이 템플릿 7종, 크기·방향, 저장 위치 | `LibrarySheets` | `ui/Sheets.kt` (`NewItemSheet`) |
| 동기화 센터 | 진행률, 활동, 충돌 카드, 옵션 | `SyncCenterView` | `ui/Sheets.kt` (`SyncCenterDialog`) |
| 디자인 토큰 | 색·타이포·모양·컴포넌트 | `NoteTheme`, `Components` | `ui/Theme.kt`, `ui/Components.kt` |

## 토큰 ("Quiet Ink")

라이트/다크 값은 iPad와 같습니다(`ui/Theme.kt`의 `colors(dark)`): background `#F6F7F9`/`#111317`, sidebar `#EEF0F3`/`#171A1F`,
desk `#E9EBEF`/`#0B0C0F`, surface `#FFFFFF`/`#1E2128`, accent `#2F5BD3`/`#7A9CFF`, synced · conflict · danger · offline 등.
종이(줄 `#DCE3F0`, 여백선 `#F2C9C9`)는 항상 밝습니다. Material 3 색 체계에도 같은 값을 넣어 시스템 컴포넌트(대화상자, 메뉴, 스위치)가 어울리게 했습니다.

- 모양: 버튼·입력 radius 10 · 높이 40–44, 카드 14, 팝오버 18, 시트 20, 칩·배지 캡슐, 터치 영역 최소 44×44 dp.
- 간격: 4 · 8 · 12 · 16 · 20 · 24 · 28(페이지 여백).
- 글자: 시스템 글꼴(한글은 Noto Sans CJK). 28 bold, 22 semibold, 17 semibold, 15 본문, 13 semibold 레이블, 12 보조.
- 아이콘: SF Symbols 대신 Material Icons(같은 의미), Material에 없는 펜 촉 · 지우개 · 올가미 · 레이저는 디자인의 선 아이콘을
  벡터로 그렸습니다(`NoteIcons`).
- 펜 색 `#1C1C1E #1F4FD1 #D12F2F #12805C #7A3FC4 #E07A1F`, 형광펜 `#FFE45C #8EF0A8 #7FD3FF #FF9ECF`.

## 화면 크기

- 너비 840 dp 이상(태블릿 가로): 사이드바 288 dp 고정 — iPad 분할 보기와 같습니다.
- 그보다 좁으면(휴대폰, 태블릿 세로 일부): 사이드바가 서랍 메뉴로, 검색은 아이콘으로, "새로 만들기"는 + 버튼으로 줄어듭니다.
- 편집기는 700 dp보다 좁으면 페이지 목록을 기본으로 숨기고, 책갈피·검색을 "더 보기" 메뉴로 옮깁니다.
- 시트는 태블릿에서 가운데 카드, 휴대폰에서 전체 화면입니다.
