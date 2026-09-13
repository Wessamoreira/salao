/*
 * Prévia da casca, sem backend.
 *
 *   npm run previa:casca   →   http://localhost:5174/preview/casca.html
 *
 * As capacidades entram direto no cache do React Query: é o mesmo retrato que
 * `/me/capabilities` devolve, e assim a casca roda exatamente como roda em produção.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { Casca } from '../src/layout/Casca';
import { chaveDasCapacidades, type Capacidades } from '../src/api/capacidades';
import '../src/estilo/global.css';

const CAPACIDADES: Capacidades = {
  usuarioId: 'u1',
  nome: 'Wesley Moreira',
  email: 'wesley@salao.test',
  perfil: 'GERENTE',
  estabelecimento: { id: 'e1', nome: 'Studio Bertoldo', timezone: 'America/Sao_Paulo',
    moeda: 'BRL' },
  permissoes: ['agenda:read:all', 'comanda:open', 'financeiro:read:all'],
  grupos: [
    { id: 'operacao', rotulo: 'Operação' },
    { id: 'gestao', rotulo: 'Gestão' },
    { id: 'sistema', rotulo: 'Sistema' },
  ],
  menus: [
    { id: 'agenda', rotulo: 'Agenda', rota: '/agenda', icone: 'calendario', grupo: 'operacao' },
    { id: 'atendimento', rotulo: 'Atendimento', rota: '/comandas', icone: 'tesoura',
      grupo: 'operacao' },
    { id: 'clientes', rotulo: 'Clientes', rota: '/clientes', icone: 'pessoas',
      grupo: 'operacao' },
    { id: 'estoque', rotulo: 'Estoque', rota: '/estoque', icone: 'caixa', grupo: 'gestao' },
    { id: 'financeiro', rotulo: 'Financeiro', rota: '/financeiro', icone: 'cifrao',
      grupo: 'gestao' },
    { id: 'configuracao', rotulo: 'Configurações', rota: '/config', icone: 'engrenagem',
      grupo: 'sistema' },
  ],
  flags: {},
  limites: {},
  mfaAtivo: true,
  mfaObrigatorio: true,
};

const cliente = new QueryClient({
  defaultOptions: { queries: { retry: false, staleTime: Infinity } },
});
cliente.setQueryData(chaveDasCapacidades, CAPACIDADES);

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={cliente}>
      <MemoryRouter initialEntries={['/comandas']}>
        <Routes>
          <Route element={<Casca />}>
            {CAPACIDADES.menus.map((m) => (
              <Route key={m.id} path={m.rota} element={
                <div style={{ padding: 24, color: 'var(--tinta-fraca)', fontSize: 14 }}>
                  Área de trabalho. A casca é o que está sendo avaliado aqui.
                </div>
              } />
            ))}
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  </StrictMode>,
);
