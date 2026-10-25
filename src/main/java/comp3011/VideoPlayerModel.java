/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Zarni Htet Khaing (a1807729)
 *      - builds its frame processors from the command line instead of a hard coded list
 *      - decoding, effects and image conversion moved off the JavaFX thread into PlaybackPipeline,
 *        this class keeps the clock, audio timing and display. old single threaded frame code removed
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling and the frame processors (one
 * new one per effect flag from the command line, in that order). It publishes frames and playback state
 * through callbacks supplied by its owning {@link VideoPlayerController}, keeping it decoupled from the
 * JavaFX view.
 * </p>
 *
 * <p>
 * Threads: every method here runs on the JavaFX Application Thread and every field is only touched by
 * that thread, so nothing here needs locks. The heavy per frame work (decode, effects, convert to an
 * Image) happens on worker threads in a {@link PlaybackPipeline}, and this class only talks to it through
 * the pipelines queues. Its job each pulse is small, write whatever sound is due and put the next frame
 * up when its time comes. See PlaybackPipeline for the full design and why theres no races or deadlocks.
 * </p>
 *
 * <p>
 * The one thing that gets lent out is the grabber. While a pipeline is running its decoder thread is the
 * only thing that uses it. This class only touches the grabber when no pipeline is running, i.e. after
 * stopPipeline() has waited for the decoder thread to finish.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PlaybackPipeline pipeline; // the worker threads making frames, null when playback isn't open
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessorOption> frameProcessorOptions,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;

        // one new processor per flag, built here once and in order. processors number
        // themselves as they're built and use that as their random seed, so -ss gets two
        // different scratch patterns. see FrameProcessorOption
        List<FrameProcessor> processors = new ArrayList<>(frameProcessorOptions.size());
        for (FrameProcessorOption option : frameProcessorOptions) {
            processors.add(option.createProcessor());
        }
        this.frameProcessors = List.copyOf(processors);
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        // original cleared the queued audio here. doesn't work anymore, the decoder runs ahead of the picture
        // now so clearing would leave a gap of silence after unmuting. instead keep the queue and
        // writeDueAudio() just skips audio that comes due while muted
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            // decoder thread is the only one allowed to use the grabber while the pipeline runs, so stop the
            // pipeline (waits for that thread) BEFORE moving the grabber. frames already in it are from the old
            // position and get thrown away with it
            stopPipeline();
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            startPipeline();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            startPipeline();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    // starts a new pipeline reading from wherever the grabber is now. this thread can't touch the grabber
    // again until stopPipeline()
    private void startPipeline() {
        // copied into locals so the decoder thread makes InfoVideo from values fixed right now, not from fields
        // this thread might change later
        String mediaName = mediaName(videoFile);
        int videoFrameCount = totalVideoFrames;
        double videoFrameRate = frameRate;
        int roundedFrameRate = intFrameRate;
        int frameDurationUs = videoFrameDurationUs;
        FFmpegFrameGrabber decoderGrabber = grabber;

        pipeline = new PlaybackPipeline(
                grabber,
                audioAvailable ? audioPlayer : null,
                frame -> new InfoVideo(
                        mediaName,
                        videoFrameCount,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        videoFrameRate,
                        roundedFrameRate,
                        frameDurationUs,
                        decoderGrabber.getPixelFormat()), // runs on the decoder thread, which owns the grabber now
                frameProcessors);
        pipeline.start();
    }

    // stops the worker threads and waits for them, after this its safe to use the grabber again
    private void stopPipeline() {
        if (pipeline != null) {
            pipeline.stop();
            pipeline = null;
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    // JavaFX calls this every pulse (~60 times a sec) on the JavaFX Application Thread. heavy stuff is in the
    // pipeline now so this just has 2 quick jobs, keep the sound going and put the next frame up when its due.
    // never waits on the pipeline, only looks at whats already arrived, so a slow effect can make a frame late
    // but can't freeze the window
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        writeDueAudio(now);

        PipelineMessage next = pipeline.peekDisplay();
        if (next instanceof PipelineMessage.DisplayableFrame frame) {
            showFrameIfDue(frame, now);
        } else if (next instanceof PipelineMessage.EndOfStream) {
            finishPlayback(); // only gets here after every frame before it was shown
        } else if (next instanceof PipelineMessage.Failure failure) {
            handlePlaybackError(failure.cause());
        }
    }

    private void showFrameIfDue(PipelineMessage.DisplayableFrame frame, long now) {
        if (firstTimestampUs == NO_SEEK_REQUEST) {
            // first frame since play/seek starts the clock and gets shown straight away even if paused, so
            // seeking while paused still updates the picture like the original did
            firstTimestampUs = frame.timestampUs();
            playbackStartNs = System.nanoTime();
            if (pauseRequested) {
                pauseStartedNs = playbackStartNs;
            }
        } else if (pauseRequested || targetTimeNs(frame) > now) {
            return; // not due yet, leave it at the front for a later pulse
        }

        pipeline.removeDisplayHead();
        displayFrame(frame);
    }

    private void displayFrame(PipelineMessage.DisplayableFrame frame) {
        long targetTimeNs = targetTimeNs(frame);
        currentTimestampUs = logicalPlaybackBaseUs + Math.max(0, frame.timestampUs() - firstTimestampUs);
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        // headroom = how long before its display time the frame came out of the pipeline ready
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetTimeNs - frame.readyAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private long targetTimeNs(PipelineMessage.DisplayableFrame frame) {
        long relativeTimestampUs = Math.max(0, frame.timestampUs() - firstTimestampUs);
        return playbackStartNs + relativeTimestampUs * 1_000L;
    }

    private void writeDueAudio(long now) {
        takeDecodedAudio();

        // Handle some obvious early exits
        if (audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            if (!audioOutputEnabled) {
                pendingAudio.remove(); // muted, skip it so unmuting carries on in sync
                continue;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    // moves sound from the decoder onto this threads own queue, where how much has been written (offset)
    // gets tracked without sharing anything
    private void takeDecodedAudio() {
        DecodedAudio decoded;
        while ((decoded = pipeline.pollAudio()) != null) {
            pendingAudio.add(new PendingAudio(decoded.timestampUs(), decoded.samples()));
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();

        // stop the pipeline first, it waits for the decoder thread to finish so nothing is still inside the
        // native decoder when the grabber gets closed below. closing it mid grab would free memory its using
        stopPipeline();

        playbackOpen = false;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
