package dev.huya.connect.sdk;

public interface HuyaListener {
    default void onRawFrame(RawFrame frame) { }
    /** Each push is delivered once as Message, then once as a Business event when recognized. */
    default void onEvent(HuyaEvent event) { }
    default void onStatus(HuyaStatus status) { }
    default void onError(Throwable error) { }
}
