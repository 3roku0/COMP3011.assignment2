/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.bytedeco.javacv.Frame;

/**
 * Gives each decoded frame its own pixel buffer, and reuses the buffers once a frame is done.
 *
 * <p>
 * Why copy: the grabber gives back the SAME Frame and the same pixel memory on every grab(). Fine when
 * one thread did everything, but in the pipeline the decoder grabs the next frame while the effects are
 * still working on earlier ones. Without a copy an effect would be editing pixels the decoder is
 * writing over, top half one frame bottom half the next (frame tearing). So the decoder copies first.
 * </p>
 *
 * <p>
 * Why reuse: a 1920x800 frame is ~4.6MB, a new one every frame is ~110MB/s of garbage at 24fps and the
 * GC pauses show up as stutter. Reusing means we only ever make enough to fill the pipeline.
 * </p>
 *
 * <p>
 * Threads: copyOf() is only called by the decoder thread, recycle() only by the converter thread. The
 * free list is a ConcurrentLinkedQueue so thats safe, and the decoder only gets a buffer back after the
 * converter is finished with it. Never blocks, if nothing is free it just makes a new one. Frames thrown
 * away on a seek never come back, so a pool that waited for a free buffer could hang the decoder.
 * </p>
 */
final class FrameBufferPool {
    private final Queue<Frame> freeFrames = new ConcurrentLinkedQueue<>();

    /** a frame the caller owns with a copy of decoded's pixels. decoder thread only */
    Frame copyOf(Frame decoded) {
        if (!(decoded.image[0] instanceof ByteBuffer decodedPixels)) {
            throw new IllegalArgumentException("only byte image frames are supported");
        }

        int byteCount = decoded.imageStride * decoded.imageHeight;
        Frame copy = freeFrames.poll();
        if (copy == null || copy.image[0].capacity() < byteCount) {
            copy = newFrameLike(decoded, byteCount);
        }

        // duplicate() so we don't mess up either buffers position/limit, the effects use absolute indexes
        ByteBuffer source = decodedPixels.duplicate();
        source.clear();
        source.limit(byteCount);
        ByteBuffer destination = ((ByteBuffer) copy.image[0]).duplicate();
        destination.clear();
        destination.put(source);

        return copy;
    }

    /** give a frames buffer back to be reused. caller can't touch the frame after this. converter thread only */
    void recycle(Frame frame) {
        freeFrames.offer(frame);
    }

    private static Frame newFrameLike(Frame decoded, int byteCount) {
        Frame frame = new Frame();
        frame.imageWidth = decoded.imageWidth;
        frame.imageHeight = decoded.imageHeight;
        frame.imageDepth = decoded.imageDepth;
        frame.imageChannels = decoded.imageChannels;
        frame.imageStride = decoded.imageStride;
        frame.image = new Buffer[] { ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()) };
        return frame;
    }
}
