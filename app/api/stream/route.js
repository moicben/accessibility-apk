export const dynamic = 'force-dynamic';

import { keyStore } from '../../../lib/store.js';

export async function GET(request) {
  const encoder = new TextEncoder();
  
  // Créer un stream SSE
  const stream = new ReadableStream({
    start(controller) {
      // Envoyer les touches existantes au démarrage
      const existingKeys = keyStore.getKeys();
      const initialData = JSON.stringify({ type: 'initial', keys: existingKeys });
      controller.enqueue(encoder.encode(`data: ${initialData}\n\n`));

      // Écouter les nouvelles touches
      const onNewKey = (keyData) => {
        try {
          const data = JSON.stringify({ type: 'newKey', data: keyData });
          controller.enqueue(encoder.encode(`data: ${data}\n\n`));
        } catch (error) {
          keyStore.removeListener('newKey', onNewKey);
        }
      };

      keyStore.on('newKey', onNewKey);
      request.signal.addEventListener('abort', () => {
        keyStore.removeListener('newKey', onNewKey);
        controller.close();
      });
    }
  });

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',
    },
  });
}
