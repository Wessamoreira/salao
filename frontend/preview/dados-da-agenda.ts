/*
 * O dia de agenda que as prévias usam — um só, para a prévia da grade e a do sistema
 * mostrarem o mesmo dia. Duas cópias divergem na primeira correção.
 */
/** Hoje, para a linha do agora aparecer onde ela precisa ser olhada. */
const HOJE = new Intl.DateTimeFormat('en-CA').format(new Date());
const FUSO = 'America/Sao_Paulo';

function as(hora: number, minuto = 0): string {
  const [ano, mes, dia] = HOJE.split('-').map(Number);
  return new Date(Date.UTC(ano, mes - 1, dia, hora + 3, minuto)).toISOString();
}

const PROFISSIONAIS = [
  { id: 'ana', nome: 'Ana Ribeiro', apelido: 'Ana' },
  { id: 'julia', nome: 'Júlia Menezes', apelido: null },
  { id: 'nice', nome: 'Berenice do Carmo', apelido: 'Nice' },
];

function bloco(profissionalId: string | null, de: string, ate: string, ordem: number) {
  return { ordem, tipo: profissionalId ? 'ATIVO' : 'PAUSA',
    janela: { inicio: de, fim: ate }, profissionalId, recursoId: null, unidade: null };
}

/** Um dia de verdade: coloração com pausa, encaixe dentro dela, falta e cancelamento. */
const AGENDAMENTOS = [
  { id: 'a1', status: 'EM_ATENDIMENTO', inicio: as(10), fim: as(11, 40),
    clienteId: 'c1', profissionalId: 'ana', servicoId: 's1', origem: 'PAINEL',
    observacao: null, minutosDoProfissional: 60, versao: 0,
    blocos: [bloco('ana', as(10), as(10, 30), 0), bloco(null, as(10, 30), as(11, 10), 1),
      bloco('ana', as(11, 10), as(11, 40), 2)] },
  // O encaixe dentro da pausa — a razão de o modelo de blocos existir.
  { id: 'a2', status: 'CONFIRMADO', inicio: as(10, 40), fim: as(11, 10),
    clienteId: 'c2', profissionalId: 'ana', servicoId: 's2', origem: 'PAINEL',
    observacao: null, minutosDoProfissional: 30, versao: 0,
    blocos: [bloco('ana', as(10, 40), as(11, 10), 0)] },
  { id: 'a3', status: 'SOLICITADO', inicio: as(14), fim: as(14, 45),
    clienteId: 'c3', profissionalId: 'ana', servicoId: 's3', origem: 'WEB',
    observacao: null, minutosDoProfissional: 45, versao: 0,
    blocos: [bloco('ana', as(14), as(14, 45), 0)] },
  { id: 'a4', status: 'NO_SHOW', inicio: as(9), fim: as(9, 45),
    clienteId: 'c4', profissionalId: 'julia', servicoId: 's3', origem: 'WHATSAPP',
    observacao: null, minutosDoProfissional: 45, versao: 0,
    blocos: [bloco('julia', as(9), as(9, 45), 0)] },
  { id: 'a5', status: 'CANCELADO', inicio: as(11), fim: as(11, 45),
    clienteId: 'c1', profissionalId: 'julia', servicoId: 's3', origem: 'PAINEL',
    observacao: null, minutosDoProfissional: 45, versao: 0,
    blocos: [bloco('julia', as(11), as(11, 45), 0)] },
  { id: 'a6', status: 'CONFIRMADO', inicio: as(15), fim: as(16, 40),
    clienteId: 'c5', profissionalId: 'julia', servicoId: 's1', origem: 'PAINEL',
    observacao: null, minutosDoProfissional: 60, versao: 0,
    blocos: [bloco('julia', as(15), as(15, 30), 0), bloco(null, as(15, 30), as(16, 10), 1),
      bloco('julia', as(16, 10), as(16, 40), 2)] },
];

export const RESPOSTA_DA_AGENDA = {
  data: HOJE,
  fuso: FUSO,
  profissionais: PROFISSIONAIS,
  jornadas: {
    ana: [{ inicio: as(9), fim: as(13) }, { inicio: as(14), fim: as(19) }],
    julia: [{ inicio: as(9), fim: as(18) }],
    // A Nice não trabalha hoje: a coluna inteira precisa ler como indisponível.
    nice: [],
  },
  agendamentos: AGENDAMENTOS,
  bloqueios: [{ id: 'b1', profissionalId: 'julia', recursoId: null, motivo: 'CURSO',
    descricao: 'curso de coloração', janela: { inicio: as(13), fim: as(15) } }],
  nomesDeClientes: {
    c1: 'Marilda Souza', c2: 'Ju', c3: 'Josefa Antônia de Andrade',
    c4: 'Cleide R.', c5: 'Vera Lúcia',
  },
};

// Duas vagas abertas, para o aviso de RT-AGD-010 aparecer — é o que a recepção vê depois de
// cancelar. O conteúdo delas é da tela da fila; aqui só a contagem importa.
export const VAGAS = [{ id: 'v1' }, { id: 'v2' }];
