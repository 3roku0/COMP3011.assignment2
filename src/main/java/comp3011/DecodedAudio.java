/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Author: Zarni Htet Khaing (a1807729)
 */
package comp3011;

/**
 * A chunk of decoded sound plus the media time it belongs at. The decoder thread makes these and the
 * JavaFX thread plays them when the clock gets there. The bytes are a fresh copy (AudioPlayer.copySamples
 * makes a new array each time) so nothing else ever writes to them after its made.
 */
record DecodedAudio(long timestampUs, byte[] samples) {
}
