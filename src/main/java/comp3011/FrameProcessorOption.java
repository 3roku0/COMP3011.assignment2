/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * All the effects you can pick from the command line live here. The parser, help text and the
 * model all read from this one table.
 *
 * <p>
 * To add a new effect: make a FrameProcessor subclass, then add a line here with its letter, long
 * flag, help text and constructor. Don't reuse h, a, x, 1 or 2 since those are already taken.
 * Help prints these in the order below so don't sort them.
 * </p>
 *
 * <p>
 * Each entry stores a recipe (Supplier) not an actual processor, so something like -ss gives two
 * separate scratch objects instead of the same one twice. Processors count themselves as they get
 * built and use that count as their random seed, so only build them once per flag, in order, and
 * nowhere else.
 * </p>
 */
public enum FrameProcessorOption {
    NUMBER('n', "--number-frames", "Render the frame number onto each frame", FrameNumberer::new),
    SCRATCH('s', "--scratch-frames", "Render vertical film scratches", FrameScratcher::new),
    FLICKER('f', "--flicker-frames", "Randomly dim frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "--black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
    YELLOW('y', "--yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
    VIGNETTE('v', "--vignette-frames", "Darken the frame edges", FrameVignetter::new),
    DUST('d', "--dust-frames", "Render dust and hair marks", FrameDuster::new),
    JITTER('j', "--jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
    MOTTLE('m', "--mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
    BLEED('b', "--bleed-frames", "Bleed light into frames", FrameBleeder::new),
    PEPPER('p', "--pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);

    private final char shortFlag;
    private final String longFlag;
    private final String description;
    private final Supplier<FrameProcessor> factory;

    FrameProcessorOption(char shortFlag, String longFlag, String description, Supplier<FrameProcessor> factory) {
        this.shortFlag = shortFlag;
        this.longFlag = longFlag;
        this.description = description;
        this.factory = factory;
    }

    public char shortFlag() {
        return shortFlag;
    }

    public String longFlag() {
        return longFlag;
    }

    public String description() {
        return description;
    }

    /** Builds a new processor. Each call returns a different object. */
    public FrameProcessor createProcessor() {
        return factory.get();
    }

    /** The option for a letter such as 's', if any. */
    public static Optional<FrameProcessorOption> fromShortFlag(char flag) {
        for (FrameProcessorOption option : values()) {
            if (option.shortFlag == flag) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    /** The option for a flag such as "--scratch-frames", if any. */
    public static Optional<FrameProcessorOption> fromLongFlag(String flag) {
        for (FrameProcessorOption option : values()) {
            if (option.longFlag.equals(flag)) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }
}
