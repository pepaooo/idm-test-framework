package com.idm.test.engine.spi;

/**
 * One entry of a step's waiting history: a try under {@code wait:} whose observation differed
 * from the try before it (the first try always counts).
 *
 * @param attempt       the try's number, from 1
 * @param elapsedMillis time since the wait began when the try ended
 * @param message       what the try observed, cut to at most {@link #MAX_MESSAGE} characters; may be null
 */
public record WaitObservation(int attempt, long elapsedMillis, String message) {

    /** The longest message an entry keeps. */
    public static final int MAX_MESSAGE = 300;

    public WaitObservation {
        if (message != null && message.length() > MAX_MESSAGE) {
            message = message.substring(0, MAX_MESSAGE - 1) + "…";
        }
    }
}
