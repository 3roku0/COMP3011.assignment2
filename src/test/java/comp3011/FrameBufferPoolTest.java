/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Tests the frame tearing fix. The grabber reuses one pixel buffer for every frame so the pipeline has
 * to work on copies that the next grab can't touch.
 */
class FrameBufferPoolTest {
    private static final int WIDTH = 4;
    private static final int HEIGHT = 3;
    private static final int STRIDE = 16; // wider than WIDTH * 3 on purpose, real decoder rows can have padding

    @Test
    void copyKeepsItsPixelsWhenTheDecoderReusesItsBuffer() {
        FrameBufferPool pool = new FrameBufferPool();
        Frame grabberFrame = frameFilledWith((byte) 10);

        Frame copy = pool.copyOf(grabberFrame);
        fill(grabberFrame, (byte) 99); // pretend the next grab just wrote over the grabbers buffer

        assertNotSame(grabberFrame.image[0], copy.image[0]);
        assertArrayEquals(filled((byte) 10), pixelsOf(copy));
        assertEquals(WIDTH, copy.imageWidth);
        assertEquals(HEIGHT, copy.imageHeight);
        assertEquals(STRIDE, copy.imageStride);
        assertEquals(3, copy.imageChannels);
        assertEquals(Frame.DEPTH_UBYTE, copy.imageDepth);
    }

    @Test
    void recycledBufferIsReusedAndFullyOverwritten() {
        FrameBufferPool pool = new FrameBufferPool();
        Frame first = pool.copyOf(frameFilledWith((byte) 1));
        pool.recycle(first);

        Frame second = pool.copyOf(frameFilledWith((byte) 2));

        assertSame(first, second, "recycled frame should get reused, not a new 4.6MB buffer");
        assertArrayEquals(filled((byte) 2), pixelsOf(second));
    }

    private static Frame frameFilledWith(byte value) {
        Frame frame = new Frame();
        frame.imageWidth = WIDTH;
        frame.imageHeight = HEIGHT;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = 3;
        frame.imageStride = STRIDE;
        frame.image = new Buffer[] { ByteBuffer.allocateDirect(STRIDE * HEIGHT) };
        fill(frame, value);
        return frame;
    }

    private static void fill(Frame frame, byte value) {
        ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
        pixels.clear();
        pixels.put(filled(value));
    }

    private static byte[] filled(byte value) {
        byte[] bytes = new byte[STRIDE * HEIGHT];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static byte[] pixelsOf(Frame frame) {
        ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
        pixels.clear();
        byte[] copy = new byte[STRIDE * HEIGHT];
        pixels.get(copy);
        return copy;
    }
}
