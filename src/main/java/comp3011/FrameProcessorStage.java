/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import java.util.concurrent.BlockingQueue;

/**
 * The stage that applies one FrameProcessor effect, on its own thread.
 *
 * <p>
 * One thread per effect is what makes the effects run in parallel, while this stage is on frame 10 the
 * one before it is already on frame 11. It's also what keeps them correct. Bleeder, Jitterer and Mottler
 * reuse work buffers kept in their own fields every frame, so if two threads used the same one of those
 * objects at once they'd write over each others half done work. Here each processor object is only ever
 * used by one thread so that can't happen, and none of the effects needed a lock or any changes.
 * </p>
 *
 * <p>
 * The same processor objects get reused by every pipeline the model makes (after each seek etc), but a
 * new pipeline only starts after the old ones threads have been joined, so two threads never have the
 * same processor at the same time.
 * </p>
 */
final class FrameProcessorStage extends PipelineStage {
    private final FrameProcessor processor;
    private boolean initialised; // only this stages thread reads/writes this

    FrameProcessorStage(
            FrameProcessor processor,
            BlockingQueue<PipelineMessage> input,
            BlockingQueue<PipelineMessage> output) {
        super(input, output);
        this.processor = processor;
    }

    @Override
    protected PipelineMessage handle(PipelineMessage.DecodedFrame frame) throws Exception {
        // initialise here on this stages own thread, not in the model, so the processors fields are only
        // ever written by the same thread that reads them
        if (!initialised) {
            processor.initialise(frame.videoInfo());
            initialised = true;
        }

        processor.process(frame.frame(), frame.info());
        return frame; // same frame, edited in place, handed on to the next stage
    }
}
