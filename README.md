# COMP3011 Assignment 2 - Java Local Project

Zarni Htet Khaing (a1807729)

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

## Running it
Effects are picked on the command line and get applied in the order you type them. You can repeat them, `-ss` gives two different scratch layers.

## Adding a new effect

1. Make a new `FrameProcessor` subclass.
2. Add one line for it in `FrameProcessorOption` (letter, long flag, help text, constructor).

Thats it. The parser, the help text and the model all read from that one table. Don't reuse `h a x 1 2`, those are taken. `CommandLineControllerTest` checks no two effects share a flag.

## How the threads work

The original player did everything on the JavaFX Application Thread. Decode, every effect, convert, display, audio and the GUI. With enough effects one core maxed out and the other cores did nothing.

Now its a production line. Each station is its own thread and they pass frames along through queues.
- **decoder** (`FrameDecoder`) - reads frames and sound from the file. Copies each frame into our own buffer then sends it on.
- **one thread per effect** (`FrameProcessorStage`) - each one does its one effect, in command line order.
- **converter** (`FrameConverterStage`) - turns the frame into a JavaFX `Image`.
- **JavaFX Application Thread** (`VideoPlayerModel`) - every pulse it writes any sound thats due and shows the next frame when its time. Never waits on the pipeline, only peeks at whats already arrived. So the window stays responsive even when its busy.

While effect 2 is on frame 10, effect 1 is already on frame 11 and the decoder is on frame 12. So the effects run at the same time on different cores. Each station only has to fit its own bit of work in the 41.7ms frame time, not the whole stack.

`PlaybackPipeline` builds all of this, and the model makes a new one each time playback starts or you seek.

## Why its correct

**What is shared between threads:** only the queues. Everything else belongs to one thread.

1. **A frame has one owner at a time.** Whoever last took it off a queue owns it. It gives it up by putting it on the next queue and never touches it again. So two threads never edit the same pixels.
2. **The queues are `BlockingQueue`s.** They're thread safe, and passing something through one is a happens-before. Everything the sender wrote before `put()` is visible to the receiver after `take()`. Thats why the next thread always sees the finished pixels and not half updated ones.
3. **The decoder copies every frame.** The FFmpeg grabber reuses the same buffer for every grab. Without a copy the next grab would write over a frame an effect is still working on (frame tearing). `FrameBufferPool` does the copy and reuses buffers so we don't make ~110MB of garbage a second.
4. **Each effect object is only used by one thread.** Bleeder, Jitterer and Mottler keep work buffers in their own fields. Two threads using the same one would wreck each others frames. Here each stage owns one effect object for its whole life, so none of the effects needed locks or changes. Repeated flags get separate objects too.
5. **Only the decoder thread uses the grabber while the pipeline runs.** FFmpeg isn't thread safe. On a seek the model stops the pipeline first (interrupt then join every thread), and only then moves the grabber. Same when closing.
6. **Old frames can't show up after a seek.** Each pipeline has its own queues, so frames from before the seek get thrown away with the old pipeline.
7. **All the playback state and GUI stuff stays on the JavaFX thread** like the original. Clock, pause, audio timing, the view. Worker threads never touch them. So the model needs no locks at all.

**Why it can't deadlock:**

- A worker only ever waits on its own input queue or its own output queue.
- The stations are in a straight line, so nobody can be waiting on each other in a circle.
- No locks are held while waiting.
- The JavaFX thread never waits on a worker. It only peeks and polls.
- The only waiting it does is `join()` in `stop()`. That always finishes because `interrupt()` wakes a thread stuck on a queue, and no worker ever waits for the JavaFX thread.

**Memory:** the queues are bounded (2 frames between stations, 8 waiting for display). If its paused or the display falls behind, the stations fill up and then just sleep. They don't decode the whole movie into memory.

## Stopping

Seek, stop, Esc, end of video, errors and closing the window all go through `closePlaybackResources()`. That stops the pipeline first. `stop()` interrupts every worker then joins them, so it doesn't return till they've actually ended. The threads are daemon threads too, as a backup so they can never keep the JVM alive.

If an effect throws, it gets sent down the line as a `Failure` message behind the frames before it. So the error shows on the JavaFX thread instead of dying quietly on a worker.

## Tests

`mvn package` / `mvn verify` run these:

- `CommandLineControllerTest` - exact help text, every short and long flag, stacking, repeats, bad options, exit codes.
- `FrameBufferPoolTest` - makes a copy, then writes over the "grabber" buffer like the next grab would, and checks the copy didn't change. Also checks buffers get reused.
- `FrameProcessorStageTest` - the main one for the threading:
  - runs every effect (plus some repeats) single threaded and through one thread per effect, and checks every pixel of every frame is the same
  - records which thread calls each effect, and checks each effect object only ever got called by one thread, in frame order
  - checks an exception on a worker comes out in order as a `Failure` instead of getting lost
  - every test also checks all the threads actually stop when interrupted

## Performance

On my MacBook Air (8 cores), the original single threaded player kept up with about 7 effects and went red (late frames) at 8. The multithreaded one plays all 10 effects plus frame numbers (`-pbmjdvywfsn`) with the headroom staying green, and java uses more than one core.

The line can only go as fast as its slowest station. One effect is a small part of the frame time so this is fine, but if you stack lots of effects you get more threads than cores and they start sharing.

## Use of AI

I used Claude (Anthropic) as a tutor and coding assistant for this assignment. I used it to learn the
Java and OS ideas behind this (threads, queues, happens-before, thread confinement, interrupt/join),
to plan the pipeline design, and to help me explain it in this README.

Most of the code started as drafts from Claude, which I built up one small step at a time. For each
step I went through what it changed and why, ran it and tested it on my own machine (unit tests, plus
checking pause, seek, mute, resize and closing with lots of effects stacked), and committed it on its
own so the history shows how it came together.
