/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import java.util.concurrent.BlockingQueue;

/**
 * One station in the pipeline. Takes messages off its input queue, does its job to each frame, puts the
 * result on its output queue, always in the same order it got them. Subclasses just say what happens
 * to one frame (handle).
 *
 * <p>
 * Threads: each stage object is run by exactly one thread for its whole life, so anything kept in its
 * own fields (an effect, the converter, a flag) is only ever touched by that thread and needs no locks
 * (thread confinement). The two queues are the only things it shares. BlockingQueue is thread safe, and
 * passing something through it is a happens-before edge, everything the sender wrote to a frame before
 * put() is visible to the receiver after take(). Thats what makes it ok for one thread to edit the
 * pixels and the next thread to read them.
 * </p>
 *
 * <p>
 * Stopping: a stage finishes when it passes on an EndOfStream or Failure, or when its thread gets
 * interrupted while waiting on a queue (PlaybackPipeline.stop()). It never waits on anything except its
 * own two queues, so stopping can't deadlock.
 * </p>
 */
abstract class PipelineStage implements Runnable {
    private final BlockingQueue<PipelineMessage> input;
    private final BlockingQueue<PipelineMessage> output;

    protected PipelineStage(BlockingQueue<PipelineMessage> input, BlockingQueue<PipelineMessage> output) {
        this.input = input;
        this.output = output;
    }

    @Override
    public final void run() {
        try {
            while (true) {
                PipelineMessage message = input.take(); // thread sleeps here (no cpu) till something arrives

                if (!(message instanceof PipelineMessage.DecodedFrame frame)) {
                    // end of stream or a failure from further up, pass it on so it gets to the display
                    // behind every earlier frame, then this stage is done
                    output.put(message);
                    return;
                }

                PipelineMessage result;
                try {
                    result = handle(frame);
                } catch (Exception e) {
                    result = new PipelineMessage.Failure(e);
                }

                output.put(result); // sleeps here if the next stage is behind (backpressure)
                if (result instanceof PipelineMessage.Failure) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            // stop() wants us to finish (stop, seek, window closed, end of playback). nothing to clean up,
            // the queues and whatever frames are in them get thrown away with the pipeline
            Thread.currentThread().interrupt();
        }
    }

    /**
     * does this stages job to one frame and returns what goes to the next stage. only called by this
     * stages own thread. any exception turns into a Failure sent down the line in order
     */
    protected abstract PipelineMessage handle(PipelineMessage.DecodedFrame frame) throws Exception;
}
