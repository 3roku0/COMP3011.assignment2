/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Checks running the effects on a chain of FrameProcessorStage threads is safe. Same pixels as the
 * original single threaded player, each effect object only ever used by one thread, frames stay in order,
 * and failures come out in order instead of getting lost on a worker thread.
 *
 * <p>
 * Uses the real stage class, real threads and real bounded queues, just with small made up frames so
 * theres no video file, native decoder or display needed.
 * </p>
 */
class FrameProcessorStageTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int CHANNELS = 3;
    private static final int STRIDE = WIDTH * CHANNELS;
    private static final int FRAME_COUNT = 60;
    private static final InfoVideo VIDEO_INFO = new InfoVideo(
            "stage-test-clip", FRAME_COUNT, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, CHANNELS, STRIDE, 24.0, 24, 41_667, 0);

    /**
     * The main correctness check. Every effect (incl. bleed/jitter/mottle which reuse buffers in their
     * fields, plus a few repeats) gets applied 2 ways to identical frames, one thread doing every effect in
     * turn like the original, and one thread per effect like the pipeline. Pixels have to match exactly.
     */
    @Test
    void pipelinedEffectsProduceExactlyTheSamePixelsAsSingleThreadedProcessing() throws Exception {
        List<FrameProcessor> processors = new ArrayList<>();
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            processors.add(option.createProcessor());
        }
        processors.add(FrameProcessorOption.SCRATCH.createProcessor());
        processors.add(FrameProcessorOption.BLEED.createProcessor());
        processors.add(FrameProcessorOption.NUMBER.createProcessor());

        // 1. the original way, one thread does every effect to each frame in turn
        List<Frame> expected = syntheticFrames();
        for (FrameProcessor processor : processors) {
            processor.initialise(VIDEO_INFO);
        }
        for (int frameNumber = 0; frameNumber < FRAME_COUNT; frameNumber++) {
            for (FrameProcessor processor : processors) {
                processor.process(expected.get(frameNumber), infoFor(frameNumber));
            }
        }

        // 2. same processor objects again but one thread each. effects are deterministic per frame number so
        //    reusing them has to give the same output
        List<PipelineMessage> results = runThroughStages(processors, syntheticFrames());

        assertEquals(FRAME_COUNT + 1, results.size());
        for (int frameNumber = 0; frameNumber < FRAME_COUNT; frameNumber++) {
            PipelineMessage.DecodedFrame actual =
                    assertInstanceOf(PipelineMessage.DecodedFrame.class, results.get(frameNumber));
            assertArrayEquals(pixelsOf(expected.get(frameNumber)), pixelsOf(actual.frame()),
                    "frame " + frameNumber + " is different from single threaded");
        }
        assertInstanceOf(PipelineMessage.EndOfStream.class, results.get(FRAME_COUNT));
    }

    @Test
    void eachEffectIsUsedByExactlyOneThreadAndSeesFramesInOrder() throws Exception {
        List<RecordingProcessor> recorders = List.of(
                new RecordingProcessor(), new RecordingProcessor(), new RecordingProcessor());

        runThroughStages(recorders, syntheticFrames());

        List<Integer> inOrder = new ArrayList<>();
        for (int frameNumber = 0; frameNumber < FRAME_COUNT; frameNumber++) {
            inOrder.add(frameNumber);
        }

        Set<Thread> threadsAcrossAllEffects = new HashSet<>();
        for (RecordingProcessor recorder : recorders) {
            Set<Thread> callingThreads = new HashSet<>(recorder.callingThreads);
            assertEquals(1, callingThreads.size(), "an effect object got called from more than one thread");
            assertEquals(inOrder, new ArrayList<>(recorder.frameNumbersSeen), "an effect saw frames out of order");
            threadsAcrossAllEffects.addAll(callingThreads);
        }
        assertEquals(recorders.size(), threadsAcrossAllEffects.size(), "effects should be on separate threads");
    }

    @Test
    void failingEffectStopsAfterPassingOnEarlierFramesThenTheFailure() throws Exception {
        IllegalStateException fault = new IllegalStateException("simulated effect failure");
        FrameProcessor failsOnFrameThree = new FrameProcessor() {
            @Override
            public void process(Frame frame, InfoFrame info) {
                if (info.frameNumber() == 3) {
                    throw fault;
                }
            }
        };

        List<PipelineMessage> results = runThroughStages(List.of(failsOnFrameThree), syntheticFrames());

        assertEquals(4, results.size());
        for (int frameNumber = 0; frameNumber < 3; frameNumber++) {
            PipelineMessage.DecodedFrame frame =
                    assertInstanceOf(PipelineMessage.DecodedFrame.class, results.get(frameNumber));
            assertEquals(frameNumber, frame.info().frameNumber());
        }
        PipelineMessage.Failure failure = assertInstanceOf(PipelineMessage.Failure.class, results.get(3));
        assertSame(fault, failure.cause());
    }

    // chains the processors into stage threads with the same bounded queues the player uses, feeds frames in
    // from another thread, and collects whatever comes out the end up to the end of stream / failure. then stops
    // every thread like PlaybackPipeline.stop() does and checks they really ended
    private static List<PipelineMessage> runThroughStages(List<? extends FrameProcessor> processors, List<Frame> frames)
            throws InterruptedException {
        BlockingQueue<PipelineMessage> firstQueue = newStageQueue();
        BlockingQueue<PipelineMessage> stageInput = firstQueue;
        List<Thread> threads = new ArrayList<>();

        for (FrameProcessor processor : processors) {
            BlockingQueue<PipelineMessage> stageOutput = newStageQueue();
            threads.add(Thread.ofPlatform().start(new FrameProcessorStage(processor, stageInput, stageOutput)));
            stageInput = stageOutput;
        }
        BlockingQueue<PipelineMessage> lastQueue = stageInput;

        threads.add(Thread.ofPlatform().start(() -> {
            try {
                for (int frameNumber = 0; frameNumber < frames.size(); frameNumber++) {
                    firstQueue.put(new PipelineMessage.DecodedFrame(frames.get(frameNumber), infoFor(frameNumber),
                            VIDEO_INFO));
                }
                firstQueue.put(new PipelineMessage.EndOfStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // stopped early, e.g. after a failure further down
            }
        }));

        List<PipelineMessage> results = new ArrayList<>();
        while (true) {
            PipelineMessage message = lastQueue.poll(10, TimeUnit.SECONDS);
            assertNotNull(message, "pipeline stalled, nothing came out for 10 seconds");
            results.add(message);
            if (!(message instanceof PipelineMessage.DecodedFrame)) {
                break;
            }
        }

        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(thread.isAlive(), "a stage thread didn't stop when interrupted");
        }
        return results;
    }

    private static BlockingQueue<PipelineMessage> newStageQueue() {
        return new ArrayBlockingQueue<>(PlaybackPipeline.STAGE_QUEUE_CAPACITY);
    }

    private static InfoFrame infoFor(int frameNumber) {
        return new InfoFrame(frameNumber, frameNumber * 41_667L);
    }

    // same random-ish pictures every call, with bright and dark bits so bleed has highlights to work on
    private static List<Frame> syntheticFrames() {
        Random random = new Random(3011);
        List<Frame> frames = new ArrayList<>();
        for (int frameNumber = 0; frameNumber < FRAME_COUNT; frameNumber++) {
            byte[] pixels = new byte[STRIDE * HEIGHT];
            random.nextBytes(pixels);

            Frame frame = new Frame();
            frame.imageWidth = WIDTH;
            frame.imageHeight = HEIGHT;
            frame.imageDepth = Frame.DEPTH_UBYTE;
            frame.imageChannels = CHANNELS;
            frame.imageStride = STRIDE;
            frame.image = new Buffer[] { ByteBuffer.allocateDirect(pixels.length).put(pixels).clear() };
            frames.add(frame);
        }
        return frames;
    }

    private static byte[] pixelsOf(Frame frame) {
        ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
        pixels.clear();
        byte[] copy = new byte[pixels.remaining()];
        pixels.get(copy);
        return copy;
    }

    /** fake effect that changes nothing, just records which threads call it and the frame order */
    private static final class RecordingProcessor extends FrameProcessor {
        // thread safe lists so if a bug did let 2 threads call this, the test still records it properly
        // instead of messing up its own bookkeeping
        final Queue<Thread> callingThreads = new ConcurrentLinkedQueue<>();
        final Queue<Integer> frameNumbersSeen = new ConcurrentLinkedQueue<>();

        @Override
        public void process(Frame frame, InfoFrame info) {
            callingThreads.add(Thread.currentThread());
            frameNumbersSeen.add(info.frameNumber());
        }
    }
}
