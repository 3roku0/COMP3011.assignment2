/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

import static comp3011.FrameProcessorOption.BLACK_AND_WHITE;
import static comp3011.FrameProcessorOption.BLEED;
import static comp3011.FrameProcessorOption.DUST;
import static comp3011.FrameProcessorOption.FLICKER;
import static comp3011.FrameProcessorOption.JITTER;
import static comp3011.FrameProcessorOption.MOTTLE;
import static comp3011.FrameProcessorOption.NUMBER;
import static comp3011.FrameProcessorOption.PEPPER;
import static comp3011.FrameProcessorOption.SCRATCH;
import static comp3011.FrameProcessorOption.VIGNETTE;
import static comp3011.FrameProcessorOption.YELLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Regression tests for {@link CommandLineController}. Checks the help text is exact and all the ways
 * of writing the effect flags from the spec work.
 *
 * <p>
 * Only checks which options get picked, never builds an actual processor. Keeps it fast, no video or
 * native libs needed, and doesn't mess with the processors instance counting.
 * </p>
 */
class CommandLineControllerTest {

    @TempDir
    Path tempDir;

    private String videoPath;

    @BeforeEach
    void createVideoFile() throws IOException {
        // parser rejects files that don't exist so give it a real (empty) one, nothing decodes it anyway
        videoPath = Files.createFile(tempDir.resolve("movie.mp4")).toString();
    }

    private CommandLineController parse(String... args) {
        return new CommandLineController(args);
    }

    // copied straight from the spec, not built from the enum, so if the table changes the help this fails
    @Test
    void helpTextMatchesSpecificationExactly() {
        String expected = """
                Usage: VideoPlayer [options] [video-file]

                Options:
                  -h, --help         Show this help message
                  -a, --audio        Play audio
                  -x, --maximise     Open the player maximised
                  -1, --monitor-1    Open the player on display 1
                  -2, --monitor-2    Open the player on display 2

                Frame processors:
                  -n, --number-frames    Render the frame number onto each frame
                  -s, --scratch-frames   Render vertical film scratches
                  -f, --flicker-frames   Randomly dim frames
                  -w, --black-and-white  Convert frames to black and white
                  -y, --yellow-frames    Apply a warmer colour temperature
                  -v, --vignette-frames  Darken the frame edges
                  -d, --dust-frames      Render dust and hair marks
                  -j, --jitter-frames    Randomly displace frames by a few pixels
                  -m, --mottle-frames    Add cloudy emulsion mottling
                  -b, --bleed-frames     Bleed light into frames
                  -p, --pepper-frames    Pepper frames with dark spots/blotches

                Frame processors are applied in command-line order and may be repeated.
                Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,
                converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.
                """;

        assertEquals(expected, CommandLineController.helpText());
    }

    @Test
    void helpExampleStackParsesInCommandLineOrder() {
        CommandLineController controller = parse("-nssnfwyvdjmbp", videoPath);

        assertEquals(
                List.of(NUMBER, SCRATCH, SCRATCH, NUMBER, FLICKER, BLACK_AND_WHITE, YELLOW,
                        VIGNETTE, DUST, JITTER, MOTTLE, BLEED, PEPPER),
                controller.getFrameProcessorOptions());
        assertTrue(controller.shouldLaunchApplication());
    }

    // the 4 commands the spec says are the same: long, separate short, stacked, mixed
    @Test
    void specificationsEquivalentFormsSelectTheSameEffects() {
        List<FrameProcessorOption> expected = List.of(JITTER, FLICKER, DUST, DUST, NUMBER);

        assertEquals(expected, parse("--jitter-frames", "--flicker-frames", "--dust-frames", "--dust-frames",
                "--number-frames", videoPath).getFrameProcessorOptions());
        assertEquals(expected, parse("-j", "-f", "-d", "-d", "-n", videoPath).getFrameProcessorOptions());
        assertEquals(expected, parse("-jfddn", videoPath).getFrameProcessorOptions());
        assertEquals(expected, parse("-jf", "--dust-frames", "-d", "--number-frames", videoPath)
                .getFrameProcessorOptions());
    }

    @ParameterizedTest
    @EnumSource(FrameProcessorOption.class)
    void everyShortFlagSelectsItsProcessor(FrameProcessorOption option) {
        assertEquals(List.of(option), parse("-" + option.shortFlag(), videoPath).getFrameProcessorOptions());
    }

    @ParameterizedTest
    @EnumSource(FrameProcessorOption.class)
    void everyLongFlagSelectsItsProcessor(FrameProcessorOption option) {
        assertEquals(List.of(option), parse(option.longFlag(), videoPath).getFrameProcessorOptions());
    }

    @Test
    void generalOptionsStackWithFrameProcessors() {
        CommandLineController controller = parse("-a1nx", videoPath);

        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(1, controller.getDisplayId());
        assertEquals(List.of(NUMBER), controller.getFrameProcessorOptions());
        assertTrue(controller.shouldLaunchApplication());
    }

    @Test
    void noFrameProcessorFlagsSelectsNoEffects() {
        CommandLineController controller = parse("-a", videoPath);

        assertTrue(controller.getFrameProcessorOptions().isEmpty());
        assertTrue(controller.shouldLaunchApplication());
    }

    @Test
    void unknownLetterInsideAStackIsRejected() {
        CommandLineController controller = parse("-nqs", videoPath);

        assertEquals("Unknown option: -q", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
        assertEquals(1, controller.getExitCode());
    }

    @Test
    void unknownLongFlagIsRejected() {
        CommandLineController controller = parse("--sepia-frames", videoPath);

        assertEquals("Unknown option: --sepia-frames", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void helpAloneShowsHelpWithoutLaunchingOrFailing() {
        CommandLineController controller = parse("-h");

        assertTrue(controller.isHelpRequested());
        assertFalse(controller.shouldLaunchApplication());
        assertEquals(0, controller.getExitCode());
    }

    @Test
    void selectedEffectsCannotBeChangedAfterParsing() {
        List<FrameProcessorOption> options = parse("-n", videoPath).getFrameProcessorOptions();

        assertThrows(UnsupportedOperationException.class, () -> options.add(PEPPER));
    }

    // if someone adds an effect with a letter/flag thats already taken it would quietly hide the other one
    @Test
    void everyFlagIsUniqueAndClearOfTheGeneralOptions() {
        Set<Character> shortFlags = new HashSet<>(List.of('h', 'a', 'x', '1', '2'));
        Set<String> longFlags = new HashSet<>(List.of("--help", "--audio", "--maximise", "--monitor-1", "--monitor-2"));

        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            assertTrue(shortFlags.add(option.shortFlag()), "Duplicate short flag -" + option.shortFlag());
            assertTrue(longFlags.add(option.longFlag()), "Duplicate long flag " + option.longFlag());
        }
    }
}
