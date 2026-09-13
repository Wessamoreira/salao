/*
 * Os ícones da casca, num lugar só.
 *
 * Traço de 1.75 em caixa de 24: é o peso que acompanha a Archivo em 500/600 sem pesar
 * mais que o rótulo ao lado. Ícone mais fino some no menu escuro; mais grosso vira o
 * elemento dominante de uma linha cujo assunto é a palavra.
 */
export type NomeDeIcone =
  | 'calendario' | 'tesoura' | 'pessoas' | 'caixa' | 'cifrao' | 'engrenagem'
  | 'menu' | 'fechar' | 'sair' | 'busca' | 'recolher' | 'expandir'
  | 'chevron' | 'escudo' | 'seta';

export function Icone({ nome, tamanho = 18 }: { nome: string; tamanho?: number }) {
  const comum = {
    width: tamanho, height: tamanho, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 1.75, strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const, 'aria-hidden': true,
  };

  switch (nome) {
    case 'calendario':
      return <svg {...comum}><path d="M6 3v3M18 3v3M4 9h16" />
        <rect x="3" y="5" width="18" height="16" rx="2" /><path d="M8 13h3v3H8z" /></svg>;
    case 'tesoura':
      return <svg {...comum}><circle cx="6" cy="7" r="3" /><circle cx="6" cy="17" r="3" />
        <path d="m8.7 8.4 11.3 6.1M8.7 15.6 20 9.5" /></svg>;
    case 'pessoas':
      return <svg {...comum}><circle cx="9" cy="8" r="3" />
        <path d="M3.5 20v-1.5A4.5 4.5 0 0 1 8 14h2a4.5 4.5 0 0 1 4.5 4.5V20" />
        <path d="M15.5 5.3a3 3 0 0 1 0 5.4M17 14a4.5 4.5 0 0 1 3.5 4.4V20" /></svg>;
    case 'caixa':
      return <svg {...comum}><path d="m4 7 8-4 8 4-8 4-8-4Z" />
        <path d="M4 7v10l8 4 8-4V7M12 11v10" /></svg>;
    case 'cifrao':
      return <svg {...comum}><circle cx="12" cy="12" r="9" />
        <path d="M15.5 8.5c-.8-.7-2-1-3.3-1-1.8 0-3.2.9-3.2 2.3 0 3.4 6.3 1.7 6.3 5.1 0 1.4-1.4 2.4-3.3 2.4-1.5 0-2.9-.5-3.8-1.3M12 5.5v13" /></svg>;
    case 'engrenagem':
      return <svg {...comum}><circle cx="12" cy="12" r="3" />
        <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1a1.7 1.7 0 0 0 1.9.3A1.7 1.7 0 0 0 10 3v-.2h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z" /></svg>;
    case 'menu':
      return <svg {...comum}><path d="M4 7h16M4 12h16M4 17h16" /></svg>;
    case 'fechar':
      return <svg {...comum}><path d="m6 6 12 12M18 6 6 18" /></svg>;
    case 'sair':
      return <svg {...comum}><path d="M10 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4M14 8l4 4-4 4M9 12h9" /></svg>;
    case 'busca':
      return <svg {...comum}><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4 4" /></svg>;
    case 'recolher':
      return <svg {...comum}><rect x="3" y="4" width="18" height="16" rx="2" />
        <path d="M10 4v16M7.5 9.5 5.5 12l2 2.5" /></svg>;
    case 'expandir':
      return <svg {...comum}><rect x="3" y="4" width="18" height="16" rx="2" />
        <path d="M10 4v16m-3.5-4.5L8.5 12l-2-2.5" /></svg>;
    case 'chevron':
      return <svg {...comum}><path d="m8 10 4 4 4-4" /></svg>;
    case 'seta':
      return <svg {...comum}><path d="M5 12h14M13 6l6 6-6 6" /></svg>;
    case 'escudo':
      return <svg {...comum}><path d="M12 3 5 6v5.5c0 4 3 7.6 7 9.5 4-1.9 7-5.5 7-9.5V6l-7-3Z" />
        <path d="m9 12 2 2 4-4" /></svg>;
    default:
      return <svg {...comum}><circle cx="12" cy="12" r="8" /></svg>;
  }
}
