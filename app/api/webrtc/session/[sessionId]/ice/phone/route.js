export const dynamic = "force-dynamic";

import { NextResponse } from "next/server";
import { webrtcStore } from "../../../../../../../lib/webrtcStore.js";

export async function POST(request, { params }) {
  const sessionId = params?.sessionId;
  if (!sessionId) return NextResponse.json({ error: "missing_sessionId" }, { status: 400 });
  try {
    const candidate = await request.json();
    const session = webrtcStore.addIce(sessionId, "phone", candidate);
    if (!session) return NextResponse.json({ error: "not_found" }, { status: 404 });
    return NextResponse.json({ ok: true });
  } catch (e) {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }
}

export async function GET(_request, { params }) {
  const sessionId = params?.sessionId;
  if (!sessionId) return NextResponse.json({ error: "missing_sessionId" }, { status: 400 });
  const session = webrtcStore.getSession(sessionId);
  if (!session) return NextResponse.json({ error: "not_found" }, { status: 404 });
  return NextResponse.json({ candidates: session.ice.phone });
}

