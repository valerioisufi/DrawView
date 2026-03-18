package com.studiomath.drawview.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView

@SuppressLint("ViewConstructor")
class DrawView(context: Context, val drawViewModel: DrawViewModel) : SurfaceView(context), SurfaceHolder.Callback {

    init {
        // Indispensabile per ricevere gli eventi created/changed/destroyed
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // La superficie è pronta. Avviamo il motore grafico!
        drawViewModel.drawManager.startRenderLoop(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Chiamato subito dopo surfaceCreated e ad ogni rotazione/ridimensionamento
        drawViewModel.drawManager.onSizeChanged(width, height, width, height) // Semplificato oldWidth/oldHeight
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // La view sta per essere distrutta (es. l'utente esce dall'app o ruota lo schermo).
        // Dobbiamo FERMARE il thread per evitare memory leak o crash.
        drawViewModel.drawManager.stopRenderLoop()
    }
}