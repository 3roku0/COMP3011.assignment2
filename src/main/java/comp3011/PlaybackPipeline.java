/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;

/**
 * The multithreaded frame pipeline for one stretch of playback, from play or a seek until the next seek,
 * stop, end of video or error. The model makes a fresh one for each stretch.
 *
 * <pre>
 *  decoder --q--> effect 1 --q--> effect 2 -- ... --q--> converter --q--> JavaFX Application Thread
 *     |          (1 thread each, in command line order)                  (clock, audio, display, GUI)
 *     +------------------------ sound (separate queue) ------------------------------>
 * </pre>
 *
 * <p>
 * Every station is a thread and they hand frames along through ArrayBlockingQueues. While effect 2 is on
 * frame 10, effect 1 is on frame 11 and the decoder on frame 12, so the effects run at the same time on
 * different cores instead of one after the other on the JavaFX thread. The line can only go as fast as
 * its slowest station, but one effect is a small part of the 41.7ms frame budget.
 * </p>
 *
 * <p>
 * Why theres no races:
 * </p>
 * <ol>
 *   <li>a frame has one owner at a time, whichever stage last took it off a queue. it gives it up by
 *       putting it on the next queue. going through a BlockingQueue also means the next thread sees
 *       every pixel the last one wrote (happens-before)</li>
 *   <li>the decoder copies every frame before sending it, the grabber reuses its buffer
 *       (FrameBufferPool)</li>
 *   <li>each effect object is confined to one stage thread. Bleeder/Jitterer/Mottler keep work buffers
 *       in their fields so they can never be shared</li>
 *   <li>pipelines never overlap. stop() interrupts and then joins every thread before returning, and
 *       the model only makes the next pipeline after that. so the effect objects and the grabber are
 *       never used by an old thread and a new one at the same time. join also makes everything the old
 *       threads did visible to the model</li>
 *   <li>each pipeline has its own queues so frames decoded before a seek get thrown away with the old
 *       pipeline and can't end up on screen</li>
 *   <li>clock, pause state, audio timing and all the GUI stuff stay on the JavaFX thread like the
 *       original. workers never touch them, they only put things on queues</li>
 * </ol>
 *
 * <p>
 * Why it can't deadlock: a worker only ever waits on its own input queue or its own output queue. The
 * stations are in a straight line so nobody can be waiting on each other in a circle, and no locks are
 * held while waiting. The JavaFX thread never waits on the pipeline, it only peeks at whats already
 * arrived. The only waiting it does is the join in stop(), and that always finishes because interrupt
 * wakes a thread stuck on a queue, and no worker ever waits on the JavaFX thread.
 * </p>
 *
 * <p>
 * Memory: queues are bounded so if the display falls behind or its paused, the stations fill up and
 * then sleep instead of decoding the whole movie into memory (backpressure).
 * </p>
 */
final class PlaybackPipeline {
    /** frames allowed between two stations. 2 lets a station start the next frame straight away */
    static final int STAGE_QUEUE_CAPACITY = 2;

    /** finished frames waiting for their display time, ~1/3 sec at 24fps to soak up hiccups like GC */
    static final int DISPLAY_QUEUE_CAPACITY = 8;

    private final List<Thread> workers = new ArrayList<>();
    private final BlockingQueue<PipelineMessage> displayQueue = new ArrayBlockingQueue<>(DISPLAY_QUEUE_CAPACITY);
    private final Queue<DecodedAudio> audioQueue = new ConcurrentLinkedQueue<>();
    private final JavaFXFrameConverter converter = new JavaFXFrameConverter();

    /**
     * Builds the stations but doesn't start them. Grabber has to be open and in position already, from
     * start() until stop() returns only the decoder thread can use it.
     *
     * @param audioSampleCopier audio player used for copySamples, or null if theres no sound
     * @param videoInfoFactory  makes the InfoVideo for the effects from the first picture, runs on the
     *                          decoder thread
     * @param processors        effects in command line order, one station each
     */
    PlaybackPipeline(
            FFmpegFrameGrabber grabber,
            AudioPlayer audioSampleCopier,
            Function<Frame, InfoVideo> videoInfoFactory,
            List<FrameProcessor> processors) {
        FrameBufferPool framePool = new FrameBufferPool();

        BlockingQueue<PipelineMessage> stationInput = newStageQueue();
        addWorker("frame-decoder",
                new FrameDecoder(grabber, audioSampleCopier, videoInfoFactory, framePool, stationInput, audioQueue));

        // chain one station per effect in command line order so they get applied in that order
        for (int i = 0; i < processors.size(); i++) {
            FrameProcessor processor = processors.get(i);
            BlockingQueue<PipelineMessage> stationOutput = newStageQueue();
            addWorker("frame-effect-" + (i + 1) + "-" + processor.getClass().getSimpleName(),
                    new FrameProcessorStage(processor, stationInput, stationOutput));
            stationInput = stationOutput;
        }

        addWorker("frame-converter", new FrameConverterStage(converter, framePool, stationInput, displayQueue));
    }

    /** start every station. JavaFX thread only */
    void start() {
        for (Thread worker : workers) {
            worker.start();
        }
    }

    /**
     * Stops every station and waits till they've all actually finished. After this the grabber and
     * effect objects are free for the caller to use again. JavaFX thread only.
     *
     * <p>
     * Quick to return, a station waiting on a queue wakes up straight away when interrupted, and a busy
     * one finishes the frame its on (one effect, a few ms) then sees the interrupt at its next queue.
     * </p>
     */
    void stop() {
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Thread worker : workers) {
            joinUninterruptibly(worker);
        }
        converter.close(); // fine now the converter thread is done
    }

    /** next finished message without taking it off, or null if nothing's arrived. never waits. JavaFX thread only */
    PipelineMessage peekDisplay() {
        return displayQueue.peek();
    }

    /**
     * removes the message peekDisplay() just returned. still the head because the JavaFX thread is the
     * only one that ever removes from this queue, the converter only adds to the back
     */
    void removeDisplayHead() {
        displayQueue.poll();
    }

    /** next chunk of sound or null if none waiting. never waits */
    DecodedAudio pollAudio() {
        return audioQueue.poll();
    }

    private void addWorker(String name, Runnable station) {
        // daemon so they can't keep the JVM alive, backup in case the window closes without a clean stop()
        workers.add(Thread.ofPlatform().name(name).daemon(true).unstarted(station));
    }

    private static BlockingQueue<PipelineMessage> newStageQueue() {
        return new ArrayBlockingQueue<>(STAGE_QUEUE_CAPACITY);
    }

    // the caller is about to reuse the grabber and effects, so we can't return while a worker might still
    // be using them, even if the calling thread itself gets interrupted
    private static void joinUninterruptibly(Thread worker) {
        boolean interrupted = false;
        while (true) {
            try {
                worker.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
