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
        const data = JSON.stringify({ type: 'newKey', data: keyData });
        try {
          controller.enqueue(encoder.encode(`data: ${data}\n\n`));
        } catch (error) {
          // Le client s'est déconnecté
          keyStore.removeListener('newKey', onNewKey);
        }
      };

      keyStore.on('newKey', onNewKey);

      // Nettoyer lors de la fermeture
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
