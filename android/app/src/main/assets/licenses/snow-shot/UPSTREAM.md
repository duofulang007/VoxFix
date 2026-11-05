# Snow Shot screenshot icons

Source: mg-chao/snow-apps, Snow Shot 1.1.8, commit
afdc340b14e7f92a294a95c374d13ada0d8c7a82.

The original SVG files are retained in icons/. Android drawable resources
snow_*.xml preserve their path geometry, converted to VectorDrawable; color is
controlled by the Android toolbar. Icons are not redrawn approximations.

selection-radius.svg is retained from
snow_shot/src/presentation/components/icons/resources/selection-radius.svg at the
same fixed commit. snow_selection_radius.xml converts its path and rounded rects
without changing their geometry; GPL-3.0-or-later, see GPL-3.0.txt and COPYRIGHT.
The corner controls, hatch tiles, serial geometry and connectors reference the
same upstream's screenshottoolpalettebuttons.cpp, screenshottoolpalettestylemodel.cpp,
snow_canvas_fill_render.cpp, snow_canvas_renderer.cpp and document_geometry.rs.

- recognize.svg, translate.svg, save.svg: snow_shot/src/presentation/components/icons/resources/
  (upstream names text-recognition.svg, ocr-translate.svg, save.svg). GPL-3.0-or-later;
  see GPL-3.0.txt and COPYRIGHT. The translation symbol's MIT notice also remains
  inside translate.svg. This does not relicense the whole custom icon as MIT.
- copy.svg, eye.svg, edit.svg, reload.svg, setting.svg, close.svg, arrow-up.svg,
  arrow-down.svg, vertical-align-top.svg, vertical-align-bottom.svg:
  ant_design_qt/packages/ant_design_icons_qt/resources/icons/outlined/.
  Ant Design Icons MIT notice is retained in ANT-THIRD-PARTY-NOTICES.md.
- align-left.svg, align-center-horizontal.svg, align-right.svg, align-top.svg,
  align-center-vertical.svg, align-bottom.svg, distribute-horizontal.svg,
  distribute-vertical.svg, duplicate.svg: snow_shot/src/presentation/components/icons/resources/.
  GPL-3.0-or-later; see GPL-3.0.txt and COPYRIGHT.

https://github.com/mg-chao/snow-apps/tree/afdc340b14e7f92a294a95c374d13ada0d8c7a82

T22 object-selection ports reference the same fixed commit's
snow_draw_engine_qt/crates/snow-draw-engine-editor/src/selection.rs,
edit_workflow/selection_edit.rs, edit_workflow/selection_feedback.rs,
input/pointer_idle.rs, input/pointer_selection.rs, geometry/selection_bounds.rs,
geometry/primitives.rs, geometry/handles.rs and arrow_ops.rs, plus
snow-draw-engine-scene/src/selection_visuals.rs (Apache-2.0; verified against each crate LICENSE).
The Android selection transaction, rotation and SAT marquee intersection retain
that source's algorithms; incomplete upstream workflows are tracked in the T22 record.
Pen endpoint continuation also ports editor/src/free_draw_workflow.rs::continue_from,
resolve_free_draw_endpoint and FreeDrawContinuation::sample at the same fixed commit.

T22 Mixed / resize / linear-handle ports also reference editor/src/style.rs,
geometry/resize.rs, text/resize.rs, document/src/{document,document_geometry,free_draw}.rs,
document/src/arrow/data.rs and scene/src/{item_conversions,overlay_composition}.rs
at the fixed commit above. Editor, document and scene crate LICENSE files each
confirm Apache-2.0; the unchanged license text is retained in Apache-2.0.txt.
Android implementation and file/renderer adaptations are modified ports, not
unmodified upstream files. The Qt palette files and artwork under snow_shot/
remain covered by their existing GPL-3.0-or-later notices.

T22 elbow segment and unbound endpoint routing ports reference the same commit's
document/src/arrow/{elbow_core,elbow_router_search,elbow_update,elbow_update_endpoints,elbow_update_segments,engine}.rs
and arrow geometry helpers. These modified Kotlin ports use the document crate's Apache-2.0 license.

Selection layer order, alignment, distribution and duplication follow the editor
crate's Apache-2.0 document_ops.rs. Selection actions follow the palette's
createSelectionActionFamily and the 12 × 12 offset declared in snow_canvas_widget.h.

T11 arrow palette icons: icons/arrowhead-*.svg (14 head options, each with a -start
mirror), icons/arrow-type-{straight,curved,elbow}.svg, icons/arrow-shaft-{plain,tapered}.svg
and icons/arrow-ratio.svg come from snow_shot/src/presentation/components/icons/resources/
at the fixed commit. VectorDrawable has no stroke, and these files are stroke-first, so
ScreenshotArrowGlyphs.kt keeps the upstream path data, view boxes, per-element stroke
width, cap/join and the 0.3 group opacity, and draws them with Canvas instead of
flattening them into filled outlines. The -start files differ from their base file only
by the wrapper transform translate(40,0) scale(-1,1) (the crowfoot set and none are
authored head-left, so only their end form flips). arrowhead-square.svg and
arrowhead-inverted-triangle.svg do not exist upstream; the palette offers 14 heads.

delete.svg (selection action row) is ant_design_qt/packages/ant_design_icons_qt/resources/
icons/outlined/delete.svg at the same fixed commit; snow_delete.xml keeps its path geometry
and the Ant Design Icons MIT notice stays in ANT-THIRD-PARTY-NOTICES.md.
