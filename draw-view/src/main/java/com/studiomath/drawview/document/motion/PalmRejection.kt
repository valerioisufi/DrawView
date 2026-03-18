package com.studiomath.drawview.document.motion

import android.view.MotionEvent

class PalmRejection {
    var pointersIdRejected = mutableSetOf<Int>()

    fun record(event: MotionEvent) {
        for (pointerIndex in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(pointerIndex)

            if (event.getToolMinor(pointerIndex) / event.getToolMajor(pointerIndex) < 0.5) {
                pointersIdRejected.add(pointerId)
            }
        }
//        val pointerIndex = event.actionIndex

    }
}