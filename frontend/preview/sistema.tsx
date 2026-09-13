/*
 * Prévia do SISTEMA — a casca com as telas reais dentro, sem backend.
 *
 *   npm run previa:sistema   →   http://localhost:5174/preview/sistema.html
 *
 * As prévias existentes julgam uma tela por vez, e é assim que cinco telas acabam com
 * cinco cabeçalhos diferentes: cada uma parece boa sozinha. O que precisa ser julgado é
 * o conjunto — trocar de seção, ver se a ação está sempre no mesmo canto, ver se a
 * espera de uma tela se parece com a espera da outra.
 *
 * Roda os componentes REAIS com o CSS real; só o `fetch` é falso.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter, Navigate, Route, Routes } from 'react-router-dom';
import { Casca } from '../src/layout/Casca';
import { Clientes } from '../src/telas/clientes/Clientes';
import { Configuracao } from '../src/telas/config/Configuracao';
import { EmBreve } from '../src/telas/EmBreve';
import '../src/estilo/global.css';

const CAPACIDADES = {
  usuarioId: 'u1', nome: 'Wesley Moreira', email: 'wesley@salao.test', perfil: 'ADMIN',
  estabelecimento: { id: 'e1', nome: 'Studio Bertoldo',
    timezone: 'America/Sao_Paulo', moeda: 'BRL' },
  permissoes: ['cliente:read', 'cliente:write', 'config:manage', 'estoque:read',
    'financeiro:read:all', 'agenda:read:all', 'comanda:open'],
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
  flags: { podeVerValorDeOutros: true },
  limites: { descontoMaximoPercentual: 100 },
  mfaAtivo: true, mfaObrigatorio: true,
};

const CONFIGURACAO = {
  id: 'e1', nome: 'Studio Bertoldo', documento: '12345678000190',
  fuso: 'America/Sao_Paulo', moeda: 'BRL', baseComissao: 'BRUTO',
  descontoAfetaComissao: false, periodicidadeDeFechamento: 'MENSAL',
  paisPadrao: 'BR', dddPadrao: '11', granularidadeAgendaMinutos: 15, ativo: true,
};

const CLIENTES = [
  { id: 'c1', nome: 'Maria Silva', apelido: null,
    telefone: '5511987654321', email: 'maria.silva@exemplo.com', versao: 0 },
  { id: 'c2', nome: 'Júlia Silva', apelido: 'Ju',
    telefone: '5511987654321', email: null, versao: 0 },
  { id: 'c3', nome: 'Maria Silvana Souza', apelido: null,
    telefone: '5511933334444', email: null, versao: 0 },
  { id: 'c4', nome: 'Josefa Antônia de Andrade', apelido: 'Dona Zilda',
    telefone: '5511955550001', email: null, versao: 0 },
];

function responder(url: string, metodo: string): unknown {
  if (url.includes('/me/capabilities')) return CAPACIDADES;
  if (url.includes('/v1/configuracao')) return CONFIGURACAO;
  if (url.includes('/consentimentos')) return [];
  // ANTES do ramo de busca: `possiveis-duplicados` também casa com `/v1/clientes`, e
  // devolver a forma errada aqui derrubava a tela — que é o tipo de erro que uma prévia
  // incompleta ensina.
  if (url.includes('possiveis-duplicados')) return [];
  if (metodo === 'POST' || metodo === 'PUT') return CLIENTES[0];

  const porId = /\/v1\/clientes\/([\w-]+)$/.exec(url);
  if (porId) return CLIENTES.find((c) => c.id === porId[1]);

  if (url.includes('/v1/clientes')) {
    const termo = new URL(url, 'http://x').searchParams.get('q') ?? '';
    const digitos = termo.replace(/\D/g, '');
    if (digitos) {
      if (digitos.length < 3) {
        return { interpretacao: 'INSUFICIENTE', aviso: 'informe ao menos 3 dígitos', itens: [] };
      }
      return { interpretacao: 'TELEFONE', aviso: null,
        itens: CLIENTES.filter((c) => c.telefone?.endsWith(digitos)) };
    }
    if (termo.trim().length < 2) {
      return { interpretacao: 'INSUFICIENTE', aviso: 'informe ao menos 2 letras', itens: [] };
    }
    const alvo = termo.toLowerCase();
    return { interpretacao: 'NOME', aviso: null,
      itens: CLIENTES.filter((c) =>
        `${c.nome} ${c.apelido ?? ''}`.toLowerCase().includes(alvo)) };
  }
  return {};
}

/*
 * Meio segundo de atraso, de propósito.
 *
 * Uma prévia que responde no mesmo quadro nunca mostra o estado de espera — e o estado
 * de espera é metade do que faz um sistema parecer fluido ou travado. Este é o único
 * lugar onde a lentidão é um recurso.
 */
window.fetch = async (entrada: RequestInfo | URL, init?: RequestInit) => {
  await new Promise((r) => setTimeout(r, 500));
  return new Response(JSON.stringify(responder(String(entrada), init?.method ?? 'GET')), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
};

const cliente = new QueryClient({
  defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
});

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={cliente}>
      <MemoryRouter initialEntries={['/clientes']}>
        <Routes>
          <Route element={<Casca />}>
            <Route path="/clientes" element={<Clientes />} />
            <Route path="/config" element={<Configuracao />} />
            <Route path="/estoque" element={<EmBreve titulo="Estoque" rotina="RT-EST-001" />} />
            <Route path="/financeiro"
              element={<EmBreve titulo="Financeiro" rotina="RT-FIN-004" />} />
            <Route path="/agenda" element={<EmBreve titulo="Agenda" rotina="RT-AGD-011" />} />
            <Route path="/comandas"
              element={<EmBreve titulo="Atendimento" rotina="RT-ATD-008" />} />
            <Route path="*" element={<Navigate to="/clientes" replace />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  </StrictMode>,
);
