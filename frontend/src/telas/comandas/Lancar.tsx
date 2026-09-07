import { useState } from 'react';
import { useProdutosVendaveis, useProfissionais, useServicos } from '../../api/comandas';

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
          }
        }}
      >
        <label className="cmd-rotulo" htmlFor="cmd-produto">Produto</label>
        <select id="cmd-produto" className="cmd-campo" value={produto}
          onChange={(e) => setProduto(e.target.value)}>
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
