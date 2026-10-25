/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import org.bytedeco.javacv.Frame;

import javafx.scene.image.Image;

/**
 * Everything that goes along the pipeline queues, decoder -> effect threads -> converter -> JavaFX thread.
 *
 * <p>
 * Sealed so these 4 are the only kinds of message, and they're records so a message can't change once
 * its sent. The only thing that does change is the pixels inside a DecodedFrame, the effects edit those
 * in place, but only one thread has the frame at a time (whoever took it off a queue last).
 * </p>
 */
public sealed interface PipelineMessage {

    /** a decoded picture going through the effects. the Frame is our own copy, not the grabbers buffer */
    record DecodedFrame(Frame frame, InfoFrame info, InfoVideo videoInfo) implements PipelineMessage {
    }

    /** finished picture as a javafx Image, waiting to be shown. readyAtNs is when it came out the end */
    record DisplayableFrame(Image image, int frameNumber, long timestampUs, long readyAtNs)
            implements PipelineMessage {
    }

    /**
     * video's finished. goes down the line behind the last frame so the display only sees it after
     * every frame got shown
     */
    record EndOfStream() implements PipelineMessage {
    }

    /**
     * something broke in a stage. also goes down in order so the error gets to the javafx thread
     * instead of just dying quietly on a worker thread
     */
    record Failure(Exception cause) implements PipelineMessage {
    }
}
