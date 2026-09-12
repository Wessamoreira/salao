package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.FechamentoDoProfissional;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.internal.infra.FechamentosJdbc;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-006 — fechar o período e repassar.
 *
 * <h2>O que o fechamento é</h2>
 *
 * <p>Para cada pessoa: um documento com o que ela tinha antes, o que o período produziu e o que
 * foi pago — e, quando houve pagamento, uma linha {@code REPASSE} no razão que zera a conta.
 *
 * <h2>A competência do repasse é o fim do período, não hoje</h2>
 *
 * <p>É a exceção nomeada de RN-FIN-010, e ela tem razão de ser: o repasse <strong>liquida</strong>
 * um período, e pertence a ele. Um repasse de março datado de 5 de abril deixaria o extrato de
 * março mostrando dinheiro a receber que já foi pago — e a pessoa cobraria de novo, com razão.
 *
 * <h2>Quem deve ao salão não gera repasse negativo</h2>
 *
 * <p>Quem adiantou mais do que ganhou fecha o período com zero, e a dívida fica no razão. O
 * fechamento seguinte a encontra sozinho, porque a conta é acumulada e não por período — sem linha
 * de transporte, sem coluna de saldo, sem rotina de recálculo.
 *
 * <h2>Tudo numa transação</h2>
 *
 * <p>N documentos e N linhas de razão commitam juntos. Um fechamento pela metade seria metade da
 * equipe paga e metade não, com o período marcado como fechado para os dois lados.
 */
public class FecharPeriodoUseCase {

    private static final Logger log = LoggerFactory.getLogger(FecharPeriodoUseCase.class);

    private static final String ORIGEM_FECHAMENTO = "FECHAMENTO";
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final FechamentosJdbc fechamentos;
    private final LancamentosJdbc lancamentos;
    private final ContasDoPeriodo contas;
    private final PeriodoDeFechamento periodo;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public FecharPeriodoUseCase(FechamentosJdbc fechamentos, LancamentosJdbc lancamentos,
                                ContasDoPeriodo contas, PeriodoDeFechamento periodo,
                                AuditoriaApi auditoria, Relogio relogio) {
        this.fechamentos = fechamentos;
        this.lancamentos = lancamentos;
        this.contas = contas;
        this.periodo = periodo;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_FECHAR + "')")
    @Transactional
    public List<FechamentoDoProfissional> executar(LocalDate de, LocalDate ate,
                                                    String versaoDaPrevia) {
        UUID tenant = TenantContext.obrigatorio();

        // O lock precisa vir antes da conferência e do cálculo. Assim dois períodos diferentes
        // não leem o mesmo saldo acumulado ao mesmo tempo e não pagam a mesma comissão duas vezes.
        fechamentos.travarEstabelecimento(tenant);
        periodo.conferir(de, ate);

        List<ContasDoPeriodo.Posicao> posicoes = contas.em(de, ate);
        if (posicoes.isEmpty()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.SEM_POSICOES_PARA_FECHAR,
                    "não há comissão, vale ou saldo anterior para fechar neste período");
        }
        String versaoAtual = VersaoDaPrevia.calcular(de, ate, posicoes);
        if (versaoDaPrevia == null || !versaoAtual.equals(versaoDaPrevia)) {
            throw new ErroDeDominio(ErrosDoFinanceiro.PREVIA_DESATUALIZADA,
                    "os valores mudaram desde a prévia; confira os números atualizados");
        }

        Instant agora = relogio.agora();
        UUID por = UsuarioAtual.idOuNulo();
        String descricao = "Repasse do período " + de.format(DIA) + " a " + ate.format(DIA);

        List<FechamentoDoProfissional> feitos = new ArrayList<>();
        Money total = Money.ZERO;
        int quantosReceberam = 0;

        try {
            for (ContasDoPeriodo.Posicao posicao : posicoes) {
                UUID id = fechamentos.inserir(tenant, posicao.profissionalId(), de, ate,
                        posicao.saldoAnterior(), posicao.movimento(), posicao.aRepassar(), agora,
                        por);

                if (!posicao.aRepassar().ehZero()) {
                    lancamentos.inserir(tenant, ate, TipoDeLancamento.REPASSE,
                            ORIGEM_FECHAMENTO, id, ContasDoPeriodo.CONTRAPARTE,
                            posicao.profissionalId(),
                            // Positivo, como o vale: dinheiro entregue devolve ao salão o que ele
                            // devia. O extrato inverte e mostra o menos que a pessoa espera ver.
                            posicao.aRepassar(), descricao, null, agora, por);
                    total = total.somar(posicao.aRepassar());
                    quantosReceberam++;
                }
                // Um registro por documento, e não um pelo ato inteiro: a pergunta que a trilha
                // precisa responder meses depois é "quanto pagaram à Ana em março, e quem autorizou".
                auditoria.registrar(RegistroDeAuditoria.criacao("fechamento", id, Map.of(
                        "profissionalId", String.valueOf(posicao.profissionalId()),
                        "de", de.toString(),
                        "ate", ate.toString(),
                        "saldoAnterior", posicao.saldoAnterior().toString(),
                        "movimento", posicao.movimento().toString(),
                        "repassado", posicao.aRepassar().toString())));

                feitos.add(new FechamentoDoProfissional(id, posicao.profissionalId(), de, ate,
                        posicao.saldoAnterior(), posicao.movimento(), posicao.aRepassar(), agora));
            }
        } catch (DataIntegrityViolationException e) {
            String detalhe = e.getMostSpecificCause().getMessage();
            if (detalhe != null && detalhe.contains("fechamento_sem_sobreposicao")) {
                throw new ErroDeDominio(ErrosDoFinanceiro.PERIODO_JA_FECHADO,
                        "outro fechamento ocupou este período enquanto a operação era gravada");
            }
            throw e;
        }

        log.info("Período {} a {} fechado: {} em {} repasse(s), {} documento(s)",
                de, ate, total, quantosReceberam, feitos.size());
        return List.copyOf(feitos);
    }
}
