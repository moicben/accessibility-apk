package com.andtracker;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import android.media.projection.MediaProjection;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebRTC streaming (screen capture) + control channel receiver (DataChannel).
 */
public final class WebRtcStreamer {
    private static final String TAG = "WebRtcStreamer";

    public interface Events {
        void onSessionCreated(String sessionId, String secret);
        void onStatus(String status);
        void onFatalError(String error);
    }

    private final Context appContext;
    private final SignalingClient signaling;
    private final Intent mediaProjectionPermissionData;
    private final int screenWidth;
    private final int screenHeight;
    private final Events events;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private EglBase eglBase;
    private VideoCapturer capturer;
    private VideoTrack videoTrack;
    private DataChannel controlChannel;

    private String sessionId;
    private String secret;

    public WebRtcStreamer(
            Context appContext,
            String signalingBaseUrl,
            Intent mediaProjectionPermissionData,
            int screenWidth,
            int screenHeight,
            Events events
    ) {
        this.appContext = appContext.getApplicationContext();
        this.signaling = new SignalingClient(signalingBaseUrl);
        this.mediaProjectionPermissionData = mediaProjectionPermissionData;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.events = events;
    }

    public void start() {
        new Thread(this::startInternal, "webrtc-start").start();
    }

    public void stop() {
        stopped.set(true);
        try {
            if (controlChannel != null) controlChannel.close();
        } catch (Throwable ignored) {}
        try {
            if (peerConnection != null) peerConnection.close();
        } catch (Throwable ignored) {}
        try {
            if (capturer != null) capturer.dispose();
        } catch (Throwable ignored) {}
        try {
            if (factory != null) factory.dispose();
        } catch (Throwable ignored) {}
        try {
            if (eglBase != null) eglBase.release();
        } catch (Throwable ignored) {}
    }

    private void startInternal() {
        try {
            postStatus("Création session...");
            JSONObject created = signaling.createSession(screenWidth, screenHeight);
            sessionId = created.optString("sessionId", "");
            secret = created.optString("secret", "");
            if (sessionId.isEmpty()) {
                throw new IllegalStateException("Réponse createSession invalide: " + created);
            }
            postSessionCreated(sessionId, secret);

            initPeerConnection();
            createAndSendOffer();
            pollForAnswerAndRemoteIce();
        } catch (Exception e) {
            Log.e(TAG, "Fatal", e);
            postFatal("Erreur WebRTC: " + e.getMessage());
        }
    }

    private void initPeerConnection() throws Exception {
        postStatus("Initialisation WebRTC...");

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        );

        eglBase = EglBase.create();
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();

        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), /* enableIntelVp8Encoder */ true, /* enableH264HighProfile */ true
        );
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        // Screen capture -> video track
        VideoSource videoSource = factory.createVideoSource(/* isScreencast= */ true);
        SurfaceTextureHelper sth = SurfaceTextureHelper.create("screen-capture", eglBase.getEglBaseContext());
        capturer = new ScreenCapturerAndroid(mediaProjectionPermissionData, new MediaProjectionCallback());
        capturer.initialize(sth, appContext, videoSource.getCapturerObserver());
        capturer.startCapture(screenWidth, screenHeight, 30);

        videoTrack = factory.createVideoTrack("screen", videoSource);
        videoTrack.setEnabled(true);

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        // Public STUN default (TURN will be injected later via server/config)
        iceServers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        );

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerObserver());
        if (peerConnection == null) {
            throw new IllegalStateException("createPeerConnection a retourné null");
        }

        peerConnection.addTrack(videoTrack, Arrays.asList("stream"));

        // Control channel (created by phone; browser will receive ondatachannel)
        DataChannel.Init init = new DataChannel.Init();
        init.ordered = true;
        controlChannel = peerConnection.createDataChannel("control", init);
        controlChannel.registerObserver(new ControlChannelObserver());
    }

    private void createAndSendOffer() throws Exception {
        postStatus("Création offer...");

        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        SdpObserverAdapter observer = new SdpObserverAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserverAdapter(), sdp);
                try {
                    JSONObject offer = new JSONObject();
                    offer.put("type", sdp.type.canonicalForm());
                    offer.put("sdp", sdp.description);
                    signaling.publishOffer(sessionId, secret, offer);
                    postStatus("Offer publiée, en attente answer...");
                } catch (Exception e) {
                    postFatal("Erreur publishOffer: " + e.getMessage());
                }
            }
        };

        peerConnection.createOffer(observer, constraints);
    }

    private void pollForAnswerAndRemoteIce() {
        new Thread(() -> {
            long lastIceCount = 0;
            while (!stopped.get()) {
                try {
                    // Answer
                    JSONObject answer = signaling.fetchAnswer(sessionId, secret);
                    if (answer != null && answer.optString("sdp", "").length() > 0) {
                        String sdp = answer.optString("sdp", "");
                        String type = answer.optString("type", "answer");
                        SessionDescription remote = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp);
                        peerConnection.setRemoteDescription(new SdpObserverAdapter(), remote);
                        postStatus("Answer reçue");
                        // Once remote set, keep polling ICE but less often
                    }

                    // ICE from web
                    org.json.JSONArray cands = signaling.fetchRemoteIce(sessionId, secret);
                    if (cands != null && cands.length() > lastIceCount) {
                        for (int i = (int) lastIceCount; i < cands.length(); i++) {
                            JSONObject c = cands.getJSONObject(i);
                            String candidate = c.optString("candidate", null);
                            String sdpMid = c.optString("sdpMid", null);
                            int sdpMLineIndex = c.optInt("sdpMLineIndex", 0);
                            if (candidate != null && sdpMid != null) {
                                peerConnection.addIceCandidate(new IceCandidate(sdpMid, sdpMLineIndex, candidate));
                            }
                        }
                        lastIceCount = cands.length();
                    }

                    Thread.sleep(700);
                } catch (Exception ignored) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
            }
        }, "webrtc-poll").start();
    }

    private void postSessionCreated(String sessionId, String secret) {
        mainHandler.post(() -> events.onSessionCreated(sessionId, secret));
    }

    private void postStatus(String status) {
        Log.d(TAG, status);
        mainHandler.post(() -> events.onStatus(status));
    }

    private void postFatal(String error) {
        Log.e(TAG, error);
        mainHandler.post(() -> events.onFatalError(error));
    }

    private final class PeerObserver implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {}
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {}
        @Override public void onIceCandidate(IceCandidate candidate) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("candidate", candidate.sdp);
                payload.put("sdpMid", candidate.sdpMid);
                payload.put("sdpMLineIndex", candidate.sdpMLineIndex);
                signaling.publishIce(sessionId, secret, payload);
            } catch (Exception e) {
                Log.e(TAG, "publishIce failed", e);
            }
        }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(org.webrtc.MediaStream stream) {}
        @Override public void onRemoveStream(org.webrtc.MediaStream stream) {}
        @Override public void onDataChannel(DataChannel dc) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onAddTrack(RtpReceiver receiver, org.webrtc.MediaStream[] mediaStreams) {}
    }

    private final class ControlChannelObserver implements DataChannel.Observer {
        @Override
        public void onBufferedAmountChange(long previousAmount) {}

        @Override
        public void onStateChange() {
            postStatus("Control channel: " + controlChannel.state());
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            try {
                if (buffer.binary) return;
                java.nio.ByteBuffer data = buffer.data;
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                String msg = new String(bytes, StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(msg);
                // Forward to Accessibility layer
                RemoteControlBridge.handleControlMessage(appContext, json);
            } catch (Exception e) {
                Log.e(TAG, "Control message parse failed", e);
            }
        }
    }

    private static final class MediaProjectionCallback extends MediaProjection.Callback {
        // no-op for now
    }
}

