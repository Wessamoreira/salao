import { useState } from 'react';
import {
  useImportacoes, useImportar, usePrevia,
  type Campo, type Formato, type LinhaDaPrevia, type Mapeamento, type Previa, type Resultado,
} from '../../api/conciliacao';
import { useCapacidades } from '../../api/capacidades';
import { ErroDaApi } from '../../api/http';
import { Esqueleto, Falha, Pagina } from '../../layout/Pagina';
import { formatar } from '../../layout/tempo';
import './conciliacao.css';

/**
 * RT-FIN-009 — importar o relatório de vendas da Stone.
 *
 * <h2>Conferir o valor, não o nome da coluna</h2>
 *
 * A Stone não publica as colunas da Lista de vendas, então o sistema sugere qual é qual — e a tela
 * mostra, ao lado de cada campo, o que ele leu da primeira venda: "Valor bruto · VALOR BRUTO → R$
 * 1.234,56". É isso que o dono confere. Um formato de número trocado aparece como um valor absurdo
 * aqui, e não como divergência depois.
 *
 * <h2>Nada é gravado antes do botão</h2>
 *
 * Escolher o arquivo e mexer no mapeamento só refazem a prévia. O arquivo não fica no servidor entre
 * as duas etapas: a importação o envia de novo, com o mapeamento confirmado.
 */
export function Conciliacao() {
  const [arquivo, definirArquivo] = useState<File | null>(null);
  const [mapeamento, definirMapeamento] = useState<Mapeamento | null>(null);
  const [resultado, definirResultado] = useState<Resultado | null>(null);
  const { data: capacidades } = useCapacidades();
  const fuso = capacidades?.estabelecimento.timezone ?? FUSO_PADRAO;
  const previa = usePrevia();
  const importar = useImportar();

  const recomecar = () => {
    definirArquivo(null);
    definirMapeamento(null);
    definirResultado(null);
    previa.reset();
    importar.reset();
  };

  const escolher = (novo: File) => {
    recomecar();
    definirArquivo(novo);
    previa.mutate({ arquivo: novo }, {
      onSuccess: (lida) => definirMapeamento({ colunas: lida.mapeamento, formato: lida.formato }),
    });
  };

  const remapear = (novo: Mapeamento) => {
    if (!arquivo) return;
    definirMapeamento(novo);
    previa.mutate({ arquivo, mapeamento: novo });
  };

  const confirmar = () => {
    if (!arquivo || !mapeamento) return;
    importar.mutate({ arquivo, mapeamento }, {
      onSuccess: (feito) => {
        definirResultado(feito);
        definirArquivo(null);
        definirMapeamento(null);
        previa.reset();
      },
    });
  };

  let etapa;
  if (resultado) {
    etapa = <ResultadoDaImportacao resultado={resultado} aoRecomecar={recomecar} />;
  } else if (!arquivo) {
    etapa = <EscolherArquivo aoEscolher={escolher} />;
  } else if (previa.isError && !previa.data) {
    etapa = (
      <section className="cnc-bloco cnc-erro" role="alert">
        <h2 className="cnc-titulo">Não deu para ler {arquivo.name}</h2>
        <p className="cnc-ajuda">{mensagem(previa.error)}</p>
        <button className="pagina-acao discreta" type="button" onClick={recomecar}>
          Escolher outro arquivo
        </button>
      </section>
    );
  } else if (!previa.data || !mapeamento) {
    etapa = <div className="cnc-bloco"><Esqueleto linhas={6} altura={40} /></div>;
  } else {
    etapa = (
      <Conferencia
        arquivo={arquivo}
        fuso={fuso}
        previa={previa.data}
        mapeamento={mapeamento}
        relendo={previa.isPending}
        importando={importar.isPending}
        erro={importar.error ?? previa.error}
        aoRemapear={remapear}
        aoImportar={confirmar}
        aoTrocar={recomecar}
      />
    );
  }

  return (
    <Pagina>
      <div className="cnc-tela">
        {etapa}
        <Historico fuso={fuso} />
      </div>
    </Pagina>
  );
}

function EscolherArquivo({ aoEscolher }: { aoEscolher: (arquivo: File) => void }) {
  const [arrastando, definirArrastando] = useState(false);
  return (
    <section className="cnc-bloco" aria-labelledby="cnc-entrada">
      <h2 id="cnc-entrada" className="cnc-titulo">Importar vendas da Stone</h2>
      <p className="cnc-ajuda">
        No portal da Stone, abra <strong>Vendas → Lista de vendas</strong>, escolha o período e
        exporte em <strong>CSV</strong>. Nada é gravado antes de você conferir.
      </p>
      <label
        className={`cnc-zona${arrastando ? ' arrastando' : ''}`}
        onDragOver={(e) => { e.preventDefault(); definirArrastando(true); }}
        onDragLeave={() => definirArrastando(false)}
        onDrop={(e) => {
          e.preventDefault();
          definirArrastando(false);
          const escolhido = e.dataTransfer.files[0];
          if (escolhido) aoEscolher(escolhido);
        }}
      >
        <input
          type="file"
          accept=".csv,text/csv"
          className="cnc-arquivo"
          onChange={(e) => {
            const escolhido = e.target.files?.[0];
            if (escolhido) aoEscolher(escolhido);
            e.target.value = '';
          }}
        />
        <span className="cnc-zona-acao">Escolher arquivo CSV</span>
        <span className="cnc-zona-dica">ou arraste para cá</span>
      </label>
    </section>
  );
}

function Conferencia({ arquivo, fuso, previa, mapeamento, relendo, importando, erro, aoRemapear,
  aoImportar, aoTrocar }: {
  arquivo: File;
  fuso: string;
  previa: Previa;
  mapeamento: Mapeamento;
  relendo: boolean;
  importando: boolean;
  erro: unknown;
  aoRemapear: (m: Mapeamento) => void;
  aoImportar: () => void;
  aoTrocar: () => void;
}) {
  const primeira = previa.amostra.find((linha) => linha.estado === 'LIDA');
  const usadas = new Set(Object.values(mapeamento.colunas));
  const faltaAlgo = previa.faltando.length > 0;

  return (
    <section className="cnc-bloco" aria-labelledby="cnc-conferencia">
      <header className="cnc-cabecalho">
        <div>
          <h2 id="cnc-conferencia" className="cnc-titulo">{arquivo.name}</h2>
          <p className="cnc-contas">
            {faltaAlgo ? `${previa.linhas} linhas — escolha as colunas obrigatórias para ler as vendas`
              : `${previa.linhas} linhas · ${previa.lidas} vendas lidas · ${previa.ignoradas} ignoradas`
                + ` · ${previa.recusadas} recusadas`}
          </p>
        </div>
        <button className="pagina-acao discreta" type="button" onClick={aoTrocar}>
          Escolher outro arquivo
        </button>
      </header>

      {previa.jaImportadoEm && (
        <p className="cnc-aviso" role="status">
          Este arquivo já foi importado em {quando(previa.jaImportadoEm, fuso)}. Importar de novo não duplica
          nada: o que já está aqui conta como repetido.
        </p>
      )}

      <div className="cnc-mapa" aria-busy={relendo}>
        <div className="cnc-mapa-topo">
          <h3 className="cnc-secao">Qual coluna é qual</h3>
          <div className="cnc-formato" role="group" aria-label="Números no arquivo">
            {FORMATOS.map((opcao) => (
              <button
                key={opcao.valor}
                type="button"
                aria-pressed={mapeamento.formato === opcao.valor}
                onClick={() => aoRemapear({ ...mapeamento, formato: opcao.valor })}
              >
                {opcao.rotulo}
              </button>
            ))}
          </div>
        </div>
        <ul className="cnc-campos">
          {previa.campos.map(({ campo, rotulo, obrigatorio }) => {
            const coluna = mapeamento.colunas[campo] ?? '';
            const faltando = previa.faltando.includes(campo);
            return (
              <li key={campo} className="cnc-campo" data-faltando={faltando || undefined}>
                <label className="cnc-campo-rotulo" htmlFor={`cnc-${campo}`}>
                  {rotulo}
                  {obrigatorio && <span className="cnc-obrigatorio"> · obrigatório</span>}
                </label>
                <select
                  id={`cnc-${campo}`}
                  value={coluna}
                  onChange={(e) => aoRemapear({
                    ...mapeamento,
                    colunas: { ...mapeamento.colunas, [campo]: e.target.value || undefined },
                  })}
                >
                  <option value="">— não usar —</option>
                  {previa.colunas.map((nome) => (
                    <option key={nome} value={nome} disabled={usadas.has(nome) && nome !== coluna}>
                      {nome}
                    </option>
                  ))}
                </select>
                <span className="cnc-campo-lido">
                  {faltando ? 'escolha a coluna' : coluna && primeira ? lido(campo, primeira) : '—'}
                </span>
              </li>
            );
          })}
        </ul>
      </div>

      {previa.amostra.length > 0 && <Amostra linhas={previa.amostra} />}

      <footer className="cnc-rodape">
        {erro != null && <p className="erro" role="alert">{mensagem(erro)}</p>}
        <button
          className="pagina-acao principal"
          type="button"
          disabled={faltaAlgo || previa.lidas === 0 || relendo || importando}
          onClick={aoImportar}
        >
          {importando ? 'Importando…'
            : `Importar ${previa.lidas} ${previa.lidas === 1 ? 'venda' : 'vendas'}`}
        </button>
      </footer>
    </section>
  );
}

function Amostra({ linhas }: { linhas: LinhaDaPrevia[] }) {
  return (
    <div className="cnc-rolagem">
      <table className="cnc-tabela">
        <caption>As primeiras linhas, como vão entrar</caption>
        <thead>
          <tr>
            <th className="num">Linha</th>
            <th>Stone ID</th>
            <th>Data</th>
            <th className="num">Valor bruto</th>
            <th>Produto</th>
            <th>Bandeira</th>
            <th className="num">Parcelas</th>
            <th>Situação</th>
          </tr>
        </thead>
        <tbody>
          {linhas.map((linha) => linha.estado === 'LIDA' ? (
            <tr key={linha.linha}>
              <td className="num">{linha.linha}</td>
              <td>{linha.idNaAdquirente}</td>
              <td>{lido('DATA', linha)}</td>
              <td className="num">{lido('VALOR_BRUTO', linha)}</td>
              <td>{lido('PRODUTO', linha)}</td>
              <td>{lido('BANDEIRA', linha)}</td>
              <td className="num">{lido('PARCELAS', linha)}</td>
              <td>{lido('SITUACAO', linha)}</td>
            </tr>
          ) : (
            <tr key={linha.linha} data-estado={linha.estado.toLowerCase()}>
              <td className="num">{linha.linha}</td>
              <td colSpan={7}>
                <span className="cnc-etiqueta">
                  {linha.estado === 'IGNORADA' ? 'Ignorada' : 'Recusada'}
                </span>{' '}
                {linha.motivo}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ResultadoDaImportacao({ resultado, aoRecomecar }: {
  resultado: Resultado;
  aoRecomecar: () => void;
}) {
  return (
    <section className="cnc-bloco" aria-labelledby="cnc-resultado">
      <h2 id="cnc-resultado" className="cnc-titulo">Importado</h2>
      <p className="cnc-totais">
        <Total numero={resultado.novas} rotulo={resultado.novas === 1 ? 'nova' : 'novas'} />
        <Total numero={resultado.canceladas}
          rotulo={resultado.canceladas === 1 ? 'cancelada' : 'canceladas'} />
        <Total numero={resultado.repetidas}
          rotulo={resultado.repetidas === 1 ? 'repetida' : 'repetidas'} />
        <Total numero={resultado.ignoradas}
          rotulo={resultado.ignoradas === 1 ? 'ignorada' : 'ignoradas'} />
        <Total numero={resultado.recusadas}
          rotulo={resultado.recusadas === 1 ? 'recusada' : 'recusadas'} />
      </p>
      {resultado.recusas.length > 0 && (
        <>
          <h3 className="cnc-secao">Linhas recusadas — confira no arquivo</h3>
          <ul className="cnc-recusas">
            {resultado.recusas.map((recusa) => (
              <li key={recusa.linha}>
                <span className="cnc-linha">Linha {recusa.linha}</span>
                <span>{recusa.motivo}</span>
              </li>
            ))}
          </ul>
          {resultado.recusadas > resultado.recusas.length && (
            <p className="cnc-ajuda">
              Mostrando as primeiras {resultado.recusas.length} de {resultado.recusadas}.
            </p>
          )}
        </>
      )}
      <button className="pagina-acao discreta" type="button" onClick={aoRecomecar}>
        Importar outro arquivo
      </button>
    </section>
  );
}

function Total({ numero, rotulo }: { numero: number; rotulo: string }) {
  return <span className="cnc-total"><strong className="cnc-numero">{numero}</strong> {rotulo}</span>;
}

function Historico({ fuso }: { fuso: string }) {
  const consulta = useImportacoes();
  const itens = consulta.data?.pages.flatMap((pagina) => pagina.itens) ?? [];

  if (consulta.isPending) return <div className="cnc-historico"><Esqueleto linhas={3} /></div>;
  if (consulta.isError) {
    return <Falha aoTentar={() => consulta.refetch()}>{mensagem(consulta.error)}</Falha>;
  }
  // Sem importação ainda, o convite de cima basta: uma tabela vazia só diria o que já se sabe.
  if (itens.length === 0) return null;

  return (
    <section className="cnc-historico" aria-labelledby="cnc-historico">
      <h2 id="cnc-historico" className="cnc-secao">Importações anteriores</h2>
      <div className="cnc-rolagem">
        <table className="cnc-tabela">
          <thead>
            <tr>
              <th>Quando</th>
              <th>Arquivo</th>
              <th>Quem</th>
              <th className="num">Novas</th>
              <th className="num">Canceladas</th>
              <th className="num">Repetidas</th>
              <th className="num">Ignoradas</th>
              <th className="num">Recusadas</th>
            </tr>
          </thead>
          <tbody>
            {itens.map((item) => (
              <tr key={item.id}>
                <td>{quando(item.importadaEm, fuso)}</td>
                <td>{item.nomeDoArquivo ?? '—'}</td>
                <td>{item.quemImportou ?? '—'}</td>
                <td className="num">{item.novas}</td>
                <td className="num">{item.canceladas}</td>
                <td className="num">{item.repetidas}</td>
                <td className="num">{item.ignoradas}</td>
                <td className="num" data-atencao={item.recusadas > 0 || undefined}>
                  {item.recusadas}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {consulta.hasNextPage && (
        <div className="cnc-mais">
          <button className="pagina-acao discreta" type="button"
            disabled={consulta.isFetchingNextPage} onClick={() => consulta.fetchNextPage()}>
            {consulta.isFetchingNextPage ? 'Carregando…' : 'Carregar mais'}
          </button>
        </div>
      )}
    </section>
  );
}

// ------------------------------------------------------------------ texto

const FUSO_PADRAO = 'America/Sao_Paulo';

const FORMATOS: { valor: Formato; rotulo: string }[] = [
  { valor: 'BRASILEIRO', rotulo: '1.234,56' },
  { valor: 'INTERNACIONAL', rotulo: '1,234.56' },
];

const PRODUTO = { CREDITO: 'Crédito', DEBITO: 'Débito', OUTRO: 'Outro' } as const;
const SITUACAO = { APROVADA: 'Aprovada', CANCELADA: 'Cancelada' } as const;

/** O que o sistema leu de um campo, do jeito que a pessoa reconhece. */
function lido(campo: Campo, linha: LinhaDaPrevia): string {
  switch (campo) {
    case 'ID_NA_ADQUIRENTE': return linha.idNaAdquirente ?? '—';
    case 'DATA': return dataLocal(linha.capturadaEm, linha.horaInformada);
    case 'VALOR_BRUTO': return reais(linha.valorBruto);
    case 'VALOR_LIQUIDO': return reais(linha.valorLiquido);
    case 'PRODUTO': return linha.produto ? PRODUTO[linha.produto] : '—';
    case 'BANDEIRA': return linha.bandeira ?? '—';
    case 'PARCELAS': return linha.parcelas == null ? '—'
      : linha.parcelas === 1 ? 'À vista' : `${linha.parcelas}x`;
    case 'NSU': return linha.nsu ?? '—';
    case 'AUTORIZACAO': return linha.autorizacao ?? '—';
    case 'SITUACAO': return linha.situacao ? SITUACAO[linha.situacao] : '—';
  }
}

function reais(valor: number | null): string {
  return valor == null ? '—'
    : new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(valor);
}

/** A hora do arquivo já é a do salão: formata o texto, sem passar por fuso. */
function dataLocal(texto: string | null, comHora: boolean | null): string {
  if (!texto) return '—';
  const [data, hora = ''] = texto.split('T');
  const [ano, mes, dia] = data.split('-');
  return comHora ? `${dia}/${mes}/${ano} ${hora.slice(0, 5)}` : `${dia}/${mes}/${ano}, sem hora`;
}

/** Instante gravado pelo servidor, mostrado no fuso do salão. */
function quando(instante: string, fuso: string): string {
  return formatar(new Date(instante), fuso, {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
    hour12: false,
  });
}

function mensagem(erro: unknown): string {
  return erro instanceof ErroDaApi ? erro.message : 'Tente de novo em instantes.';
}
