/*
 * Prévia visual dos acessos, sem backend.
 *
 * Quatro contas que cobrem exatamente o que a tela existe para mostrar: a própria conta de quem
 * olha (que não se mexe), uma gerente que ainda vai configurar o segundo fator, uma recepção
 * criada e nunca usada — sinal de que a senha não chegou — e um acesso já retirado.
 *
 *   npm run previa:acessos   →   http://localhost:5174/preview/acessos.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { Acessos } from '../src/telas/acessos/Acessos';
import '../src/estilo/global.css';

const EU = 'u1';

const USUARIOS = [
  { id: EU, nome: 'Marilda Souza', email: 'marilda@studiomarilda.com.br', telefone: null,
    perfil: 'ADMIN', ativo: true, mfaAtivo: true,
    ultimoAcessoEm: new Date().toISOString() },
  { id: 'u2', nome: 'Ana Ribeiro', email: 'ana@studiomarilda.com.br', telefone: '5511987654321',
    perfil: 'GERENTE', ativo: true, mfaAtivo: false,
    ultimoAcessoEm: new Date(Date.now() - 3 * 86400000).toISOString() },
  { id: 'u3', nome: 'Bia Nogueira', email: 'bia@studiomarilda.com.br', telefone: null,
    perfil: 'RECEPCAO', ativo: true, mfaAtivo: false, ultimoAcessoEm: null },
  { id: 'u4', nome: 'Nice Fernandes', email: 'nice@studiomarilda.com.br', telefone: null,
    perfil: 'PROFISSIONAL', ativo: false, mfaAtivo: false,
    ultimoAcessoEm: '2026-04-02T12:00:00Z' },
];

const PERFIS = [
  { perfil: 'ADMIN', rotulo: 'Administradora', exigeMfa: true,
    resumo: 'A dona do salão. Faz tudo, inclusive criar acesso e mudar a configuração.',
    podeFazer: ['ver a agenda de todo o salão', 'fechar comanda e receber',
      'dar desconto sem limite', 'ver o dinheiro do salão inteiro',
      'fechar o período e apurar comissão', 'criar acesso e mudar perfil',
      'mudar a configuração do salão e o cardápio'] },
  { perfil: 'GERENTE', rotulo: 'Gerente', exigeMfa: true,
    resumo: 'Opera o dia e vê o dinheiro do salão — mas não cria acesso nem mexe na configuração.',
    podeFazer: ['ver a agenda de todo o salão', 'dar desconto sem limite',
      'ver o dinheiro do salão inteiro', 'fechar o período e apurar comissão'] },
  { perfil: 'RECEPCAO', rotulo: 'Recepção', exigeMfa: false,
    resumo: 'O balcão: agenda de todo mundo, comanda e cliente. Não vê comissão nem custo.',
    podeFazer: ['ver a agenda de todo o salão', 'marcar e desmarcar para qualquer profissional',
      'abrir comanda', 'fechar comanda e receber', 'dar desconto de até 10%',
      'ver o estoque', 'cadastrar e corrigir cliente'] },
  { perfil: 'PROFISSIONAL', rotulo: 'Profissional', exigeMfa: false,
    resumo: 'Vê a própria agenda e o próprio extrato, e nada de mais ninguém.',
    podeFazer: ['ver a própria agenda', 'marcar e desmarcar na própria agenda',
      'ver o próprio extrato de comissão', 'ver a lista de clientes'] },
  { perfil: 'PAINEL', rotulo: 'Painel de parede', exigeMfa: false,
    resumo: 'Conta de aparelho: mostra a agenda numa tela pendurada e não toca em nada.',
    podeFazer: ['ver a agenda de todo o salão'] },
];

window.fetch = async (entrada: RequestInfo | URL) => {
  const url = String(entrada);
  await new Promise((seguir) => setTimeout(seguir, 300));
  let corpo: unknown = {};
  if (url.includes('/me/capabilities')) {
    corpo = { usuarioId: EU, nome: 'Marilda Souza', email: 'marilda@studiomarilda.com.br',
      perfil: 'ADMIN',
      estabelecimento: { id: 'e1', nome: 'Studio Marilda',
        timezone: 'America/Sao_Paulo', moeda: 'BRL' },
      permissoes: ['usuario:manage'], grupos: [], menus: [] };
  } else if (url.includes('/v1/perfis')) {
    corpo = PERFIS;
  } else if (url.includes('/v1/usuarios')) {
    corpo = USUARIOS;
  }
  return new Response(JSON.stringify(corpo), {
    status: 200, headers: { 'Content-Type': 'application/json' } });
};

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <MemoryRouter initialEntries={['/acessos']}>
      <QueryClientProvider client={new QueryClient()}>
        <div style={{ minHeight: '100dvh', background: 'var(--papel-fundo)' }}>
          <Acessos />
        </div>
      </QueryClientProvider>
    </MemoryRouter>
  </StrictMode>,
);
