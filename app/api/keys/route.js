import { keyStore } from '../../../lib/store.js';
import { NextResponse } from 'next/server';

export async function POST(request) {
  try {
    const body = await request.json();
    const { key, timestamp } = body;

    if (!key || typeof key !== 'string') {
      return NextResponse.json(
        { error: 'Invalid key parameter' },
        { status: 400 }
      );
    }

    const keyTimestamp = timestamp || Date.now();
    const keyData = keyStore.addKey(key, keyTimestamp);

    return NextResponse.json(
      { success: true, data: keyData },
      { status: 200 }
    );
  } catch (error) {
    console.error('Error processing key:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
}

export async function GET() {
  const keys = keyStore.getKeys();
  return NextResponse.json({ keys }, { status: 200 });
}
