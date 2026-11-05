package local.voxfix.debug

import android.graphics.*
import kotlin.math.*

/**
 * Arrow palette icons from Snow Shot 1.1.8
 * (`snow_shot/src/presentation/components/icons/resources/arrowhead-*.svg`,
 * `arrow-type-*.svg`, `arrow-shaft-*.svg`, `arrow-ratio.svg`; fixed commit `afdc340b`).
 *
 * VectorDrawable cannot stroke a path, so the source geometry is drawn here with the SVG's own
 * stroke width and cap/join instead of being flattened into filled outlines. Path data, view boxes,
 * per-element fill/stroke resolution and the group opacity are the upstream values verbatim; the
 * `-start` files are the same glyph under `translate(w,0) scale(-1,1)`, which [draw] applies.
 */
internal object ScreenshotArrowGlyphs {
    /** One `<path>`/`<circle>`/`<rect>` plus the paint the SVG resolves for that element. */
    class Part(val fill: Boolean, val stroke: Boolean, val strokeWidth: Float,
               val roundJoin: Boolean, val build: (Path) -> Unit)

    /** One palette option: view box, the mirror the upstream file bakes in, and its parts. */
    class Glyph(val width: Float, val height: Float, val flipForEnd: Boolean, val alpha: Float, val parts: List<Part>)

    private fun path(data: String, fill: Boolean = false, stroke: Boolean = false, strokeWidth: Float = 1f,
                     roundJoin: Boolean = false, evenOdd: Boolean = false) =
        Part(fill, stroke, strokeWidth, roundJoin) { target ->
            SvgPath.build(data, target, evenOdd)
        }

    private fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false, stroke: Boolean = false,
                       strokeWidth: Float = 1f) =
        Part(fill, stroke, strokeWidth, false) { target -> target.addCircle(cx, cy, r, Path.Direction.CW) }

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        Part(true, false, 0f, false) { target ->
            target.addRoundRect(RectF(x, y, x + w, y + h), r, r, Path.Direction.CW)
        }

    /** `<circle r="4" transform="matrix(-1 0 0 1 30 10)">`: the centre is the matrix translation. */
    private fun dot(fill: Boolean, strokeWidth: Float = 1f) =
        circle(30f, 10f, 4f, fill = fill, stroke = true, strokeWidth = strokeWidth)

    /** The 40×20 head strips; `arrowhead-none.svg` alone uses a 24×24 box. */
    private fun head(flipForEnd: Boolean, vararg parts: Part): Glyph =
        Glyph(40f, 20f, flipForEnd, 1f, parts.toList())

    private val HEADS: Map<ScreenshotArrowhead, Glyph> = linkedMapOf(
        // `arrowhead-none.svg`: 24×24 box, the group is 0.3 opacity.
        ScreenshotArrowhead.NONE to Glyph(24f, 24f, true, .3f, listOf(
            path("M12 12l9 0", stroke = true, strokeWidth = 2f),
            path("M3 9l6 6", stroke = true, strokeWidth = 2f),
            path("M3 15l6 -6", stroke = true, strokeWidth = 2f))),
        ScreenshotArrowhead.ARROW to head(false,
            path("M34 10H6M34 10L27 5M34 10L27 15", stroke = true, strokeWidth = 2f),
            path("M27.5 5L34.5 10L27.5 15", stroke = true, strokeWidth = 2f)),
        ScreenshotArrowhead.BAR to head(false,
            path("M34 10H5.99996M34 10L34 5M34 10L34 15", stroke = true, strokeWidth = 2f)),
        ScreenshotArrowhead.DOT to head(false,
            path("M32 10L6 10", fill = true, stroke = true, strokeWidth = 2f), dot(fill = true)),
        ScreenshotArrowhead.CIRCLE to head(false,
            path("M32 10L6 10", fill = true, stroke = true, strokeWidth = 2f), dot(fill = true)),
        // The outline file sets the group's stroke width to 2 and its fill to none.
        ScreenshotArrowhead.CIRCLE_OUTLINE to head(false,
            path("M26 10L6 10", stroke = true, strokeWidth = 2f), dot(fill = false, strokeWidth = 2f)),
        ScreenshotArrowhead.INDENTED_TRIANGLE to head(false,
            path("M32 10L6 10", fill = true, stroke = true, strokeWidth = 2f),
            path("M27.5 5.5L34.5 10L27.5 14.5L29.25 10Z", fill = true, stroke = true)),
        ScreenshotArrowhead.TRIANGLE to head(false,
            path("M32 10L6 10", fill = true, stroke = true, strokeWidth = 2f),
            path("M27.5 5.5L34.5 10L27.5 14.5L27.5 5.5", fill = true, stroke = true)),
        ScreenshotArrowhead.TRIANGLE_OUTLINE to head(false,
            path("M6,9.5H27", stroke = true, strokeWidth = 2f, roundJoin = true),
            path("M27,5L34,10L27,14Z", stroke = true, strokeWidth = 2f, roundJoin = true)),
        ScreenshotArrowhead.DIAMOND to head(false,
            path("M6,9.5H20", fill = true, stroke = true, strokeWidth = 2f, roundJoin = true),
            path("M27,5L34,10L27,14L20,9.5Z", fill = true, stroke = true, strokeWidth = 2f, roundJoin = true)),
        ScreenshotArrowhead.DIAMOND_OUTLINE to head(false,
            path("M6,9.5H20", stroke = true, strokeWidth = 2f, roundJoin = true),
            path("M27,5L34,10L27,14L20,9.5Z", stroke = true, strokeWidth = 2f, roundJoin = true)),
        // The crowfoot files (and `none`) author the head on the left, so their end form is mirrored.
        ScreenshotArrowhead.CROWFOOT_ONE to head(true,
            path("M34,10 H6 M15,10 L15,15 L15,5", stroke = true, strokeWidth = 2f, roundJoin = true)),
        ScreenshotArrowhead.CROWFOOT_MANY to head(true,
            path("M34,10 H6 M15,10 L7,5 M15,10 L7,15", stroke = true, strokeWidth = 2f, roundJoin = true)),
        ScreenshotArrowhead.CROWFOOT_ONE_OR_MANY to head(true,
            path("M34,10 H6 M15,10 L15,16 L15,4 M15,10 L7,5 M15,10 L7,15", stroke = true, strokeWidth = 2f, roundJoin = true)))

    // `Square` has no palette icon upstream and the palette never offers it; the engine paints it
    // with the diamond branch (`get_arrowhead_points`), so a square document reuses that icon.
    private val HEAD_ALIASES: Map<ScreenshotArrowhead, Glyph> =
        mapOf(ScreenshotArrowhead.SQUARE to HEADS.getValue(ScreenshotArrowhead.DIAMOND))

    /** `arrow-type-*.svg`: 1024 box, stroke 80.64, round cap and join. */
    private val TYPES: Map<ScreenshotArrowType, Glyph> = mapOf(
        ScreenshotArrowType.STRAIGHT to Glyph(1024f, 1024f, false, 1f, listOf(
            path("M207.36 816.64L816.64 207.36", stroke = true, strokeWidth = 80.64f, roundJoin = true),
            path("M512 207.36H816.64V512", stroke = true, strokeWidth = 80.64f, roundJoin = true))),
        ScreenshotArrowType.CURVE to Glyph(1024f, 1024f, false, 1f, listOf(
            path("M171.52 834.56A501.76 501.76 0 0 1 673.28 332.8H888.32", stroke = true, strokeWidth = 80.64f, roundJoin = true),
            path("M673.28 117.76L888.32 332.8L673.28 547.84", stroke = true, strokeWidth = 80.64f, roundJoin = true))),
        ScreenshotArrowType.ELBOW to Glyph(1024f, 1024f, false, 1f, listOf(
            path("M135.68 798.72H395.52A80.64 80.64 0 0 0 476.16 718.08V413.44A80.64 80.64 0 0 1 556.8 332.8H888.32",
                stroke = true, strokeWidth = 80.64f, roundJoin = true),
            path("M673.28 117.76L888.32 332.8L673.28 547.84", stroke = true, strokeWidth = 80.64f, roundJoin = true))))

    /** `arrow-shaft-*.svg`: a filled bar and a stroked wedge. */
    private val SHAFTS: Map<ScreenshotArrowShaftType, Glyph> = mapOf(
        ScreenshotArrowShaftType.PLAIN to Glyph(1024f, 1024f, false, 1f,
            listOf(rect(64f, 471.68f, 896f, 80.64f, 8.96f))),
        ScreenshotArrowShaftType.TAPERED to Glyph(1024f, 1024f, false, 1f,
            listOf(path("M104.32 512L919.68 359.68V664.32Z", stroke = true, strokeWidth = 80.64f, roundJoin = true))))

    /** `arrow-ratio.svg`: an even-odd frame with the bars and dots of the readout. */
    val RATIO: Glyph = Glyph(1024f, 1024f, false, 1f, listOf(
        path("M90.88 81.92H933.12A80.64 80.64 0 0 1 1013.76 162.56V861.44A80.64 80.64 0 0 1 933.12 942.08" +
            "H90.88A80.64 80.64 0 0 1 10.24 861.44V162.56A80.64 80.64 0 0 1 90.88 81.92ZM99.84 162.56" +
            "A8.96 8.96 0 0 0 90.88 171.52V852.48A8.96 8.96 0 0 0 99.84 861.44H924.16" +
            "A8.96 8.96 0 0 0 933.12 852.48V171.52A8.96 8.96 0 0 0 924.16 162.56Z",
            fill = true, evenOdd = true),
        rect(256.64f, 332.8f, 80.64f, 358.4f, 8.96f),
        circle(512f, 404.48f, 40.32f, fill = true),
        circle(512f, 619.52f, 40.32f, fill = true),
        rect(686.72f, 332.8f, 80.64f, 358.4f, 8.96f)))

    /**
     * The 14 options the upstream palette offers, in the order of its `arrowheads` vector
     * (`screenshottoolpalettestylecontrols.cpp`). `SQUARE` is deliberately absent: upstream has the
     * enum value but neither an icon nor a palette entry, so it stays decodable only.
     */
    val PANEL_HEADS: List<ScreenshotArrowhead> = listOf(
        ScreenshotArrowhead.NONE, ScreenshotArrowhead.ARROW, ScreenshotArrowhead.BAR, ScreenshotArrowhead.DOT,
        ScreenshotArrowhead.CIRCLE, ScreenshotArrowhead.CIRCLE_OUTLINE, ScreenshotArrowhead.INDENTED_TRIANGLE,
        ScreenshotArrowhead.TRIANGLE, ScreenshotArrowhead.TRIANGLE_OUTLINE, ScreenshotArrowhead.DIAMOND,
        ScreenshotArrowhead.DIAMOND_OUTLINE, ScreenshotArrowhead.CROWFOOT_ONE, ScreenshotArrowhead.CROWFOOT_MANY,
        ScreenshotArrowhead.CROWFOOT_ONE_OR_MANY)

    fun head(value: ScreenshotArrowhead): Glyph = HEADS[value] ?: HEAD_ALIASES.getValue(value)
    fun type(value: ScreenshotArrowType): Glyph = TYPES.getValue(value)
    fun shaft(value: ScreenshotArrowShaftType): Glyph = SHAFTS.getValue(value)

    /** Fit the view box inside [box], keeping the upstream aspect, then apply the start mirror. */
    fun draw(canvas: Canvas, glyph: Glyph, box: RectF, color: Int, start: Boolean, paint: Paint) {
        val scale = min(box.width() / glyph.width, box.height() / glyph.height)
        canvas.save()
        canvas.translate(box.left + (box.width() - glyph.width * scale) / 2f,
            box.top + (box.height() - glyph.height * scale) / 2f)
        canvas.scale(scale, scale)
        if (start != glyph.flipForEnd) { canvas.translate(glyph.width, 0f); canvas.scale(-1f, 1f) }
        paint.reset(); paint.isAntiAlias = true; paint.color = color
        paint.alpha = (Color.alpha(color) * glyph.alpha).toInt().coerceIn(0, 255)
        for (part in glyph.parts) {
            val target = Path(); part.build(target)
            if (part.fill) { paint.style = Paint.Style.FILL; canvas.drawPath(target, paint) }
            if (part.stroke) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = part.strokeWidth
                paint.strokeCap = Paint.Cap.BUTT
                paint.strokeJoin = if (part.roundJoin) Paint.Join.ROUND else Paint.Join.MITER
                canvas.drawPath(target, paint)
            }
        }
        canvas.restore()
    }

    /** The SVG path grammar these icons use: M/L/H/V/Z (absolute and relative) plus elliptical arcs. */
    private object SvgPath {
        fun build(data: String, path: Path, evenOdd: Boolean = false) {
            if (evenOdd) path.fillType = Path.FillType.EVEN_ODD
            val length = data.length
            var index = 0
            var command = ' '
            var x = 0f; var y = 0f; var startX = 0f; var startY = 0f
            fun skip() {
                while (index < length && (data[index] == ' ' || data[index] == ',' ||
                        data[index] == '\n' || data[index] == '\r' || data[index] == '\t')) index++
            }
            fun number(): Float {
                skip()
                val from = index
                if (index < length && (data[index] == '+' || data[index] == '-')) index++
                while (index < length && data[index].isDigit()) index++
                if (index < length && data[index] == '.') {
                    index++
                    while (index < length && data[index].isDigit()) index++
                }
                if (index < length && (data[index] == 'e' || data[index] == 'E')) {
                    index++
                    if (index < length && (data[index] == '+' || data[index] == '-')) index++
                    while (index < length && data[index].isDigit()) index++
                }
                return data.substring(from, index).toFloat()
            }
            while (true) {
                skip()
                if (index >= length) break
                if (data[index].isLetter()) { command = data[index]; index++ }
                else if (command == 'M') command = 'L'
                else if (command == 'm') command = 'l'
                when (command) {
                    'M' -> { x = number(); y = number(); path.moveTo(x, y); startX = x; startY = y }
                    'm' -> { x += number(); y += number(); path.moveTo(x, y); startX = x; startY = y }
                    'L' -> { x = number(); y = number(); path.lineTo(x, y) }
                    'l' -> { x += number(); y += number(); path.lineTo(x, y) }
                    'H' -> { x = number(); path.lineTo(x, y) }
                    'h' -> { x += number(); path.lineTo(x, y) }
                    'V' -> { y = number(); path.lineTo(x, y) }
                    'v' -> { y += number(); path.lineTo(x, y) }
                    'Z', 'z' -> { path.close(); x = startX; y = startY }
                    'A', 'a' -> {
                        val rx = number(); val ry = number(); val rotation = number()
                        val large = number() != 0f; val sweep = number() != 0f
                        val toX = if (command == 'a') x + number() else number()
                        val toY = if (command == 'a') y + number() else number()
                        arcTo(path, x, y, rx, ry, rotation, large, sweep, toX, toY)
                        x = toX; y = toY
                    }
                    else -> index++
                }
            }
        }

        /** SVG F.6.5 endpoint → centre parameterisation, then `arcTo` on the matching oval. */
        private fun arcTo(path: Path, fromX: Float, fromY: Float, radiusX: Float, radiusY: Float,
                          rotation: Float, large: Boolean, sweep: Boolean, toX: Float, toY: Float) {
            var rx = abs(radiusX); var ry = abs(radiusY)
            if (rx == 0f || ry == 0f) { path.lineTo(toX, toY); return }
            val phi = Math.toRadians(rotation.toDouble()).toFloat()
            val cosPhi = cos(phi); val sinPhi = sin(phi)
            val dx = (fromX - toX) / 2f; val dy = (fromY - toY) / 2f
            val x1 = cosPhi * dx + sinPhi * dy
            val y1 = -sinPhi * dx + cosPhi * dy
            val lambda = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry)
            if (lambda > 1f) { val grow = sqrt(lambda); rx *= grow; ry *= grow }
            var numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
            if (numerator < 0f) numerator = 0f
            val denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1
            if (denominator <= 0f) { path.lineTo(toX, toY); return }
            val sign = if (large != sweep) 1f else -1f
            val coefficient = sign * sqrt(numerator / denominator)
            val centreX = cosPhi * coefficient * (rx * y1 / ry) - sinPhi * coefficient * (-ry * x1 / rx) + (fromX + toX) / 2f
            val centreY = sinPhi * coefficient * (rx * y1 / ry) + cosPhi * coefficient * (-ry * x1 / rx) + (fromY + toY) / 2f
            fun angle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
                val cosine = (ux * vx + uy * vy) / max(hypot(ux, uy) * hypot(vx, vy), 1e-9f)
                val value = acos(cosine.coerceIn(-1f, 1f))
                return if (ux * vy - uy * vx < 0f) -value else value
            }
            val ux = (x1 - coefficient * (rx * y1 / ry)) / rx
            val uy = (y1 - coefficient * (-ry * x1 / rx)) / ry
            val vx = (-x1 - coefficient * (rx * y1 / ry)) / rx
            val vy = (-y1 - coefficient * (-ry * x1 / rx)) / ry
            val startAngle = Math.toDegrees(angle(1f, 0f, ux, uy).toDouble()).toFloat()
            var sweepAngle = Math.toDegrees(angle(ux, uy, vx, vy).toDouble()).toFloat()
            if (sweep && sweepAngle < 0f) sweepAngle += 360f
            if (!sweep && sweepAngle > 0f) sweepAngle -= 360f
            val oval = RectF(centreX - rx, centreY - ry, centreX + rx, centreY + ry)
            if (rotation == 0f) path.arcTo(oval, startAngle, sweepAngle)
            else {
                val arc = Path()
                arc.arcTo(oval, startAngle, sweepAngle)
                val matrix = Matrix().apply { setRotate(rotation, centreX, centreY) }
                arc.transform(matrix)
                path.addPath(arc)
            }
        }
    }
}
