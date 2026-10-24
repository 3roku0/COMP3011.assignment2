/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Zarni Htet Khaing (a1807729)
 *     
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 *
 * <p>
 * Frame processor flags just get recorded as a list of FrameProcessorOption in the order they were typed.
 * This class doesn't build any processors, the model does that. New effects go in FrameProcessorOption,
 * nothing here needs to change.
 * </p>
 */
public class CommandLineController {
    // the spec lines the two help blocks up at different columns,
    // options use 15 wide for the long flag, frame processors use 19 (fits --black-and-white)
    private static final int OPTION_FLAG_WIDTH = 15;
    private static final int PROCESSOR_FLAG_WIDTH = 19;

    private final String[] args;
    private final List<FrameProcessorOption> frameProcessorOptions = new ArrayList<>();

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isHelpRequested() {
        return helpRequested;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    // read only, so nothing after parsing can reorder or drop an effect
    public List<FrameProcessorOption> getFrameProcessorOptions() {
        return Collections.unmodifiableList(frameProcessorOptions);
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    // public + static so tests can compare it to the spec without starting the player
    public static String helpText() {
        StringBuilder help = new StringBuilder();
        help.append("Usage: VideoPlayer [options] [video-file]\n");
        help.append("\n");
        help.append("Options:\n");
        addHelpLine(help, 'h', "--help", "Show this help message", OPTION_FLAG_WIDTH);
        addHelpLine(help, 'a', "--audio", "Play audio", OPTION_FLAG_WIDTH);
        addHelpLine(help, 'x', "--maximise", "Open the player maximised", OPTION_FLAG_WIDTH);
        addHelpLine(help, '1', "--monitor-1", "Open the player on display 1", OPTION_FLAG_WIDTH);
        addHelpLine(help, '2', "--monitor-2", "Open the player on display 2", OPTION_FLAG_WIDTH);
        help.append("\n");
        help.append("Frame processors:\n");
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            addHelpLine(help, option.shortFlag(), option.longFlag(), option.description(), PROCESSOR_FLAG_WIDTH);
        }
        help.append("\n");
        help.append("Frame processors are applied in command-line order and may be repeated.\n");
        help.append("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,\n");
        help.append("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.\n");
        return help.toString();
    }

    private static void addHelpLine(StringBuilder help, char shortFlag, String longFlag, String description,
            int flagWidth) {
        help.append(String.format("  -%c, %-" + flagWidth + "s%s\n", shortFlag, longFlag, description));
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                parseLongOption(arg);
            } else if (arg.startsWith("-") && arg.length() > 1) {
                parseShortOptions(arg);
            } else if (arg.startsWith("-")) {
                errorMessage = "Unknown option: " + arg; // just "-" on its own
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }

    private void parseLongOption(String arg) {
        switch (arg) {
            case "--help" -> helpRequested = true;
            case "--audio" -> audioRequested = true;
            case "--maximise" -> maximiseRequested = true;
            case "--monitor-1" -> setDisplayId(1);
            case "--monitor-2" -> setDisplayId(2);
            default -> FrameProcessorOption.fromLongFlag(arg).ifPresentOrElse(
                    frameProcessorOptions::add,
                    () -> errorMessage = "Unknown option: " + arg);
        }
    }

    // "-jfddn" is handled the same as "-j -f -d -d -n", one letter at a time left to right,
    // which is what keeps stacked effects in command-line order
    private void parseShortOptions(String arg) {
        for (int i = 1; i < arg.length(); i++) {
            char flag = arg.charAt(i);
            switch (flag) {
                case 'h' -> helpRequested = true;
                case 'a' -> audioRequested = true;
                case 'x' -> maximiseRequested = true;
                case '1' -> setDisplayId(1);
                case '2' -> setDisplayId(2);
                default -> FrameProcessorOption.fromShortFlag(flag).ifPresentOrElse(
                        frameProcessorOptions::add,
                        () -> errorMessage = "Unknown option: -" + flag);
            }
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.print(helpText());
    }
}