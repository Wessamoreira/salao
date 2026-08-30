/**
 * Marcador honesto de tela que ainda não existe.
 *
 * Diz qual rotina a entrega, em vez de fingir conteúdo: uma tela com dados falsos parece
 * pronta e engana quem revisa — inclusive o dono do salão, numa demonstração.
 */
export function EmBreve({ titulo, rotina }: { titulo: string; rotina: string }) {
  return (
    <div style={{ padding: 'var(--e-10)' }}>
      <h1 style={{ fontFamily: 'var(--fonte-identidade)', fontSize: 'var(--t-30)',
        margin: 0, fontWeight: 400, letterSpacing: '-0.01em' }}>{titulo}</h1>
      <p style={{ fontSize: 'var(--t-14)', color: 'var(--tinta-fraca)',
        margin: 'var(--e-2) 0 0' }}>
        Entra em <code style={{ fontFamily: 'var(--fonte-mono)', fontSize: 'var(--t-12)',
          background: 'var(--papel-afundado)', padding: '1px 5px',
          borderRadius: 'var(--raio-1)' }}>{rotina}</code>.
      </p>
    </div>
  );
}
