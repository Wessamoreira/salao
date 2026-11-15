import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { SegundoFator } from './SegundoFator';

/*
 * RT-INF-017 — ativar o segundo fator escaneando, e não digitando 32 caracteres.
 */

const URI = 'otpauth://totp/Salao%20Wesley%3Adono%40salao.local'
  + '?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Salao%20Wesley&digits=6&period=30';

vi.mock('../api/http', () => ({
  api: async (caminho: string) => {
    if (caminho.includes('/me/capabilities')) return { perfil: 'ADMIN', menus: [], grupos: [] };
    if (caminho.includes('/mfa/inscrever')) {
      return { segredo: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', uriOtpauth: URI };
    }
    throw new Error(`rota inesperada: ${caminho}`);
  },
  ErroDaApi: class extends Error {},
}));
vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn(), useLocation: () => ({ state: null }) }));
vi.mock('../sessao/sessao', () => ({ definirTokenAtual: vi.fn() }));

async function comecar() {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={cliente}><SegundoFator /></QueryClientProvider>);
  await userEvent.click(await screen.findByRole('button', { name: 'Começar' }));
}

describe('ativar o segundo fator', () => {
  it('mostra o QR gerado aqui, a partir do endereço que o servidor mandou', async () => {
    await comecar();

    const qr = await screen.findByRole('img', {
      name: 'Código QR para configurar o aplicativo autenticador',
    });
    // Desenhado como caminho SVG: nada de HTML por string, nada de serviço externo.
    expect(qr.querySelector('path')?.getAttribute('d')).toMatch(/^M\d/);
  });

  it('no celular, abre o autenticador direto pelo endereço otpauth', async () => {
    await comecar();

    const abrir = await screen.findByRole('link', { name: 'Abrir no aplicativo autenticador' });
    expect(abrir).toHaveAttribute('href', URI);
  });

  it('quem precisar digitar recebe a chave em grupos de quatro', async () => {
    await comecar();

    await userEvent.click(await screen.findByText('Não consegue escanear? Use a chave'));
    expect(screen.getByText('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP')).toBeInTheDocument();
  });
});
