export const dynamic = "force-dynamic";

import { NextResponse } from "next/server";
import { webrtcStore } from "../../../../lib/webrtcStore.js";

export async function POST(request) {
  try {
    const body = await request.json().catch(() => ({}));
    const session = webrtcStore.createSession({
      screenWidth: body?.screenWidth,
      screenHeight: body?.screenHeight,
    });

    // Pas de sécurité: on renvoie quand même un champ "secret" vide pour compat Android.
    return NextResponse.json({
      sessionId: session.sessionId,
      secret: "",
      screenWidth: session.screenWidth,
      screenHeight: session.screenHeight,
    });
  } catch (e) {
    return NextResponse.json({ error: "internal_error" }, { status: 500 });
  }
}

