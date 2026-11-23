// JNI face of note-core's InkEngine C API (ink_c.h) for com.lemon7679.note.ink.InkNative.
// Kotlin wraps these in StrokeBuilder / InkPage; nothing else calls them.
// Engine objects are not thread-safe: the app uses them from the main thread.

#include <android/bitmap.h>
#include <jni.h>

#include <cstring>
#include <vector>

#include "ink/ink_c.h"

namespace {

InkBuilder* B(jlong h) { return reinterpret_cast<InkBuilder*>(h); }
InkEditor* E(jlong h) { return reinterpret_cast<InkEditor*>(h); }

std::vector<InkStrokeId> ids(JNIEnv* env, jlongArray arr) {
  std::vector<InkStrokeId> out;
  if (!arr) return out;
  jsize n = env->GetArrayLength(arr);
  out.resize(static_cast<size_t>(n));
  if (n > 0) env->GetLongArrayRegion(arr, 0, n, reinterpret_cast<jlong*>(out.data()));
  return out;
}

jlongArray toJava(JNIEnv* env, const InkStrokeId* v, size_t n) {
  jlongArray arr = env->NewLongArray(static_cast<jsize>(n));
  if (arr && n > 0) env->SetLongArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jlong*>(v));
  return arr;
}

jbyteArray toJava(JNIEnv* env, const uint8_t* data, size_t n) {
  jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
  if (arr && n > 0) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(data));
  return arr;
}

bool writeRect(JNIEnv* env, InkRect r, jfloatArray out) {
  bool ok = r.width > 0 && r.height > 0;
  if (out && env->GetArrayLength(out) >= 4) {
    float v[4] = {r.x, r.y, r.width, r.height};
    env->SetFloatArrayRegion(out, 0, 4, v);
  }
  return ok;
}

// Samples arrive packed: floats [x, y, pressure, altitude, azimuth] per point, times in
// seconds, update ids, and ints [flags | source << 8].
std::vector<InkInputPoint> points(JNIEnv* env, jfloatArray xyp, jdoubleArray times, jlongArray updates,
                                  jintArray bits, jint count) {
  std::vector<InkInputPoint> pts;
  if (count <= 0 || !xyp) return pts;
  std::vector<float> f(static_cast<size_t>(count) * 5);
  std::vector<double> t(static_cast<size_t>(count));
  std::vector<jlong> u(static_cast<size_t>(count));
  std::vector<jint> b(static_cast<size_t>(count));
  env->GetFloatArrayRegion(xyp, 0, count * 5, f.data());
  env->GetDoubleArrayRegion(times, 0, count, t.data());
  env->GetLongArrayRegion(updates, 0, count, u.data());
  env->GetIntArrayRegion(bits, 0, count, b.data());
  pts.resize(static_cast<size_t>(count));
  for (jint i = 0; i < count; ++i) {
    InkInputPoint& p = pts[static_cast<size_t>(i)];
    std::memset(&p, 0, sizeof(p));
    p.x = f[i * 5 + 0];
    p.y = f[i * 5 + 1];
    p.pressure = f[i * 5 + 2];
    p.altitude = f[i * 5 + 3];
    p.azimuth = f[i * 5 + 4];
    p.time = t[static_cast<size_t>(i)];
    p.update_id = static_cast<uint64_t>(u[static_cast<size_t>(i)]);
    p.flags = static_cast<uint32_t>(b[static_cast<size_t>(i)] & 0xFF);
    p.source = static_cast<uint32_t>((b[static_cast<size_t>(i)] >> 8) & 0xFF);
  }
  return pts;
}

struct LockedBitmap {
  JNIEnv* env;
  jobject bitmap;
  void* pixels = nullptr;
  AndroidBitmapInfo info{};

  LockedBitmap(JNIEnv* e, jobject b) : env(e), bitmap(b) {
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) pixels = nullptr;
  }
  ~LockedBitmap() {
    if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
  }
  explicit operator bool() const { return pixels != nullptr; }
  uint8_t* data() const { return static_cast<uint8_t*>(pixels); }
};

InkColor color(jfloat r, jfloat g, jfloat b, jfloat a) { return InkColor{r, g, b, a}; }

}  // namespace

#define FN(name) JNICALL Java_com_lemon7679_note_ink_InkNative_##name

extern "C" {

JNIEXPORT jstring FN(version)(JNIEnv* env, jclass) { return env->NewStringUTF(ink_version()); }

// ------------------------------------------------------------------ builder

JNIEXPORT jlong FN(builderCreate)(JNIEnv*, jclass, jint kind) {
  return reinterpret_cast<jlong>(ink_builder_create(static_cast<InkBrushKind>(kind)));
}
JNIEXPORT void FN(builderDestroy)(JNIEnv*, jclass, jlong b) { ink_builder_destroy(B(b)); }
JNIEXPORT void FN(builderSetBrush)(JNIEnv*, jclass, jlong b, jint kind, jfloat r, jfloat g, jfloat bl, jfloat a,
                                   jfloat width) {
  ink_builder_set_brush(B(b), static_cast<InkBrushKind>(kind), color(r, g, bl, a), width);
}
JNIEXPORT void FN(builderSetColor)(JNIEnv*, jclass, jlong b, jfloat r, jfloat g, jfloat bl, jfloat a) {
  ink_builder_set_color(B(b), color(r, g, bl, a));
}
JNIEXPORT void FN(builderSetWidth)(JNIEnv*, jclass, jlong b, jfloat w) { ink_builder_set_width(B(b), w); }
JNIEXPORT void FN(builderSetOpacity)(JNIEnv*, jclass, jlong b, jfloat o) { ink_builder_set_opacity(B(b), o); }
JNIEXPORT void FN(builderSetStabilizer)(JNIEnv*, jclass, jlong b, jint mode, jfloat amount) {
  ink_builder_set_stabilizer(B(b), static_cast<InkStabilizerMode>(mode), amount);
}
JNIEXPORT void FN(builderSetPressureCurve)(JNIEnv*, jclass, jlong b, jfloat c1x, jfloat c1y, jfloat c2x, jfloat c2y) {
  ink_builder_set_pressure_curve(B(b), c1x, c1y, c2x, c2y);
}
JNIEXPORT void FN(builderSetSnapOnHold)(JNIEnv*, jclass, jlong b, jboolean on) { ink_builder_set_snap_on_hold(B(b), on); }
JNIEXPORT void FN(builderSetBuiltinPrediction)(JNIEnv*, jclass, jlong b, jboolean on) {
  ink_builder_set_builtin_prediction(B(b), on);
}
JNIEXPORT void FN(builderSetViewScale)(JNIEnv*, jclass, jlong b, jfloat s) { ink_builder_set_view_scale(B(b), s); }
JNIEXPORT void FN(builderSetCorrection)(JNIEnv*, jclass, jlong b, jfloat s) { ink_builder_set_correction(B(b), s); }
JNIEXPORT void FN(builderSetCorrectionOptions)(JNIEnv*, jclass, jlong b, jboolean live, jboolean smooth,
                                               jboolean straighten, jboolean snap) {
  ink_builder_set_correction_options(B(b), live, smooth, straighten, snap);
}
JNIEXPORT void FN(builderSetShapeConstraint)(JNIEnv*, jclass, jlong b, jboolean on) {
  ink_builder_set_shape_constraint(B(b), on);
}
JNIEXPORT void FN(builderBegin)(JNIEnv* env, jclass, jlong b, jfloatArray xyp, jdoubleArray t, jlongArray u,
                                jintArray bits) {
  auto pts = points(env, xyp, t, u, bits, 1);
  if (!pts.empty()) ink_builder_begin(B(b), &pts[0]);
}
JNIEXPORT void FN(builderAddPoints)(JNIEnv* env, jclass, jlong b, jfloatArray xyp, jdoubleArray t, jlongArray u,
                                    jintArray bits, jint count) {
  auto pts = points(env, xyp, t, u, bits, count);
  if (!pts.empty()) ink_builder_add_points(B(b), pts.data(), pts.size());
}
JNIEXPORT void FN(builderSetPredicted)(JNIEnv* env, jclass, jlong b, jfloatArray xyp, jdoubleArray t, jlongArray u,
                                       jintArray bits, jint count) {
  auto pts = points(env, xyp, t, u, bits, count);
  ink_builder_set_predicted(B(b), pts.empty() ? nullptr : pts.data(), pts.size());
}
JNIEXPORT jboolean FN(builderUpdateEstimated)(JNIEnv*, jclass, jlong b, jlong id, jfloat p, jfloat alt, jfloat az) {
  return ink_builder_update_estimated(B(b), static_cast<uint64_t>(id), p, alt, az);
}
JNIEXPORT jboolean FN(builderTick)(JNIEnv*, jclass, jlong b, jdouble now) { return ink_builder_tick(B(b), now); }
JNIEXPORT void FN(builderEnd)(JNIEnv*, jclass, jlong b) { ink_builder_end(B(b)); }
JNIEXPORT void FN(builderCancel)(JNIEnv*, jclass, jlong b) { ink_builder_cancel(B(b)); }
JNIEXPORT jboolean FN(builderIsActive)(JNIEnv*, jclass, jlong b) { return ink_builder_is_active(B(b)); }
JNIEXPORT jint FN(builderSnappedShape)(JNIEnv*, jclass, jlong b) { return ink_builder_snapped_shape(B(b)); }
JNIEXPORT jint FN(builderPointCount)(JNIEnv*, jclass, jlong b) {
  return static_cast<jint>(ink_builder_point_count(B(b)));
}
JNIEXPORT jboolean FN(builderBlendsMultiply)(JNIEnv*, jclass, jlong b) {
  InkStyleInfo info{};
  ink_builder_get_style(B(b), &info);
  return info.blend == 1;
}
JNIEXPORT jboolean FN(builderUpdateGeometry)(JNIEnv* env, jclass, jlong b, jfloat ppu, jfloatArray dirty) {
  InkLiveGeometry live{};
  if (!ink_builder_update_geometry(B(b), ppu, &live)) {
    writeRect(env, InkRect{0, 0, 0, 0}, dirty);
    return false;
  }
  return writeRect(env, live.dirty, dirty);
}
JNIEXPORT void FN(builderRenderOver)(JNIEnv* env, jclass, jlong b, jobject bitmap, jfloat scale, jfloat ox,
                                     jfloat oy) {
  LockedBitmap bm(env, bitmap);
  if (!bm) return;
  ink_builder_render_over_rgba(B(b), bm.data(), static_cast<int>(bm.info.width), static_cast<int>(bm.info.height),
                               bm.info.stride, scale, ox, oy);
}

// ------------------------------------------------------------------- editor

JNIEXPORT jlong FN(editorCreate)(JNIEnv*, jclass, jfloat w, jfloat h) {
  return reinterpret_cast<jlong>(ink_editor_create(w, h));
}
JNIEXPORT void FN(editorDestroy)(JNIEnv*, jclass, jlong e) { ink_editor_destroy(E(e)); }
JNIEXPORT jlong FN(editorCommit)(JNIEnv*, jclass, jlong e, jlong b) {
  return static_cast<jlong>(ink_editor_commit(E(e), B(b)));
}
JNIEXPORT jint FN(editorStrokeCount)(JNIEnv*, jclass, jlong e) {
  return static_cast<jint>(ink_editor_stroke_count(E(e)));
}
JNIEXPORT jboolean FN(editorUndo)(JNIEnv*, jclass, jlong e) { return ink_editor_undo(E(e)); }
JNIEXPORT jboolean FN(editorRedo)(JNIEnv*, jclass, jlong e) { return ink_editor_redo(E(e)); }
JNIEXPORT jboolean FN(editorCanUndo)(JNIEnv*, jclass, jlong e) { return ink_editor_can_undo(E(e)); }
JNIEXPORT jboolean FN(editorCanRedo)(JNIEnv*, jclass, jlong e) { return ink_editor_can_redo(E(e)); }
JNIEXPORT void FN(editorEraseBegin)(JNIEnv*, jclass, jlong e, jint mode, jfloat radius, jint mask, jfloat x,
                                    jfloat y) {
  ink_editor_erase_begin(E(e), static_cast<InkEraserMode>(mode), radius, static_cast<uint32_t>(mask), x, y);
}
JNIEXPORT jboolean FN(editorEraseMove)(JNIEnv*, jclass, jlong e, jfloat x, jfloat y) {
  return ink_editor_erase_move(E(e), x, y);
}
JNIEXPORT void FN(editorEraseEnd)(JNIEnv*, jclass, jlong e) { ink_editor_erase_end(E(e)); }

JNIEXPORT jlongArray FN(editorSelectLasso)(JNIEnv* env, jclass, jlong e, jfloatArray xy) {
  jsize n = env->GetArrayLength(xy);
  std::vector<float> v(static_cast<size_t>(n));
  env->GetFloatArrayRegion(xy, 0, n, v.data());
  size_t capacity = ink_editor_stroke_count(E(e)) + 1;
  std::vector<InkStrokeId> out(capacity);
  size_t count = ink_editor_select_lasso(E(e), v.data(), static_cast<size_t>(n / 2), out.data(), capacity);
  return toJava(env, out.data(), count < capacity ? count : capacity);
}
JNIEXPORT jboolean FN(editorTransform)(JNIEnv* env, jclass, jlong e, jlongArray arr, jfloat a, jfloat b, jfloat c,
                                       jfloat d, jfloat tx, jfloat ty) {
  auto v = ids(env, arr);
  return !v.empty() && ink_editor_transform(E(e), v.data(), v.size(), a, b, c, d, tx, ty);
}
JNIEXPORT jboolean FN(editorRecolor)(JNIEnv* env, jclass, jlong e, jlongArray arr, jfloat r, jfloat g, jfloat b,
                                     jfloat a) {
  auto v = ids(env, arr);
  return !v.empty() && ink_editor_recolor(E(e), v.data(), v.size(), color(r, g, b, a));
}
JNIEXPORT jboolean FN(editorDelete)(JNIEnv* env, jclass, jlong e, jlongArray arr) {
  auto v = ids(env, arr);
  return !v.empty() && ink_editor_delete(E(e), v.data(), v.size());
}
JNIEXPORT jlongArray FN(editorDuplicate)(JNIEnv* env, jclass, jlong e, jlongArray arr, jfloat dx, jfloat dy) {
  auto v = ids(env, arr);
  std::vector<InkStrokeId> out(v.size() + 1);
  size_t n = v.empty() ? 0 : ink_editor_duplicate(E(e), v.data(), v.size(), dx, dy, out.data(), out.size());
  return toJava(env, out.data(), n < out.size() ? n : out.size());
}
JNIEXPORT jboolean FN(editorBoundsOf)(JNIEnv* env, jclass, jlong e, jlongArray arr, jfloatArray outRect) {
  auto v = ids(env, arr);
  if (v.empty()) return writeRect(env, InkRect{0, 0, 0, 0}, outRect);
  return writeRect(env, ink_editor_bounds_of(E(e), v.data(), v.size()), outRect);
}
JNIEXPORT jbyteArray FN(editorCopyStrokes)(JNIEnv* env, jclass, jlong e, jlongArray arr) {
  auto v = ids(env, arr);
  if (v.empty()) return nullptr;
  size_t size = 0;
  uint8_t* data = ink_editor_copy_strokes(E(e), v.data(), v.size(), &size);
  if (!data) return nullptr;
  jbyteArray out = toJava(env, data, size);
  ink_free(data);
  return out;
}
JNIEXPORT jlongArray FN(editorPaste)(JNIEnv* env, jclass, jlong e, jbyteArray data, jfloat dx, jfloat dy) {
  jsize n = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(n));
  env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
  std::vector<InkStrokeId> out(4096);
  size_t count = n > 0 ? ink_editor_paste_strokes(E(e), bytes.data(), bytes.size(), dx, dy, out.data(), out.size()) : 0;
  return toJava(env, out.data(), count < out.size() ? count : out.size());
}
JNIEXPORT jboolean FN(editorCorrect)(JNIEnv* env, jclass, jlong e, jlongArray arr, jfloat s) {
  auto v = ids(env, arr);
  return !v.empty() && ink_editor_correct(E(e), v.data(), v.size(), s);
}
JNIEXPORT jboolean FN(editorStraighten)(JNIEnv* env, jclass, jlong e, jlongArray arr, jfloat s) {
  auto v = ids(env, arr);
  return !v.empty() && ink_editor_straighten(E(e), v.data(), v.size(), s);
}
JNIEXPORT jboolean FN(editorTakeDirty)(JNIEnv* env, jclass, jlong e, jfloatArray outRect) {
  return writeRect(env, ink_editor_take_dirty(E(e)), outRect);
}
JNIEXPORT void FN(editorRenderOver)(JNIEnv* env, jclass, jlong e, jobject bitmap, jfloat scale, jfloat ox, jfloat oy) {
  LockedBitmap bm(env, bitmap);
  if (!bm) return;
  ink_editor_render_over_rgba(E(e), bm.data(), static_cast<int>(bm.info.width), static_cast<int>(bm.info.height),
                              bm.info.stride, scale, ox, oy);
}
JNIEXPORT void FN(editorSetHidden)(JNIEnv* env, jclass, jlong e, jlongArray arr) {
  auto v = ids(env, arr);
  ink_editor_set_hidden(E(e), v.empty() ? nullptr : v.data(), v.size());
}
JNIEXPORT void FN(editorRenderStrokesOver)(JNIEnv* env, jclass, jlong e, jlongArray arr, jobject bitmap,
                                           jfloat scale, jfloat ox, jfloat oy) {
  auto v = ids(env, arr);
  if (v.empty()) return;
  LockedBitmap bm(env, bitmap);
  if (!bm) return;
  ink_editor_render_strokes_over_rgba(E(e), v.data(), v.size(), bm.data(), static_cast<int>(bm.info.width),
                                      static_cast<int>(bm.info.height), bm.info.stride, scale, ox, oy);
}
JNIEXPORT jbyteArray FN(editorSave)(JNIEnv* env, jclass, jlong e) {
  size_t size = 0;
  uint8_t* data = ink_editor_save(E(e), &size);
  if (!data) return nullptr;
  jbyteArray out = toJava(env, data, size);
  ink_free(data);
  return out;
}
JNIEXPORT jboolean FN(editorLoad)(JNIEnv* env, jclass, jlong e, jbyteArray data) {
  jsize n = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(n));
  env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
  return ink_editor_load(E(e), bytes.data(), bytes.size());
}
JNIEXPORT jfloat FN(editorPageWidth)(JNIEnv*, jclass, jlong e) { return ink_editor_page_width(E(e)); }
JNIEXPORT jfloat FN(editorPageHeight)(JNIEnv*, jclass, jlong e) { return ink_editor_page_height(E(e)); }
JNIEXPORT jstring FN(editorExportSvg)(JNIEnv* env, jclass, jlong e) {
  char* svg = ink_editor_export_svg(E(e));
  if (!svg) return env->NewStringUTF("");
  jstring out = env->NewStringUTF(svg);
  ink_free(svg);
  return out;
}

}  // extern "C"
