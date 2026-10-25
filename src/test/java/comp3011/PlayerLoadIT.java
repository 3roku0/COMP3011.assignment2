/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Load test, compares the original single threaded way of doing the effects against the pipeline.
 *
 * <p>
 * Uses the heavy stack (all 10 effects plus numbering, -pbmjdvywfsn) on full size 1920x800 frames with
 * the real effect classes. Single threaded = one thread does every effect on each frame in turn, which
 * is what the original player did on the JavaFX thread. Pipelined = the real FrameProcessorStage threads,
 * one per effect, joined by the same bounded queues the player uses. No video file or window needed so
 * it runs anywhere mvn verify runs.
 * </p>
 *
 * <p>
 * Its an IT so failsafe runs it in mvn verify, not surefire in mvn package (takes a while). On machines
 * with less than 4 cores a speedup isn't possible (nothing to run in parallel on) so there it just prints
 * the numbers and gets skipped instead of failing.
 * </p>
 */
class PlayerLoadIT {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 800;
    private static final int CHANNELS = 3;
    private static final int STRIDE = WIDTH * CHANNELS;
    private static final String HEAVY_STACK = "pbmjdvywfsn";
    private static final int WARMUP_FRAMES = 24;
    private static final int MEASURED_FRAMES = 72;
    private static final double MIN_SPEEDUP = 1.5;
    private static final double FRAME_RATE = 23.976;
    private static final InfoVideo VIDEO_INFO = new InfoVideo(
            "load-test-clip", 10_000, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, CHANNELS, STRIDE,
            FRAME_RATE, 24, 41_708, 0);

    @Test
    void pipelineIsFasterThanSingleThreadedOnTheHeavyStack() throws Exception {
        byte[] picture = randomPicture();

        // warm up first. the JVM starts off interpreting bytecode and only JIT compiles a method after
        // its run a bunch, so timing from cold would make whichever ran first look slow for no reason
        singleThreadedFps(picture, WARMUP_FRAMES);
        pipelinedFps(picture, WARMUP_FRAMES);

        double singleFps = singleThreadedFps(picture, MEASURED_FRAMES);
        double pipelinedFps = pipelinedFps(picture, MEASURED_FRAMES);
        double speedup = pipelinedFps / singleFps;
        int cores = Runtime.getRuntime().availableProcessors();

        System.out.printf("Load test -%s on %dx%d, %d cores%n", HEAVY_STACK, WIDTH, HEIGHT, cores);
        System.out.printf("  single threaded (original): %6.1f fps%n", singleFps);
        System.out.printf("  pipelined (multithreaded):  %6.1f fps%n", pipelinedFps);
        System.out.printf("  speedup: %.2fx, real time needs %.1f fps%n", speedup, FRAME_RATE);

        assumeTrue(cores >= 4, "only " + cores + " cores, not enough to expect a speedup");
        assertTrue(speedup >= MIN_SPEEDUP,
                String.format("pipeline only %.2fx faster than single threaded, expected at least %.1fx",
                        speedup, MIN_SPEEDUP));
    }

    // the original way, one thread does every effect to each frame one after the other
    private static double singleThreadedFps(byte[] picture, int frameCount) throws Exception {
        List<FrameProcessor> processors = heavyStack();
        for (FrameProcessor processor : processors) {
            processor.initialise(VIDEO_INFO);
        }

        long start = System.nanoTime();
        for (int frameNumber = 0; frameNumber < frameCount; frameNumber++) {
            Frame frame = frameOf(picture);
            InfoFrame info = infoFor(frameNumber);
            for (FrameProcessor processor : processors) {
                processor.process(frame, info);
            }
        }
        return framesPerSecond(frameCount, System.nanoTime() - start);
    }

    // the new way, one FrameProcessorStage thread per effect with bounded queues between them
    private static double pipelinedFps(byte[] picture, int frameCount) throws Exception {
        BlockingQueue<PipelineMessage> firstQueue = newStageQueue();
        BlockingQueue<PipelineMessage> stageInput = firstQueue;
        List<Thread> threads = new ArrayList<>();

        for (FrameProcessor processor : heavyStack()) {
            BlockingQueue<PipelineMessage> stageOutput = newStageQueue();
            threads.add(Thread.ofPlatform().daemon(true)
                    .start(new FrameProcessorStage(processor, stageInput, stageOutput)));
            stageInput = stageOutput;
        }
        BlockingQueue<PipelineMessage> lastQueue = stageInput;

        long start = System.nanoTime();

        // feeds frames in like the decoder does
        threads.add(Thread.ofPlatform().daemon(true).start(() -> {
            try {
                for (int frameNumber = 0; frameNumber < frameCount; frameNumber++) {
                    firstQueue.put(new PipelineMessage.DecodedFrame(frameOf(picture), infoFor(frameNumber), VIDEO_INFO));
                }
                firstQueue.put(new PipelineMessage.EndOfStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        int framesOut = 0;
        try {
            while (true) {
                PipelineMessage message = lastQueue.poll(30, TimeUnit.SECONDS);
                if (message == null) {
                    fail("pipeline stalled, nothing came out for 30 seconds");
                } else if (message instanceof PipelineMessage.Failure failure) {
                    throw new AssertionError("an effect failed in the pipeline", failure.cause());
                } else if (message instanceof PipelineMessage.EndOfStream) {
                    break;
                } else {
                    framesOut++;
                }
            }
        } finally {
            for (Thread thread : threads) {
                thread.interrupt();
            }
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(10));
            }
        }

        long elapsed = System.nanoTime() - start;
        assertTrue(framesOut == frameCount, "expected " + frameCount + " frames out but got " + framesOut);
        return framesPerSecond(frameCount, elapsed);
    }

    // new processor objects each run, in command line order, built the same way the model does
    private static List<FrameProcessor> heavyStack() {
        List<FrameProcessor> processors = new ArrayList<>();
        for (char flag : HEAVY_STACK.toCharArray()) {
            processors.add(FrameProcessorOption.fromShortFlag(flag).orElseThrow().createProcessor());
        }
        return processors;
    }

    private static BlockingQueue<PipelineMessage> newStageQueue() {
        return new ArrayBlockingQueue<>(PlaybackPipeline.STAGE_QUEUE_CAPACITY);
    }

    // fresh copy of the picture each frame, like the decoder copying out of the grabber
    private static Frame frameOf(byte[] picture) {
        Frame frame = new Frame();
        frame.imageWidth = WIDTH;
        frame.imageHeight = HEIGHT;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = CHANNELS;
        frame.imageStride = STRIDE;
        frame.image = new Buffer[] { ByteBuffer.wrap(picture.clone()) };
        return frame;
    }

    private static InfoFrame infoFor(int frameNumber) {
        return new InfoFrame(frameNumber, Math.round(frameNumber * 1_000_000.0 / FRAME_RATE));
    }

    private static byte[] randomPicture() {
        byte[] pixels = new byte[STRIDE * HEIGHT];
        new Random(3011).nextBytes(pixels);
        return pixels;
    }

    private static double framesPerSecond(int frames, long nanos) {
        return frames / (nanos / 1_000_000_000.0);
    }
}
