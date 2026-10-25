/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import java.util.concurrent.BlockingQueue;

import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.scene.image.Image;

/**
 * Last stage, turns a finished frame into a JavaFX Image ready to show.
 *
 * <p>
 * The original did this on the JavaFX Application Thread. Doing it here takes a full frame pixel copy off
 * the thread that has to keep the window responsive. Making an Image on a worker thread is fine because
 * it isn't in the scene yet, the display thread only gets it through the output queue after this thread
 * is done writing it.
 * </p>
 *
 * <p>
 * The converter copies the pixels into a brand new Image every time, so once its converted we give the
 * frames buffer back to the FrameBufferPool for the decoder to reuse.
 * </p>
 */
final class FrameConverterStage extends PipelineStage {
    private final JavaFXFrameConverter converter; // only used by this stages thread
    private final FrameBufferPool framePool;

    FrameConverterStage(
            JavaFXFrameConverter converter,
            FrameBufferPool framePool,
            BlockingQueue<PipelineMessage> input,
            BlockingQueue<PipelineMessage> output) {
        super(input, output);
        this.converter = converter;
        this.framePool = framePool;
    }

    @Override
    protected PipelineMessage handle(PipelineMessage.DecodedFrame frame) {
        Image image = converter.convert(frame.frame());

        // last use of the frame, nothing reads it after this so the buffer can go back in the pool
        framePool.recycle(frame.frame());

        return new PipelineMessage.DisplayableFrame(
                image,
                frame.info().frameNumber(),
                frame.info().timestampUs(),
                System.nanoTime());
    }
}
