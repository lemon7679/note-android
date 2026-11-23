cmake_minimum_required(VERSION 3.18)
project(NoteInk LANGUAGES CXX)

# InkEngine from note-core: NOTE_CORE_PATH (set by Gradle: $NOTE_CORE_PATH or ../note-core),
# otherwise fetched from GitHub.
set(NOTE_CORE_PATH "" CACHE PATH "note-core checkout")
set(INK_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(INK_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
if(NOTE_CORE_PATH AND EXISTS "${NOTE_CORE_PATH}/engine/CMakeLists.txt")
  message(STATUS "InkEngine: ${NOTE_CORE_PATH}")
  add_subdirectory("${NOTE_CORE_PATH}/engine" inkengine)
else()
  include(FetchContent)
  message(STATUS "InkEngine: fetching github.com/lemon7679/note-core (main)")
  FetchContent_Declare(notecore GIT_REPOSITORY https://github.com/lemon7679/note-core.git GIT_TAG main
                       SOURCE_SUBDIR engine)
  FetchContent_MakeAvailable(notecore)
endif()

add_library(noteink SHARED ink_jni.cpp)
target_link_libraries(noteink PRIVATE inkengine jnigraphics log)
target_compile_options(noteink PRIVATE -Wall -Wextra)
# 16 KB page size support (Android 15+ devices).
target_link_options(noteink PRIVATE "-Wl,-z,max-page-size=16384")
