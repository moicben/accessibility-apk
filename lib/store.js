import { EventEmitter } from 'events';

class KeyStore extends EventEmitter {
  constructor() {
    super();
    this.keys = [];
    this.maxKeys = 1000; // Limite pour éviter une consommation mémoire excessive
  }

  addKey(key, timestamp) {
    const keyData = {
      key,
      timestamp,
      id: Date.now() + Math.random()
    };
    
    this.keys.push(keyData);
    
    // Limiter la taille du tableau
    if (this.keys.length > this.maxKeys) {
      this.keys.shift();
    }
    
    // Émettre l'événement pour les clients SSE
    this.emit('newKey', keyData);
    
    return keyData;
  }

  getKeys() {
    return this.keys;
  }

  clear() {
    this.keys = [];
    this.emit('clear');
  }
}

// Instance singleton
export const keyStore = new KeyStore();
