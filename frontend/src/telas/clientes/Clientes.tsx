import { useEffect, useRef, useState } from 'react';
import type { Cliente } from '../../api/clientes';
import { useBusca } from '../../api/clientes';
import { CadastroRapido } from './CadastroRapido';
import { Ficha } from './Ficha';
import { Isolado } from './Isolado';
import { Resultados } from './Resultados';
import './clientes.css';

/**
 * RT-CLI-006 — a tela de clientes.
 *
 * <h2>A busca É a tela</h2>
 *
 * O layout de CRUD — tabela no meio, campo de busca no canto — diria que o assunto
 * aqui é a lista. Não é, e o backend concorda: não existe endpoint que devolva todos
 * os clientes (RT-CLI-002). Sem termo, a resposta é vazia.
 *
 * Esta tela existe porque o telefone está tocando (R-UX-03). O campo de busca ocupa
 * o alto, em corpo grande, com o foco já nele quando a tela abre.
 */
export function Clientes() {
  const [termo, setTermo] = useState('');
  const [selecionado, setSelecionado] = useState<Cliente | null>(null);
  const campo = useRef<HTMLInputElement>(null);

  const { data: resultado } = useBusca(useTermoComPausa(termo));

  // O foco começa no campo: quem abre esta tela vai digitar, sempre.
  useEffect(() => { campo.current?.focus(); }, []);

  const naoAchou = termo.trim().length > 0 && resultado?.itens.length === 0 && !resultado.aviso;
  const esperando = termo.trim().length === 0;

  return (
    <div className={esperando ? 'cli-tela esperando' : 'cli-tela'}>
      <header className="cli-cabecalho">
        <input
          ref={campo}
          className="cli-busca"
          type="search"
          aria-label="Buscar cliente"
          placeholder="Nome, apelido ou telefone"
          value={termo}
          onChange={(e) => setTermo(e.target.value)}
          onKeyDown={(e) => {
            // Esc limpa e devolve a tela ao estado de espera, sem tirar a mão do
            // teclado (R-UX-02).
            if (e.key === 'Escape') { setTermo(''); setSelecionado(null); }
          }}
        />
        <Leitura resultado={resultado} temTermo={termo.trim().length > 0} />
      </header>

      <div>
        <Resultados
          resultado={resultado}
          termo={termo}
          selecionadoId={selecionado?.id ?? null}
          aoSelecionar={setSelecionado}
        />
      </div>

      <div>
        {naoAchou ? (
          <div className="cli-ficha">
            <CadastroRapido
              termoInicial={termo.trim()}
              ehTelefone={resultado?.interpretacao === 'TELEFONE'}
              aoCadastrar={() => setTermo(termo)}
            />
          </div>
        ) : selecionado ? (
          <Isolado key={selecionado.id}>
            <Ficha cliente={selecionado} aoSair={() => setSelecionado(null)} />
          </Isolado>
        ) : null}
      </div>
    </div>
  );
}

/**
 * O que o servidor entendeu do termo, escrito na tela.
 *
 * É a peça que ensina a única regra que a recepção precisa saber — que dígito
 * significa telefone — sem manual e sem seletor. E o texto vem da resposta, nunca
 * de um palpite daqui: reimplementar RN-CLI-009 em TypeScript criaria uma cópia
 * que um dia diria uma coisa enquanto a consulta fazia outra.
 */
function Leitura({
  resultado,
  temTermo,
}: {
  resultado: { interpretacao: string; aviso: string | null; itens: unknown[] } | undefined;
  temTermo: boolean;
}) {
  if (!temTermo || !resultado) return <p className="cli-leitura" />;

  if (resultado.interpretacao === 'INSUFICIENTE') {
    return <p className="cli-leitura">{resultado.aviso}</p>;
  }

  const comoBuscou =
    resultado.interpretacao === 'TELEFONE'
      ? 'pelos últimos dígitos do telefone'
      : 'por nome e apelido';

  return (
    <p className="cli-leitura">
      <span className="cli-leitura-chave">
        {resultado.interpretacao === 'TELEFONE' ? 'telefone' : 'nome'}
      </span>
      <span>procurando {comoBuscou}</span>
      <span className="cli-leitura-sep">·</span>
      <span>{contar(resultado.itens.length)}</span>
    </p>
  );
}

function contar(quantos: number): string {
  if (quantos === 0) return 'nenhuma ficha';
  return quantos === 1 ? '1 ficha' : `${quantos} fichas`;
}

/**
 * Segura o termo por um instante antes de buscar.
 *
 * Sem isso, "Maria" dispara cinco consultas e a quinta pode chegar antes da
 * terceira — a lista pisca e às vezes assenta no resultado errado. 220ms é curto
 * o bastante para não ser sentido por quem digita e longo o bastante para o
 * teclado inteiro caber dentro.
 */
function useTermoComPausa(termo: string): string {
  const [estavel, setEstavel] = useState(termo);
  useEffect(() => {
    const t = setTimeout(() => setEstavel(termo), 220);
    return () => clearTimeout(t);
  }, [termo]);
  return estavel;
}
