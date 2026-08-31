/*
 * Prévia visual da tela de clientes, sem backend.
 *
 * Existe porque teste de comportamento não olha para a tela. O erro de contraste
 * que a revisão do design system pegou (rótulo em --tinta-tenue, insuficiente para
 * AA) passou por todos os testes e só apareceu quando alguém abriu o navegador —
 * "documentar não substitui olhar o resultado".
 *
 * Roda os componentes REAIS com o CSS real; só a rede é falsa. Uma prévia com
 * marcação própria mostraria uma tela que não existe.
 *
 *   npm run previa   →   http://localhost:5174/preview/clientes.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Clientes } from '../src/telas/clientes/Clientes';
import '../src/estilo/global.css';

const CLIENTES = [
  { id: 'c1', nome: 'Maria Silva', apelido: null,
    telefone: '5511987654321', email: 'maria.silva@exemplo.com' },
  { id: 'c2', nome: 'Júlia Silva', apelido: 'Ju', telefone: '5511987654321', email: null },
  { id: 'c3', nome: 'Maria Silvana Souza', apelido: null,
    telefone: '5511933334444', email: null },
  { id: 'c4', nome: 'Josefa Antônia de Andrade', apelido: 'Dona Zilda',
    telefone: '5511955550001', email: null },
];

const CONSENTIMENTOS = [
  { finalidade: 'LEMBRETE_DE_AGENDAMENTO', baseLegal: 'EXECUCAO_DE_CONTRATO',
    decisao: 'SEM_REGISTRO', podeReceber: true, desde: null, canal: null },
  { finalidade: 'MARKETING', baseLegal: 'CONSENTIMENTO',
    decisao: 'CONCEDIDO', podeReceber: true, desde: '2026-03-12T14:20:00Z', canal: 'BALCAO' },
  { finalidade: 'PESQUISA_DE_SATISFACAO', baseLegal: 'CONSENTIMENTO',
    decisao: 'RECUSADO', podeReceber: false, desde: '2026-05-02T10:05:00Z', canal: 'WHATSAPP' },
];

function responder(url: string): unknown {
  if (url.includes('/me/capabilities')) {
    return {
      usuarioId: 'u1', nome: 'Joana', email: 'joana@salao.test', perfil: 'ADMIN',
      estabelecimento: { id: 'e1', nome: 'Studio Marilda',
        timezone: 'America/Sao_Paulo', moeda: 'BRL' },
      permissoes: ['cliente:read', 'cliente:write', 'cliente:anonymize'],
      menus: [], flags: {}, limites: {}, mfaAtivo: true, mfaObrigatorio: true,
    };
  }
  if (url.includes('possiveis-duplicados')) {
    return [{ grau: 'MESMO_TELEFONE', cliente: CLIENTES[1] }];
  }
  if (url.includes('/consentimentos')) return CONSENTIMENTOS;

  const termo = new URL(url, 'http://x').searchParams.get('q') ?? '';
  const digitos = termo.replace(/\D/g, '');
  if (digitos) {
    if (digitos.length < 3) return { interpretacao: 'INSUFICIENTE',
      aviso: 'informe ao menos 3 dígitos', itens: [] };
    return { interpretacao: 'TELEFONE', aviso: null,
      itens: CLIENTES.filter((c) => c.telefone?.endsWith(digitos)) };
  }
  if (termo.trim().length < 2) {
    return { interpretacao: 'INSUFICIENTE', aviso: 'informe ao menos 2 letras', itens: [] };
  }
  const alvo = termo.toLowerCase();
  return {
    interpretacao: 'NOME', aviso: null,
    itens: CLIENTES.filter((c) => `${c.nome} ${c.apelido ?? ''}`.toLowerCase().includes(alvo)),
  };
}

window.fetch = async (entrada: RequestInfo | URL) =>
  new Response(JSON.stringify(responder(String(entrada))), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={new QueryClient()}>
      <div style={{ minHeight: '100dvh', background: 'var(--papel-fundo)',
        padding: 'var(--e-8)' }}>
        <Clientes />
      </div>
    </QueryClientProvider>
  </StrictMode>,
);
