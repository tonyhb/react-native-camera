package com.lwansbrough.RCTCamera;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera;
import android.media.AudioManager;
import android.view.MotionEvent;

import com.facebook.react.bridge.ReactApplicationContext;

import java.util.ArrayList;

public class RCTCameraUtils {
    private static final int FOCUS_AREA_MOTION_EVENT_EDGE_LENGTH = 100;
    private static final int FOCUS_AREA_WEIGHT = 1000;
    private static boolean sMuteSystemSoundsForRecordStartStopOngoing = false;
    private static int MUTE_SYSTEM_SOUNDS_FOR_RECORD_START_STOP_DURATION = 1250;

    /**
     * Computes a Camera.Area corresponding to the new focus area to focus the camera on. This is
     * done by deriving a square around the center of a MotionEvent pointer (with side length equal
     * to FOCUS_AREA_MOTION_EVENT_EDGE_LENGTH), then transforming this rectangle's/square's
     * coordinates into the (-1000, 1000) coordinate system used for camera focus areas.
     *
     * Also note that we operate on RectF instances for the most part, to avoid any integer
     * division rounding errors going forward. We only round at the very end for playing into
     * the final focus areas list.
     *
     * @throws RuntimeException if unable to compute valid intersection between MotionEvent region
     * and SurfaceTexture region.
     */
    protected static Camera.Area computeFocusAreaFromMotionEvent(final MotionEvent event, final int surfaceTextureWidth, final int surfaceTextureHeight) {
        // Get position of first touch pointer.
        final int pointerId = event.getPointerId(0);
        final int pointerIndex = event.findPointerIndex(pointerId);
        final float centerX = event.getX(pointerIndex);
        final float centerY = event.getY(pointerIndex);

        // Build event rect. Note that coordinates increase right and down, such that left <= right
        // and top <= bottom.
        final RectF eventRect = new RectF(
                centerX - FOCUS_AREA_MOTION_EVENT_EDGE_LENGTH, // left
                centerY - FOCUS_AREA_MOTION_EVENT_EDGE_LENGTH, // top
                centerX + FOCUS_AREA_MOTION_EVENT_EDGE_LENGTH, // right
                centerY + FOCUS_AREA_MOTION_EVENT_EDGE_LENGTH // bottom
        );

        // Intersect this rect with the rect corresponding to the full area of the parent surface
        // texture, making sure we are not placing any amount of the eventRect outside the parent
        // surface's area.
        final RectF surfaceTextureRect = new RectF(
                (float) 0, // left
                (float) 0, // top
                (float) surfaceTextureWidth, // right
                (float) surfaceTextureHeight // bottom
        );
        final boolean intersectSuccess = eventRect.intersect(surfaceTextureRect);
        if (!intersectSuccess) {
            throw new RuntimeException(
                    "MotionEvent rect does not intersect with SurfaceTexture rect; unable to " +
                            "compute focus area"
            );
        }

        // Transform into (-1000, 1000) focus area coordinate system. See
        // https://developer.android.com/reference/android/hardware/Camera.Area.html.
        // Note that if this is ever changed to a Rect instead of RectF, be cautious of integer
        // division rounding!
        final RectF focusAreaRect = new RectF(
                (eventRect.left / surfaceTextureWidth) * 2000 - 1000, // left
                (eventRect.top / surfaceTextureHeight) * 2000 - 1000, // top
                (eventRect.right / surfaceTextureWidth) * 2000 - 1000, // right
                (eventRect.bottom / surfaceTextureHeight) * 2000 - 1000 // bottom
        );
        Rect focusAreaRectRounded = new Rect();
        focusAreaRect.round(focusAreaRectRounded);
        return new Camera.Area(focusAreaRectRounded, FOCUS_AREA_WEIGHT);
    }

    /**
     * List of system sound stream ids that should be muted for record start/stop, when
     * the play-sound-on-capture React prop is OFF.
     * <p>
     * Note that this list may be subject to change, especially if this doesn't seem to mute for
     * certain devices, as it seems there is variation between devices of which stream is used by
     * the system for the media recorder sound. That said, the number of streams muted should be
     * minimized as much as possible while maximizing device coverage, as we don't want to mute any
     * streams that we don't necessarily need to mute.
     */
    private static int[] MUTE_SYSTEM_SOUND_STREAM_IDS_FOR_RECORD_START_STOP = new int[]{
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
    };

    /**
     * Temporarily mute, then unmute, system sounds related to capture record start/stop.
     * <p>
     * Note that this temporary-mute-then-unmute approach seems to work well, while also
     * ensuring that the streams are always unmuted. An alternative would be to call a different
     * function to mute, then record start/stop, then call another function to unmute the streams
     * that were initially muted; this works well in theory, but in practice it seems the system
     * often plays the audio sound asynchronously (not while blocking on media recorder's
     * start/stop), such that the system audio gets played AFTER we've already muted, waited for
     * start/stop, and unmuted again, with the net effect of the sound not getting muted at all.
     * <p>
     * The time that the streams are temporarily muted is controlled by
     * MUTE_SYSTEM_SOUNDS_FOR_RECORD_START_STOP_DURATION. In some basic testing, this seems to be
     * around 1-1.25 seconds for debug settings, and 0.5-1 for release settings. We currently take
     * the upper bound of this range (1.25 seconds), but this may be changed over time if this
     * interacts with other streams too much.
     */
    public static void tempMuteSystemSoundsForRecordStartStop(final ReactApplicationContext reactApplicationContext) {
        // Do nothing if insufficient Android API level. Note that API level 23 is required to
        // properly check if streams are muted via isStreamMute below; without this, we might end up
        // unmuting a stream that we didn't mute in the first place (i.e., muted by system or user
        // beforehand).
        if (android.os.Build.VERSION.SDK_INT < 23) {
            return;
        }

        // If there is already a temp-mute request in progress, do nothing. Otherwise, we might
        // pick up on a mute/unmute that WE did, not the existing setting from the user/system, and
        // would not actually be restoring to the proper state overall. Note that this can happen
        // when we try to start a recording session, but it immediately fails, resulting in two
        // calls to this function almost back-to-back.
        if (sMuteSystemSoundsForRecordStartStopOngoing) {
            return;
        }
        // Otherwise, set that temp-mute request is ongoing now.
        sMuteSystemSoundsForRecordStartStopOngoing = true;

        // Get audio manager.
        final AudioManager audioManager = (AudioManager) reactApplicationContext.getSystemService(Context.AUDIO_SERVICE);

        // Init list of stream ids to unmute later.
        final ArrayList<Integer> mutedStreamIds = new ArrayList<Integer>();

        // Iterate through each stream id we want to mute.
        for (int streamId : MUTE_SYSTEM_SOUND_STREAM_IDS_FOR_RECORD_START_STOP) {
            // If stream is already muted, do nothing.
            if (audioManager.isStreamMute(streamId)) {
                continue;
            }

            // Set stream to muted.
            audioManager.adjustStreamVolume(streamId, AudioManager.ADJUST_MUTE, 0);

            // Add stream id to list of muted streams.
            mutedStreamIds.add(streamId);
        }

        // If empty list of stream ids, then just complete the temp-mute request and exit.
        if (mutedStreamIds.isEmpty()) {
            sMuteSystemSoundsForRecordStartStopOngoing = false;
            return;
        }

        // Otherwise, set handler to be run in the near future to unmute these streams.
        new android.os.Handler().postDelayed(
                new Runnable() {
                    public void run() {
                        // Iterate through each stream id we want to unmute.
                        for (int streamId : mutedStreamIds) {
                            // Set stream to unmuted.
                            audioManager.adjustStreamVolume(streamId, AudioManager.ADJUST_UNMUTE, 0);
                        }

                        // Set that temp-mute request is completed.
                        sMuteSystemSoundsForRecordStartStopOngoing = false;
                    }
                },
                MUTE_SYSTEM_SOUNDS_FOR_RECORD_START_STOP_DURATION);
    }
}
