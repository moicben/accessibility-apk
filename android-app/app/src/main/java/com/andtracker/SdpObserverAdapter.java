package com.andtracker;

import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

/**
 * Convenience adapter: override only needed callbacks.
 */
public class SdpObserverAdapter implements SdpObserver {
    @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
    @Override public void onSetSuccess() {}
    @Override public void onCreateFailure(String s) {}
    @Override public void onSetFailure(String s) {}
}

