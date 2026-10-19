package dev.dsh.mobile.ui

/**
 * Whether the app is on screen.
 *
 * The watch service runs while the app is not, and the one notification it should
 * not post is the one about a turn the reader is already watching end. Nothing
 * else in the app reads this: the polling loops have their own visibility flag,
 * because they must stop for a different reason (cost) and at a different moment
 * (the activity's own lifecycle).
 */
object AppVisibility {
    /** True while an activity of this app is started. */
    @Volatile
    var visible: Boolean = false
}
