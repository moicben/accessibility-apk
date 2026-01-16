import { EventEmitter } from "events";

function randomId(len = 10) {
  const alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
  let out = "";
  for (let i = 0; i < len; i++) out += alphabet[Math.floor(Math.random() * alphabet.length)];
  return out;
}

class WebRtcStore extends EventEmitter {
  constructor() {
    super();
    this.sessions = new Map();
    this.ttlMs = 1000 * 60 * 60; // 1h
  }

  _cleanup() {
    const now = Date.now();
    for (const [id, s] of this.sessions.entries()) {
      if (now - s.createdAt > this.ttlMs) this.sessions.delete(id);
    }
  }

  createSession({ screenWidth, screenHeight } = {}) {
    this._cleanup();
    const sessionId = randomId(12);
    const session = {
      sessionId,
      createdAt: Date.now(),
      screenWidth: Number(screenWidth) || null,
      screenHeight: Number(screenHeight) || null,
      offer: null,
      answer: null,
      ice: {
        phone: [],
        web: [],
      },
    };
    this.sessions.set(sessionId, session);
    return session;
  }

  getSession(sessionId) {
    this._cleanup();
    return this.sessions.get(sessionId) || null;
  }

  setOffer(sessionId, offer) {
    const s = this.getSession(sessionId);
    if (!s) return null;
    s.offer = offer;
    this.emit("offer", { sessionId });
    return s;
  }

  setAnswer(sessionId, answer) {
    const s = this.getSession(sessionId);
    if (!s) return null;
    s.answer = answer;
    this.emit("answer", { sessionId });
    return s;
  }

  addIce(sessionId, side, candidate) {
    const s = this.getSession(sessionId);
    if (!s) return null;
    if (side !== "phone" && side !== "web") return null;
    s.ice[side].push(candidate);
    this.emit("ice", { sessionId, side });
    return s;
  }
}

export const webrtcStore = new WebRtcStore();

