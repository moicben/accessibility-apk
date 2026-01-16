'use client';

import { useEffect, useMemo, useRef, useState } from 'react';

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

export default function ViewerPage({ params }) {
  const sessionId = params?.sessionId;
  const videoRef = useRef(null);
  const pcRef = useRef(null);
  const dcRef = useRef(null);
  const pollingRef = useRef(false);
  const lastIceCountRef = useRef(0);

  const [status, setStatus] = useState('init');
  const [dcState, setDcState] = useState('closed');
  const [text, setText] = useState('');

  const iceServers = useMemo(
    () => [{ urls: ['stun:stun.l.google.com:19302'] }],
    []
  );

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;

    async function start() {
      setStatus('Connexion...');
      const pc = new RTCPeerConnection({ iceServers });
      pcRef.current = pc;

      pc.ontrack = (ev) => {
        const stream = ev.streams?.[0];
        if (videoRef.current && stream) {
          videoRef.current.srcObject = stream;
        }
      };

      pc.ondatachannel = (ev) => {
        dcRef.current = ev.channel;
        setDcState(ev.channel.readyState);
        ev.channel.onopen = () => setDcState(ev.channel.readyState);
        ev.channel.onclose = () => setDcState(ev.channel.readyState);
        ev.channel.onmessage = () => {};
      };

      pc.onicecandidate = async (ev) => {
        if (!ev.candidate) return;
        try {
          await fetch(`/api/webrtc/session/${sessionId}/ice/web`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              candidate: ev.candidate.candidate,
              sdpMid: ev.candidate.sdpMid,
              sdpMLineIndex: ev.candidate.sdpMLineIndex,
            }),
          });
        } catch {}
      };

      // 1) récupérer offer
      let offer = null;
      for (let i = 0; i < 120; i++) {
        const offerRes = await fetch(`/api/webrtc/session/${sessionId}/offer`, { cache: 'no-store' });
        if (offerRes.status === 200) {
          offer = await offerRes.json();
          break;
        }
        if (offerRes.status === 404) {
          setStatus('Session introuvable (404)');
          return;
        }
        await sleep(500);
      }
      if (!offer) {
        setStatus('Offer non reçue (timeout)');
        return;
      }
      await pc.setRemoteDescription(offer);

      // 2) créer answer
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      await fetch(`/api/webrtc/session/${sessionId}/answer`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(answer),
      });

      setStatus('Answer envoyée. En attente du flux…');

      // 3) poll ICE téléphone
      pollingRef.current = true;
      while (!cancelled && pollingRef.current) {
        try {
          const res = await fetch(`/api/webrtc/session/${sessionId}/ice/phone`);
          if (res.status === 200) {
            const { candidates } = await res.json();
            const startIdx = lastIceCountRef.current;
            for (let i = startIdx; i < (candidates?.length || 0); i++) {
              const c = candidates[i];
              if (c?.candidate && c?.sdpMid != null) {
                await pc.addIceCandidate({
                  candidate: c.candidate,
                  sdpMid: c.sdpMid,
                  sdpMLineIndex: c.sdpMLineIndex,
                });
              }
            }
            lastIceCountRef.current = candidates?.length || 0;
          }
        } catch {}
        await sleep(700);
      }
    }

    start().catch((e) => setStatus(`Erreur: ${e?.message || String(e)}`));

    return () => {
      cancelled = true;
      pollingRef.current = false;
      try {
        dcRef.current?.close();
      } catch {}
      try {
        pcRef.current?.close();
      } catch {}
      dcRef.current = null;
      pcRef.current = null;
    };
  }, [sessionId, iceServers]);

  function sendControl(payload) {
    const dc = dcRef.current;
    if (!dc || dc.readyState !== 'open') return;
    dc.send(JSON.stringify(payload));
  }

  function videoToNorm(ev) {
    const rect = ev.currentTarget.getBoundingClientRect();
    const x = (ev.clientX - rect.left) / rect.width;
    const y = (ev.clientY - rect.top) / rect.height;
    return { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) };
  }

  const dragRef = useRef(null);

  return (
    <main style={{ padding: 16, fontFamily: 'system-ui, -apple-system, sans-serif' }}>
      <h2 style={{ margin: 0 }}>Viewer</h2>
      <div style={{ color: '#666', marginTop: 6 }}>
        Session: <code>{sessionId}</code> — Status: <code>{status}</code> — DataChannel:{' '}
        <code>{dcState}</code>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 16, marginTop: 16 }}>
        <div>
          <video
            ref={videoRef}
            autoPlay
            playsInline
            muted
            style={{ width: '100%', background: '#000', borderRadius: 8 }}
            onClick={(ev) => {
              const { x, y } = videoToNorm(ev);
              sendControl({ type: 'tap', x, y });
            }}
            onMouseDown={(ev) => {
              dragRef.current = { ...videoToNorm(ev), t0: performance.now() };
            }}
            onMouseUp={(ev) => {
              const start = dragRef.current;
              if (!start) return;
              const end = videoToNorm(ev);
              const dt = performance.now() - start.t0;
              dragRef.current = null;
              const dx = Math.abs(end.x - start.x);
              const dy = Math.abs(end.y - start.y);
              if (dx < 0.02 && dy < 0.02) return; // click already handled
              sendControl({ type: 'swipe', x1: start.x, y1: start.y, x2: end.x, y2: end.y, durationMs: Math.max(120, Math.min(1500, dt)) });
            }}
          />
          <div style={{ marginTop: 10, color: '#666', fontSize: 12 }}>
            Click = tap. Drag = swipe. (Très simple pour l’instant.)
          </div>
        </div>

        <div style={{ border: '1px solid #eee', borderRadius: 8, padding: 12 }}>
          <h3 style={{ marginTop: 0 }}>Contrôles</h3>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <button onClick={() => sendControl({ type: 'global', action: 'back' })}>Back</button>
            <button onClick={() => sendControl({ type: 'global', action: 'home' })}>Home</button>
            <button onClick={() => sendControl({ type: 'global', action: 'recents' })}>Recents</button>
          </div>

          <div style={{ marginTop: 12 }}>
            <div style={{ fontSize: 12, color: '#666', marginBottom: 6 }}>Texte</div>
            <textarea
              value={text}
              onChange={(e) => setText(e.target.value)}
              rows={3}
              style={{ width: '100%' }}
              placeholder="Texte à injecter"
            />
            <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
              <button
                onClick={() => {
                  sendControl({ type: 'text', text });
                }}
                disabled={!text}
              >
                Envoyer
              </button>
              <button onClick={() => setText('')}>Effacer</button>
            </div>
          </div>
        </div>
      </div>
    </main>
  );
}

