package io.karpfen.ide.ui.editor.view.input

/**
 * The drag auto-scroll: dragging a selection beyond the text area scrolls it by one [step] every [INTERVAL_MILLIS]
 * while the pointer stays outside.
 */
internal object AutoScroll {

    /** Milliseconds between two auto-scroll steps while the pointer is outside. */
    const val INTERVAL_MILLIS: Long = 30

    /**
     * The scroll step for a pointer at ([x], [y]): the distance by which the pointer is outside the area of [width] x
     * [height] on each axis, at most [maxStep] pixels, with the sign of the side it left on; (0, 0) while it is inside.
     */
    fun step(x: Float, y: Float, width: Float, height: Float, maxStep: Float): Pair<Float, Float> =
        axisStep(x, width, maxStep) to axisStep(y, height, maxStep)

    /** The step on one axis: how far [position] lies outside `0..size`, signed and at most [maxStep]. */
    private fun axisStep(position: Float, size: Float, maxStep: Float): Float = when {
        position < 0f -> maxOf(position, -maxStep)
        position > size -> minOf(position - size, maxStep)
        else -> 0f
    }
}
