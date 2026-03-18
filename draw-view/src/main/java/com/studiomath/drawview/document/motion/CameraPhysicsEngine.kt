package com.studiomath.drawview.document.motion

import android.graphics.Matrix
import android.graphics.RectF
import android.util.DisplayMetrics
import kotlin.math.abs
import kotlin.math.sign

/**
 * Motore fisico unificato per la gestione della telecamera del documento.
 * Sostituisce OverScroller e ValueAnimator utilizzando una fisica a molla (Spring Physics)
 * indipendente dal framerate e calcolata separatamente per Asse X, Asse Y e Zoom.
 *
 * @property displayMetrics Metriche del display per eventuali conversioni dp/px.
 * @property getContentRect Funzione lambda che restituisce l'ingombro matematico totale delle pagine (senza zoom).
 */
class CameraPhysicsEngine(
    private val displayMetrics: DisplayMetrics,
    private val getContentRect: () -> RectF
) {
    // --- COSTANTI E CONFIGURAZIONI FISICHE ---
    var friction: Float = 3.5f // Attrito per il fling (decelerazione)
    var springStiffness: Float = 250f // Durezza della molla (ritorno elastico)
    var springDamping: Float = 25f // Smorzamento della molla (evita oscillazioni infinite)
    var rubberBandTension: Float = 0.55f // Tensione visiva quando l'utente tira fuori dai bordi

    var minScale: Float = 0.5f
    var maxScale: Float = 5.0f

    // Margini desiderati (in pixel) attorno al documento
    var horizontalPaddingPx: Float = 40f
    var topPaddingPx: Float = 40f
    var bottomPaddingPx: Float = 40f

    // --- ASSI FISICI INDIPENDENTI ---
    private val axisX = AxisPhysics1D()
    private val axisY = AxisPhysics1D()
    private val scaleAxis = ScalePhysics1D()

    // Stato globale
    private var isUserDragging = false
    private var viewportRect = RectF()
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    init {
        // Inizializza la scala a 1.0 al lancio
        scaleAxis.position = 1f
    }

    /**
     * Aggiorna la dimensione della finestra visibile. Da chiamare in onSizeChanged.
     */
    fun setViewport(width: Int, height: Int) {
        viewportRect.set(0f, 0f, width.toFloat(), height.toFloat())
        updateDynamicBoundaries()
    }

    /**
     * Ritorna TRUE se c'è un'animazione inerziale o un rimbalzo in corso.
     * Usalo per decidere se continuare a chiamare invalidate() nel DrawManager.
     */
    fun isAnimating(): Boolean {
        return !isUserDragging && (
                axisX.state != PhysicsState.IDLE ||
                        axisY.state != PhysicsState.IDLE ||
                        scaleAxis.state != PhysicsState.IDLE
                )
    }

    // =========================================================================
    // FASE 1: GESTIONE INPUT (Chiamati dal View / GestureDetector)
    // =========================================================================

    /**
     * L'utente ha toccato lo schermo. Ferma immediatamente qualsiasi inerzia o rimbalzo.
     */
    fun onDragStart() {
        isUserDragging = true
        stopAllAnimations()
    }

    /**
     * Muove la telecamera o cambia lo zoom in base all'input dell'utente.
     * La resistenza elastica (Rubber-band) verrà calcolata visivamente in getRenderMatrix.
     */
    fun onDrag(dx: Float, dy: Float, scaleFactor: Float, focusX: Float, focusY: Float) {
        lastFocusX = focusX
        lastFocusY = focusY

        // 1. Applica lo Zoom basato sul punto focale (le dita dell'utente)
        if (scaleFactor != 1f) {
            val oldScale = scaleAxis.position

            // MOLTIPLICA E BLOCCA: Impediamo alla scala di uscire dai limiti min e max
            scaleAxis.position = (oldScale * scaleFactor).coerceIn(minScale, maxScale)

            val currentScale = scaleAxis.position
            val scaleRatio = currentScale / oldScale

            // Se la scala era già al limite, scaleRatio sarà 1.0 e X/Y non verranno alterati per errore
            axisX.position = focusX - (focusX - axisX.position) * scaleRatio
            axisY.position = focusY - (focusY - axisY.position) * scaleRatio
        }

        // 2. Applica il Pan (spostamento puro)
        axisX.position += dx
        axisY.position += dy

        updateDynamicBoundaries()
    }

    /**
     * L'utente ha rilasciato lo schermo. Trasferiamo la velocità al motore per il Fling.
     * @param velocityX Velocità in pixel/secondo (da VelocityTracker)
     */
    fun onRelease(velocityX: Float, velocityY: Float) {
        isUserDragging = false
        updateDynamicBoundaries()

        // Asse X: Decidi se fare Fling o Rimbalzare subito
        if (axisX.calculateExcess() != 0f) {
            axisX.startBounce()
        } else {
            axisX.startFling(velocityX)
        }

        // Asse Y: Decidi se fare Fling o Rimbalzare subito
        if (axisY.calculateExcess() != 0f) {
            axisY.startBounce()
        } else {
            axisY.startFling(velocityY)
        }

    }

    // =========================================================================
    // FASE 2: CONTROLLO IMPERATIVO
    // =========================================================================

    /**
     * Ferma tutto istantaneamente. Se la pagina è fuori dai bordi, rimane "congelata" lì.
     */
    fun stopAllAnimations() {
        axisX.stop()
        axisY.stop()
        scaleAxis.stop()
    }

    /**
     * Forza il rientro del documento nei limiti.
     * Utile se si cambia pagina o si chiudono menu e si vuole ripristinare la vista.
     * @param animated Se true, usa la molla. Se false, fa uno snap istantaneo.
     */
    fun restoreToBounds(animated: Boolean = true) {
        updateDynamicBoundaries()

        if (animated) {
            if (axisX.calculateExcess() != 0f) axisX.startBounce()
            if (axisY.calculateExcess() != 0f) axisY.startBounce()
            if (scaleAxis.calculateExcess() != 0f) scaleAxis.startBounce()
        } else {
            // Snap istantaneo
            if (axisX.calculateExcess() != 0f) axisX.position -= axisX.calculateExcess()
            if (axisY.calculateExcess() != 0f) axisY.position -= axisY.calculateExcess()
            if (scaleAxis.calculateExcess() != 0f) scaleAxis.position -= scaleAxis.calculateExcess()
            stopAllAnimations()
        }
    }

    // =========================================================================
    // FASE 3: MOTORE FISICO E RENDER
    // =========================================================================

    /**
     * Il loop di aggiornamento fisico. Da chiamare ad ogni frame (es. in onDraw).
     * @param deltaTimeMillis Millisecondi trascorsi dal frame precedente.
     */
    fun update(deltaTimeMillis: Long) {
        if (isUserDragging || deltaTimeMillis <= 0) return

        val dt = deltaTimeMillis / 1000f // Converti in secondi per le formule fisiche
        updateDynamicBoundaries()

        // 1. Aggiorna lo Zoom
        scaleAxis.update(dt)

        // 2. Aggiorna X e Y
        axisX.update(dt)
        axisY.update(dt)
    }

    /**
     * Genera la matrice pronta per essere applicata al Canvas.
     * Calcola istantaneamente l'effetto elastico visivo se la telecamera è fuori dai bordi.
     */
    fun getRenderMatrix(): Matrix {
        val matrix = Matrix()

        // Usiamo direttamente la scala reale, dato che non c'è più overzoom
        val renderScale = scaleAxis.position

        // X e Y mantengono il loro elastico se trascinati fuori dai bordi
        val renderX = axisX.getRubberBandPosition(viewportRect.width(), rubberBandTension)
        val renderY = axisY.getRubberBandPosition(viewportRect.height(), rubberBandTension)

        // Applica le trasformazioni in ordine standard: prima Scala, poi Traslazione
        matrix.postScale(renderScale, renderScale)
        matrix.postTranslate(renderX, renderY)

        return matrix
    }

    // =========================================================================
    // LOGICA DI CALCOLO LIMITI (CORE ARCHITECTURE)
    // =========================================================================

    /**
     * Ricalcola dinamicamente i limiti di scorrimento basandosi sullo zoom attuale
     * e sulla dimensione della finestra. Gestisce il centraggio automatico.
     */
    private fun updateDynamicBoundaries() {
        if (viewportRect.isEmpty) return
        val content = getContentRect()
        if (content.isEmpty) return

        val currentScale = scaleAxis.position
        val scaledWidth = content.width() * currentScale
        val scaledHeight = content.height() * currentScale

        // --- ASSE X: Centratura o Scorrimento ---
        if (scaledWidth <= viewportRect.width() - (horizontalPaddingPx * 2)) {
            // Contenuto più stretto: Blocchiamo min e max al centro esatto
            val centeredX = (viewportRect.width() - scaledWidth) / 2f
            axisX.minValue = centeredX
            axisX.maxValue = centeredX
        } else {
            // Scorrimento normale.
            // Il massimo in cui possiamo spostare il documento a destra è horizontalPaddingPx
            // Il massimo a sinistra è quando il bordo destro tocca la finestra.
            axisX.minValue = viewportRect.width() - scaledWidth - horizontalPaddingPx
            axisX.maxValue = horizontalPaddingPx
        }

        // --- ASSE Y: Allineamento in alto o Scorrimento ---
        if (scaledHeight <= viewportRect.height() - (topPaddingPx + bottomPaddingPx)) {
            // Contenuto più corto: Blocchiamo min e max al top padding (non al centro)
            axisY.minValue = topPaddingPx
            axisY.maxValue = topPaddingPx
        } else {
            // Scorrimento normale verticale
            axisY.minValue = viewportRect.height() - scaledHeight - bottomPaddingPx
            axisY.maxValue = topPaddingPx
        }
    }

    // =========================================================================
    // CLASSI FISICHE INTERNE
    // =========================================================================

    enum class PhysicsState { IDLE, FLINGING, BOUNCING }

    /**
     * Gestisce la fisica di un singolo asse lineare (X o Y).
     */
    private inner class AxisPhysics1D {
        var state = PhysicsState.IDLE
        var position: Float = 0f
        var velocity: Float = 0f
        var minValue: Float = 0f
        var maxValue: Float = 0f

        fun startFling(v: Float) {
            velocity = v
            state = PhysicsState.FLINGING
        }

        fun startBounce() {
            state = PhysicsState.BOUNCING
        }

        fun stop() {
            state = PhysicsState.IDLE
            velocity = 0f
        }

        fun calculateExcess(): Float {
            return when {
                position < minValue -> position - minValue
                position > maxValue -> position - maxValue
                else -> 0f
            }
        }

        fun update(dt: Float) {
            if (state == PhysicsState.IDLE) return
            val excess = calculateExcess()

            when (state) {
                PhysicsState.FLINGING -> {
                    // Decelerazione per attrito
                    velocity -= velocity * friction * dt
                    position += velocity * dt

                    // Se usciamo dal bordo DURANTE un fling, passiamo al rimbalzo conservando l'energia!
                    if (calculateExcess() != 0f) {
                        state = PhysicsState.BOUNCING
                    }

                    // Se siamo fermi e dentro i bordi, fermati del tutto
                    if (abs(velocity) < 10f && calculateExcess() == 0f) {
                        stop()
                    }
                }
                PhysicsState.BOUNCING -> {
                    // Hooke's Law: F = -kX - cV
                    val springForce = (-springStiffness * excess) - (springDamping * velocity)
                    velocity += springForce * dt
                    position += velocity * dt

                    // Condizione di aggancio al bordo (fermo e vicino al target)
                    if (abs(excess) < 0.5f && abs(velocity) < 10f) {
                        position -= calculateExcess() // Snap esatto al bordo
                        stop()
                    }
                }
                else -> {}
            }
        }

        /** Calcola la deformazione visiva (elastico) se si è fuori dai bordi */
        fun getRubberBandPosition(dimension: Float, tension: Float): Float {
            val excess = calculateExcess()
            if (excess == 0f) return position

            val validPosition = position - excess
            val absExcess = abs(excess)
            // Equazione asintotica
            val rubberBandedExcess = (absExcess * dimension * tension) / (dimension + tension * absExcess)

            return validPosition + (rubberBandedExcess * sign(excess))
        }
    }

    /**
     * Gestisce la fisica specifica dello Zoom (Scala).
     */
    private inner class ScalePhysics1D {
        var state = PhysicsState.IDLE
        var position: Float = 1f
        var velocity: Float = 0f

        fun startBounce() {
            state = PhysicsState.BOUNCING
        }

        fun stop() {
            state = PhysicsState.IDLE
            velocity = 0f
        }

        fun calculateExcess(): Float {
            return when {
                position < minScale -> position - minScale
                position > maxScale -> position - maxScale
                else -> 0f
            }
        }

        fun update(dt: Float) {
            if (state != PhysicsState.BOUNCING) return
            val excess = calculateExcess()

            // Usiamo una molla leggermente più rigida per lo zoom
            val springForce = (-springStiffness * 1.5f * excess) - (springDamping * velocity)
            velocity += springForce * dt
            position += velocity * dt

            if (abs(excess) < 0.005f && abs(velocity) < 0.05f) {
                position -= calculateExcess()
                stop()
            }
        }

        /** Lo zoom ha una resistenza visiva diversa, calcolata in proporzione */
        fun getRubberBandScale(): Float {
            val excess = calculateExcess()
            if (excess == 0f) return position

            val validScale = position - excess
            val tension = 0.3f // Tensione più rigida per lo zoom
            val absExcess = abs(excess)

            // L'elastico dello zoom ha come "dimensione" di riferimento il range valido stesso
            val range = maxScale - minScale
            val rubberBandedExcess = (absExcess * range * tension) / (range + tension * absExcess)

            return validScale + (rubberBandedExcess * sign(excess))
        }
    }
}