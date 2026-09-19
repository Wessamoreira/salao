package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.internal.domain.CampoDoExtrato;
import br.com.salao.financeiro.internal.domain.FormatoDeNumero;
import br.com.salao.financeiro.internal.domain.ImpressaoDoArquivo;
import br.com.salao.financeiro.internal.domain.InterpretadorDoExtrato;
import br.com.salao.financeiro.internal.domain.LinhaDoExtrato;
import br.com.salao.financeiro.internal.domain.MapeamentoDoExtrato;
import br.com.salao.financeiro.internal.domain.ProdutoDoCartao;
import br.com.salao.financeiro.internal.domain.SituacaoDaTransacao;
import br.com.salao.financeiro.internal.infra.ImportacoesDeExtratoJdbc;
import br.com.salao.financeiro.internal.infra.MapeamentosDeExtratoJdbc;
import br.com.salao.iam.api.Permissao;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-009 — lê o arquivo sem gravar nada, e mostra como ele seria importado.
 *
 * <p>É onde o dono confere a sugestão de colunas e o formato de número <strong>vendo as vendas já
 * lidas</strong> — data, valor, bandeira — e não uma lista de nomes de coluna. Um formato de número
 * trocado aparece aqui como R$ 123.456,00 numa venda de R$ 1.234,56, antes de virar divergência.
 */
public class PreverImportacaoUseCase {

    static final int AMOSTRA = 8;

    private final MapeamentosDeExtratoJdbc mapeamentos;
    private final ImportacoesDeExtratoJdbc importacoes;

    public PreverImportacaoUseCase(MapeamentosDeExtratoJdbc mapeamentos,
                                   ImportacoesDeExtratoJdbc importacoes) {
        this.mapeamentos = mapeamentos;
        this.importacoes = importacoes;
    }

    /** Uma linha da amostra: lida, com os valores interpretados, ou ignorada e recusada, com o motivo. */
    public record LinhaDaPrevia(int linha, String estado, String motivo, String idNaAdquirente,
                                LocalDateTime capturadaEm, Boolean horaInformada,
                                ProdutoDoCartao produto, String bandeira, Integer parcelas,
                                BigDecimal valorBruto, BigDecimal valorLiquido, String nsu,
                                String autorizacao, SituacaoDaTransacao situacao) {

        static LinhaDaPrevia de(LinhaDoExtrato linha) {
            return switch (linha) {
                case LinhaDoExtrato.Lida(int n, var t) -> new LinhaDaPrevia(n, "LIDA", null,
                        t.idNaAdquirente(), t.capturadaEm(), t.horaInformada(), t.produto(),
                        t.bandeira(), t.parcelas(), t.valorBruto(), t.valorLiquido(), t.nsu(),
                        t.autorizacao(), t.situacao());
                case LinhaDoExtrato.Ignorada(int n, String motivo) -> vazia(n, "IGNORADA", motivo);
                case LinhaDoExtrato.Recusada(int n, String motivo) -> vazia(n, "RECUSADA", motivo);
            };
        }

        private static LinhaDaPrevia vazia(int n, String estado, String motivo) {
            return new LinhaDaPrevia(n, estado, motivo, null, null, null, null, null, null, null,
                    null, null, null, null);
        }
    }

    /** Um campo que a tela pode mapear. Vem do servidor: quais são obrigatórios é regra, não tela. */
    public record CampoMapeavel(CampoDoExtrato campo, String rotulo, boolean obrigatorio) {
    }

    /**
     * @param campos      os campos que existem, na ordem em que a tela os mostra
     * @param faltando    os obrigatórios sem coluna; com algum faltando, não há amostra nem contas
     * @param jaImportadoEm quando este mesmo arquivo foi importado; nulo se nunca
     */
    public record PreviaDaImportacao(List<CampoMapeavel> campos, List<String> colunas,
                                     Map<CampoDoExtrato, String> mapeamento,
                                     FormatoDeNumero formato, List<CampoDoExtrato> faltando,
                                     List<LinhaDaPrevia> amostra, int linhas, int lidas,
                                     int ignoradas, int recusadas, Instant jaImportadoEm) {
    }

    /** @param pedido o mapeamento que a tela quer ver aplicado; nulo para receber a sugestão */
    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional(readOnly = true)
    public PreviaDaImportacao executar(byte[] arquivo, MapeamentoDoExtrato pedido) {
        var tabela = LeituraDoExtrato.ler(arquivo);
        MapeamentoDoExtrato mapeamento = pedido != null ? pedido
                : LeituraDoExtrato.sugerir(tabela,
                        mapeamentos.doSalao(LeituraDoExtrato.ADQUIRENTE));
        var faltando = mapeamento.obrigatoriosFaltando(tabela.cabecalho());

        List<LinhaDoExtrato> linhas = faltando.isEmpty()
                ? InterpretadorDoExtrato.interpretar(tabela, mapeamento) : List.of();
        int lidas = 0;
        int ignoradas = 0;
        int recusadas = 0;
        for (LinhaDoExtrato linha : linhas) {
            switch (linha) {
                case LinhaDoExtrato.Lida l -> lidas++;
                case LinhaDoExtrato.Ignorada i -> ignoradas++;
                case LinhaDoExtrato.Recusada r -> recusadas++;
            }
        }
        var campos = java.util.Arrays.stream(CampoDoExtrato.values())
                .map(c -> new CampoMapeavel(c, c.rotulo(), c.obrigatorio())).toList();
        return new PreviaDaImportacao(campos, tabela.cabecalho(), mapeamento.colunas(),
                mapeamento.formato(), faltando,
                linhas.stream().limit(AMOSTRA).map(LinhaDaPrevia::de).toList(),
                tabela.registros().size(), lidas, ignoradas, recusadas,
                importacoes.importadoEm(ImpressaoDoArquivo.de(arquivo)).orElse(null));
    }
}
