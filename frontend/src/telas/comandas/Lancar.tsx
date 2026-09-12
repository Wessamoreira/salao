import { useState } from 'react';
import {
  produtoDoCodigo, useProdutosVendaveis, useProfissionais, useServicos,
} from '../../api/comandas';
import { LeitorDeCodigo } from '../estoque/LeitorDeCodigo';

/**
 * RT-ATD-008 — lançar serviço ou produto sem sair da tela.
 *
 * Duas formas lado a lado, e não uma com seletor de tipo: serviço exige quem executou,
 * produto não tem executante. Um formulário só teria metade dos campos apagados
 * conforme a escolha — e é a mesma razão pela qual as rotas do servidor são duas
 * (RT-ATD-003).
 */
export function Lancar({ aoLancarServico, aoLancarProduto }: {
  aoLancarServico: (servicoId: string, profissionalId: string) => void;
  aoLancarProduto: (produtoId: string, quantidade: number) => void;
}) {
  const { data: servicos } = useServicos();
  const { data: profissionais } = useProfissionais();
  const { data: produtos } = useProdutosVendaveis();

  const [servico, setServico] = useState('');
  const [profissional, setProfissional] = useState('');
  const [produto, setProduto] = useState('');
  const [quantidade, setQuantidade] = useState('1');
  const [codigoSemDono, setCodigoSemDono] = useState<string | null>(null);

  const vendaveis = (produtos ?? []).filter((p) => p.vendavel);

  return (
    <div className="cmd-lancar">
      <form
        className="cmd-lancar-bloco"
        onSubmit={(e) => {
          e.preventDefault();
          if (servico && profissional) {
            aoLancarServico(servico, profissional);
            setServico('');
          }
        }}
      >
        <label className="cmd-rotulo" htmlFor="cmd-servico">Serviço</label>
        <select id="cmd-servico" className="cmd-campo" value={servico}
          onChange={(e) => setServico(e.target.value)}>
          <option value="">Escolha…</option>
          {(servicos ?? []).filter((s) => s.ativo).map((s) => (
            <option key={s.id} value={s.id}>{s.nome}</option>
          ))}
        </select>

        <label className="cmd-rotulo" htmlFor="cmd-profissional">Quem executou</label>
        <select id="cmd-profissional" className="cmd-campo" value={profissional}
          onChange={(e) => setProfissional(e.target.value)}>
          <option value="">Escolha…</option>
          {(profissionais ?? []).map((p) => (
            <option key={p.id} value={p.id}>{p.apelido ?? p.nome}</option>
          ))}
        </select>

        <button type="submit" className="cmd-botao" disabled={!servico || !profissional}>
          Lançar serviço
        </button>
      </form>

      <form
        className="cmd-lancar-bloco"
        onSubmit={(e) => {
          e.preventDefault();
          const quantas = Number(quantidade.replace(',', '.'));
          if (produto && quantas > 0) {
            aoLancarProduto(produto, quantas);
            setProduto('');
            setQuantidade('1');
            setCodigoSemDono(null);
          }
        }}
      >
        {/*
            RT-EST-009 — o bipe ESCOLHE o produto; quem lança é a pessoa, no botão.
            Lançar direto seria mais rápido e erraria mais: o lançamento dá baixa FEFO no
            estoque, e desfazer um bipe torto custa um estorno. A quantidade também quase
            nunca é 1 em venda de balcão.

            Sem `segurarFoco`: esta tela tem outros campos, e um leitor que reconquista o
            foco impediria de usar o select de serviço.
         */}
        <LeitorDeCodigo
          rotulo="Bipar produto"
          aoLer={(codigo) => {
            const achado = produtoDoCodigo(vendaveis, codigo);
            setCodigoSemDono(achado ? null : codigo);
            // Código desconhecido LIMPA a seleção, não apenas avisa. Sem isso: bipa o produto
            // A (seleciona), bipa o B cujo código não está cadastrado (aparece o aviso), e
            // "Lançar produto" continua armado — com o A. Lançava o item errado.
            setProduto(achado ? achado.id : '');
          }}
        />
        {codigoSemDono !== null && (
          <p className="est-achado" role="alert">
            Nenhum produto do balcão com o código{' '}
            <span className="est-achado-codigo">{codigoSemDono}</span>. Escolha na lista — e
            cadastre o código depois, para o próximo bipe achar.
          </p>
        )}

        <label className="cmd-rotulo" htmlFor="cmd-produto">Produto</label>
        <select id="cmd-produto" className="cmd-campo" value={produto}
          onChange={(e) => { setProduto(e.target.value); setCodigoSemDono(null); }}>
          <option value="">Escolha…</option>
          {vendaveis.map((p) => (
            <option key={p.id} value={p.id}>{p.nome}</option>
          ))}
        </select>

        <label className="cmd-rotulo" htmlFor="cmd-quantidade">Quantidade</label>
        <input id="cmd-quantidade" className="cmd-campo" type="text" inputMode="decimal"
          value={quantidade} onChange={(e) => setQuantidade(e.target.value)} />

        <button type="submit" className="cmd-botao" disabled={!produto}>
          Lançar produto
        </button>
      </form>
    </div>
  );
}
