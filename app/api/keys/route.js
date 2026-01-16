export const dynamic = 'force-dynamic';

import { keyStore } from '../../../lib/store.js';
import { NextResponse } from 'next/server';

export async function POST(request) {
  try {
    const { key, timestamp } = await request.json();
    if (!key || typeof key !== 'string') {
      return NextResponse.json({ error: 'Invalid key parameter' }, { status: 400 });
    }
    const keyData = keyStore.addKey(key, timestamp || Date.now());
    return NextResponse.json({ success: true, data: keyData });
  } catch (error) {
    console.error('Error processing key:', error);
    return NextResponse.json({ error: 'Internal server error' }, { status: 500 });
  }
}

export async function GET() {
  return NextResponse.json({ keys: keyStore.getKeys() });
}
