import { useEffect, useState } from 'react';
import { usePainel, type LinhaDoPainel } from '../../api/agenda';
import { rotuloDeHora } from './grade';
import './painel.css';

/**
 * RT-AGD-012 — a tela pendurada no balcão.
 *
 * Somente leitura, lida a dois ou três metros, e vista por quem está esperando. Não há
 * valor, não há serviço, não há telefone: o que ninguém lê de longe só serve para vazar.
 */
const SITUACAO: Record<string, { texto: string; classe: string }> = {
  EM_ATENDIMENTO: { texto: 'em atendimento', classe: 'atendendo' },
  CONFIRMADO: { texto: 'confirmado', classe: 'confirmado' },
  SOLICITADO: { texto: 'a confirmar', classe: 'solicitado' },
};

export function Painel() {
  const { data: painel, error } = usePainel();

  // O relógio grande anda sozinho; o resto da tela só muda quando o servidor avisa.
  const [agora, definirAgora] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => definirAgora(new Date()), 20_000);
    return () => clearInterval(id);
  }, []);

  const fuso = painel?.fuso ?? Intl.DateTimeFormat().resolvedOptions().timeZone;
  const hora = rotuloDeHora(agora.toISOString(), fuso);

  return (
    <main className="painel">
      <header className="painel-topo">
        <span className="painel-identidade">{painel?.estabelecimento ?? ' '}</span>
        <span className="painel-relogio">{hora}</span>
      </header>

      {error && <p className="painel-vazio">Sem conexão com o sistema.</p>}

      {painel && painel.linhas.length === 0 && !error && (
        <p className="painel-vazio">Nenhum atendimento restante hoje.</p>
      )}

      {painel && painel.linhas.length > 0 && (
        <>
          <div className="painel-cabecalho" role="row">
            <span>Horário</span>
            <span>Cliente</span>
            <span>Profissional</span>
            <span>Situação</span>
          </div>
          {painel.linhas.map((linha) => (
            <Linha key={`${linha.inicio}-${linha.cliente}`} linha={linha} fuso={painel.fuso} />
          ))}
        </>
      )}

      <footer className="painel-rodape">
        <span>atualizado às {painel ? rotuloDeHora(painel.atualizadoEm, painel.fuso) : '—'}</span>
        <span>
          {painel ? `${painel.restantes} atendimento${painel.restantes === 1 ? '' : 's'} `
            + 'restante' + (painel.restantes === 1 ? '' : 's') + ' hoje' : ' '}
        </span>
      </footer>
    </main>
  );
}

function Linha({ linha, fuso }: { linha: LinhaDoPainel; fuso: string }) {
  const situacao = SITUACAO[linha.situacao] ?? { texto: '', classe: '' };
  return (
    <div className="painel-linha" role="row">
      <span className="painel-hora">{rotuloDeHora(linha.inicio, fuso)}</span>
      <span className="painel-cliente">{linha.cliente}</span>
      <span className="painel-profissional">{linha.profissional}</span>
      <span className={`painel-situacao ${situacao.classe}`}>{situacao.texto}</span>
    </div>
  );
}
