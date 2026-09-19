package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.internal.domain.ImpressaoDoArquivo;
import br.com.salao.financeiro.internal.domain.InterpretadorDoExtrato;
import br.com.salao.financeiro.internal.domain.LinhaDoExtrato;
import br.com.salao.financeiro.internal.domain.MapeamentoDoExtrato;
import br.com.salao.financeiro.internal.domain.Reais;
import br.com.salao.financeiro.internal.domain.SituacaoDaTransacao;
import br.com.salao.financeiro.internal.domain.TransacaoLida;
import br.com.salao.financeiro.internal.infra.ImportacoesDeExtratoJdbc;
import br.com.salao.financeiro.internal.infra.MapeamentosDeExtratoJdbc;
import br.com.salao.financeiro.internal.infra.TransacoesDoAdquirenteJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-009 — grava o lado da maquininha, linha a linha, numa transação só.
 *
 * <h2>O que cada linha pode virar</h2>
 *
 * <ul>
 *   <li><strong>nova</strong> — Stone ID que o salão ainda não tinha;
 *   <li><strong>cancelada</strong> — já existia aprovada, e o arquivo diz cancelada;
 *   <li><strong>repetida</strong> — já existia igual. Reimportar o mesmo arquivo dá só isto;
 *   <li><strong>ignorada</strong> — não é venda a conciliar: Pix, negada, pré-autorização;
 *   <li><strong>recusada</strong> — não deu para ler, ou contradiz o que já estava gravado: o mesmo
 *       Stone ID com outro valor, ou uma cancelada que volta aprovada. Dinheiro não é sobrescrito em
 *       silêncio — quem decide é quem olha.
 * </ul>
 *
 * <p>As contas fecham por construção: a V58 recusa a importação cujas partes não somam o total.
 */
public class ImportarExtratoUseCase {

    private static final Logger log = LoggerFactory.getLogger(ImportarExtratoUseCase.class);

    static final int RECUSAS_GUARDADAS = 500;

    private final TransacoesDoAdquirenteJdbc transacoes;
    private final ImportacoesDeExtratoJdbc importacoes;
    private final MapeamentosDeExtratoJdbc mapeamentos;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public ImportarExtratoUseCase(TransacoesDoAdquirenteJdbc transacoes,
                                  ImportacoesDeExtratoJdbc importacoes,
                                  MapeamentosDeExtratoJdbc mapeamentos,
                                  EstabelecimentoApi estabelecimentos, AuditoriaApi auditoria,
                                  Relogio relogio) {
        this.transacoes = transacoes;
        this.importacoes = importacoes;
        this.mapeamentos = mapeamentos;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /** @param recusas as primeiras {@value #RECUSAS_GUARDADAS}; o total está em {@code recusadas} */
    public record ResultadoDaImportacao(UUID id, Instant importadaEm, int linhas, int novas,
                                        int canceladas, int repetidas, int ignoradas,
                                        int recusadas, List<LinhaDoExtrato.Recusada> recusas) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional
    public ResultadoDaImportacao executar(byte[] arquivo, String nomeDoArquivo,
                                          MapeamentoDoExtrato mapeamento) {
        var tabela = LeituraDoExtrato.ler(arquivo);
        var faltando = mapeamento.obrigatoriosFaltando(tabela.cabecalho());
        if (!faltando.isEmpty()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.MAPEAMENTO_INCOMPLETO,
                    "falta dizer qual coluna é: "
                            + String.join(", ", LeituraDoExtrato.rotulos(faltando)));
        }

        ZoneId fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());
        Instant agora = relogio.agora();
        UUID importacaoId = UUID.randomUUID();
        String adquirente = LeituraDoExtrato.ADQUIRENTE;

        int novas = 0;
        int canceladas = 0;
        int repetidas = 0;
        int ignoradas = 0;
        List<LinhaDoExtrato.Recusada> recusas = new ArrayList<>();

        for (LinhaDoExtrato linha : InterpretadorDoExtrato.interpretar(tabela, mapeamento)) {
            switch (linha) {
                case LinhaDoExtrato.Ignorada i -> ignoradas++;
                case LinhaDoExtrato.Recusada r -> recusas.add(r);
                case LinhaDoExtrato.Lida(int n, TransacaoLida venda) -> {
                    Instant capturadaEm = venda.capturadaEm().atZone(fuso).toInstant();
                    if (transacoes.inserir(adquirente, venda, capturadaEm, importacaoId, agora)
                            .isPresent()) {
                        novas++;
                        continue;
                    }
                    var existente = transacoes.existente(adquirente, venda.idNaAdquirente());
                    if (existente.valorBruto().compareTo(venda.valorBruto()) != 0) {
                        recusas.add(new LinhaDoExtrato.Recusada(n, "Stone ID "
                                + venda.idNaAdquirente() + " já importado com valor bruto "
                                + Reais.de(existente.valorBruto()) + "; o arquivo diz "
                                + Reais.de(venda.valorBruto())));
                    } else if (existente.situacao() == SituacaoDaTransacao.CANCELADA
                            && venda.situacao() == SituacaoDaTransacao.APROVADA) {
                        recusas.add(new LinhaDoExtrato.Recusada(n, "Stone ID "
                                + venda.idNaAdquirente()
                                + " já estava cancelado; o arquivo diz aprovado"));
                    } else if (venda.situacao() == SituacaoDaTransacao.CANCELADA
                            && transacoes.cancelar(existente.id(), importacaoId, agora)) {
                        canceladas++;
                    } else {
                        repetidas++;
                    }
                }
            }
        }

        int linhas = tabela.registros().size();
        var guardadas = List.copyOf(recusas.subList(0, Math.min(recusas.size(), RECUSAS_GUARDADAS)));
        importacoes.registrar(new ImportacoesDeExtratoJdbc.Nova(importacaoId, adquirente,
                ImpressaoDoArquivo.de(arquivo), nome(nomeDoArquivo), agora, UsuarioAtual.idOuNulo(),
                linhas, novas, canceladas, repetidas, ignoradas, recusas.size(), guardadas,
                mapeamento));
        mapeamentos.salvar(adquirente, mapeamento, agora);

        var contas = new LinkedHashMap<String, Object>();
        contas.put("linhas", linhas);
        contas.put("novas", novas);
        contas.put("canceladas", canceladas);
        contas.put("repetidas", repetidas);
        contas.put("ignoradas", ignoradas);
        contas.put("recusadas", recusas.size());
        auditoria.registrar(RegistroDeAuditoria.criacao("importacao_de_extrato", importacaoId,
                contas));
        log.info("Extrato importado: {} linhas — {} novas, {} canceladas, {} repetidas, "
                        + "{} ignoradas, {} recusadas", linhas, novas, canceladas, repetidas,
                ignoradas, recusas.size());

        return new ResultadoDaImportacao(importacaoId, agora, linhas, novas, canceladas, repetidas,
                ignoradas, recusas.size(), guardadas);
    }

    /** Só o nome, sem caminho — navegador antigo manda "C:\\...\\vendas.csv". */
    private static String nome(String nomeDoArquivo) {
        if (nomeDoArquivo == null || nomeDoArquivo.isBlank()) {
            return null;
        }
        String nome = nomeDoArquivo.replace('\\', '/');
        nome = nome.substring(nome.lastIndexOf('/') + 1).strip();
        return nome.length() > 200 ? nome.substring(0, 200) : nome;
    }
}
