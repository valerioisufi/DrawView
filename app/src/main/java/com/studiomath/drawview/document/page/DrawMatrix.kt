package com.studiomath.drawview.document.page

import android.graphics.Matrix
import android.graphics.RectF
import android.util.DisplayMetrics

class DrawMatrix(
    var displayMetrics: DisplayMetrics
) {
    var dx: Float = 0f
    var dy: Float = 0f
    var sx: Float = 1f
    var sy: Float = 1f

    private var matrixFloatArray: FloatArray = FloatArray(9)
    fun getMatrix(): Matrix {
        matrixFloatArray[Matrix.MTRANS_X] = dx
        matrixFloatArray[Matrix.MTRANS_Y] = dy
        matrixFloatArray[Matrix.MSCALE_X] = sx
        matrixFloatArray[Matrix.MSCALE_Y] = sy
        matrixFloatArray[Matrix.MPERSP_2] = 1f
        return Matrix().apply { setValues(matrixFloatArray) }
    }

    /** Set the matrix to translate by (dx, dy). */
    fun setTranslate(dx: Float, dy: Float) {
        this.dx = dx
        this.dy = dy
    }
    /**
     * Set the matrix to scale by sx and sy, with a pivot point at (px, py). The pivot point is the
     * coordinate that should remain unchanged by the specified transformation.
     */
    fun setScale(sx: Float, sy: Float, px: Float, py: Float) {
        this.sx = sx
        this.sy = sy
        this.dx = px * (1 - sx)
        this.dy = py * (1 - sy)
    }

    /**
     * Postconcats the matrix with the specified translation. M' = T(dx, dy) * M
     */
    fun postTranslate(dx: Float, dy: Float) {
        this.dx += dx
        this.dy += dy

    }

    /**
     * Postconcats the matrix with the specified scale. M' = S(sx, sy, px, py) * M
     */
    fun postScale(sx: Float, sy: Float, px: Float, py: Float) {
        this.sx *= sx
        this.sy *= sy
        this.dx = px * (1 - sx) + this.dx * sx
        this.dy = py * (1 - sy) + this.dy * sy

    }

    // TODO: in base alle dimensioni della prima pagina (pageWidth/windowWidth corrisponde a scaleFactor di 1)
    val scaleMin = 0.5f
    val scaleMax = 5f

    /**
     * questa funzione deve ritornare contentMatrixWithConstraints
     */
    data class Constraints(
        val contentMatrixWithConstraints: Matrix = Matrix()
    ){
        var scaleMinOffsetBounce: Float = 0f
        var scaleMaxOffsetBounce: Float = 0f

        var leftOffsetBounce: Float = 0f
        var rightOffsetBounce: Float = 0f
        var topOffsetBounce: Float = 0f
        var bottomOffsetBounce: Float = 0f

    }
    fun constrainContentRectToContentConstraints(contentConstraints: RectF, contentRect: RectF): Constraints {
        val matrixWithNoConstraints = getMatrix()
        val contentRectTransformed = RectF()
        matrixWithNoConstraints.mapRect(contentRectTransformed, contentRect)

        val constraints = Constraints().apply {
            val contentRectTransformedWithConstraints = RectF().apply {

                // devo preservare le dimensioni di contentRectTransformed, e quindi l'aspect ratio

//                if (contentRect.width() < contentConstraints.width()){
//                    // se il contenuto da visualizzare è più piccolo dell'area di visualizzazione
//                    // lo disegno (orizzontalmente) centrato all'interno dell'area di visualizzazione
//                    left = contentConstraints.centerX() - contentRectTransformed.width() / 2f
//                    right = left + contentRectTransformed.width()
//
//                } else {
                    left = if (contentRectTransformed.left > contentConstraints.left) contentConstraints.left else contentRectTransformed.left
                    right = if (contentRectTransformed.right < contentConstraints.right) contentConstraints.right else contentRectTransformed.right
//                }

                top = if (contentRectTransformed.top > contentConstraints.top) contentConstraints.top else contentRectTransformed.top
                bottom = if (contentRectTransformed.bottom < contentConstraints.bottom) contentConstraints.bottom else contentRectTransformed.bottom

            }

            contentMatrixWithConstraints.setRectToRect(
                contentRect,
                contentRectTransformedWithConstraints,
                Matrix.ScaleToFit.START
            )

        }

        return constraints
    }

    fun getMatrixWithConstrains(contentConstraints: RectF, contentRect: RectF): Matrix {
        val constraints = constrainContentRectToContentConstraints(contentConstraints, contentRect)
        return constraints.contentMatrixWithConstraints
    }
}