/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 *
 * The grab loop is adapted from VideoPlayerModel.readNextVideoFrame in the starter code by Simon Ratcliffe.
 */
package comp3011;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * First stage, reads pictures and sound out of the video file on its own thread.
 *
 * <p>
 * Pictures get copied into our own buffers first (see FrameBufferPool for why) and sent down the effects
 * in order. Sound goes on a separate unbounded queue straight to the JavaFX thread which plays it against
 * the clock. Sound skips the effects because it doesn't need them and it shouldn't have to wait behind
 * slow ones. It can't pile up forever either, if the video queue is full this thread is stuck in put()
 * so it stops decoding sound too.
 * </p>
 *
 * <p>
 * While this thread is running its the only thing allowed to use the grabber. The model only seeks or
 * closes the grabber after PlaybackPipeline.stop() has joined this thread.
 * </p>
 */
final class FrameDecoder implements Runnable {
    private final FFmpegFrameGrabber grabber;
    private final AudioPlayer audioSampleCopier; // null when theres no sound
    private final Function<Frame, InfoVideo> videoInfoFactory;
    private final FrameBufferPool framePool;
    private final BlockingQueue<PipelineMessage> output;
    private final Queue<DecodedAudio> audioOutput;

    FrameDecoder(
            FFmpegFrameGrabber grabber,
            AudioPlayer audioSampleCopier,
            Function<Frame, InfoVideo> videoInfoFactory,
            FrameBufferPool framePool,
            BlockingQueue<PipelineMessage> output,
            Queue<DecodedAudio> audioOutput) {
        this.grabber = grabber;
        this.audioSampleCopier = audioSampleCopier;
        this.videoInfoFactory = videoInfoFactory;
        this.framePool = framePool;
        this.output = output;
        this.audioOutput = audioOutput;
    }

    @Override
    public void run() {
        try {
            InfoVideo videoInfo = null;

            while (!Thread.currentThread().isInterrupted()) {
                Frame grabbed = audioSampleCopier != null ? grabber.grab() : grabber.grabImage();
                if (grabbed == null) {
                    output.put(new PipelineMessage.EndOfStream());
                    return;
                }

                long timestampUs = grabber.getTimestamp();
                if (audioSampleCopier != null && grabbed.samples != null) {
                    byte[] samples = audioSampleCopier.copySamples(grabbed);
                    if (samples.length > 0) {
                        audioOutput.add(new DecodedAudio(timestampUs, samples));
                    }
                }

                if (grabbed.image == null) {
                    continue; // sound only frame
                }

                if (videoInfo == null) {
                    videoInfo = videoInfoFactory.apply(grabbed);
                }

                InfoFrame info = new InfoFrame(grabber.getFrameNumber(), timestampUs);
                // copy BEFORE sending, the next grab() reuses the grabbers buffer
                output.put(new PipelineMessage.DecodedFrame(framePool.copyOf(grabbed), info, videoInfo));
            }
        } catch (InterruptedException e) {
            // stop() asked us to finish, grabber goes back to the model as is
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            reportFailure(e);
        }
    }

    private void reportFailure(Exception cause) {
        try {
            output.put(new PipelineMessage.Failure(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // being stopped anyway so the failure doesn't matter
        }
    }
}
