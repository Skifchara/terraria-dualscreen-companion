package com.terraduo.companion;

import android.view.ScaleGestureDetector;
import java.util.function.Predicate;

/** Forwards pinch scale factors to a callback that reports whether it consumed them. */
final class PinchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
    private final Predicate<Float> callback;

    PinchListener(Predicate<Float> callback) { this.callback = callback; }

    @Override public boolean onScale(ScaleGestureDetector detector) {
        return callback.test(detector.getScaleFactor());
    }
}
