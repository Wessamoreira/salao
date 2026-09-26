package br.com.salao.financeiro.internal.application;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.Lancamento;
import br.com.salao.financeiro.api.TipoDeLancamento;
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
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-013 / RN-FIN-041 — corrigir o razão à mão, com motivo.
 *
 * <h2>Era o caminho que a regra oferecia e não existia</h2>
 *
 * <p>RN-FIN-023 recusa estornar um estorno e diz o que fazer no lugar: <em>"quem quer o valor de
 * volta faz um lançamento novo, com motivo e rastro próprio"</em>. Esse lançamento não existia —
 * {@code AJUSTE} e {@code origem_tipo = 'MANUAL'} estavam no modelo sem nada que os escrevesse.
 *
 * <p>Sem ele, uma comissão apurada errada podia ser <strong>anulada</strong> e não
 * <strong>corrigida</strong>: o extrato ficava certo e a pessoa sem o dinheiro. A saída
 * improvisada seria um vale, que mente — vale é adiantamento, e apareceria no relatório de
 * adiantamento de quem nunca adiantou nada.
 *
 * <h2>O sinal entra invertido</h2>
 *
 * <p>No razão a comissão é negativa, porque sai do salão. Um ajuste que <em>aumenta</em> o que a
 * pessoa recebe é, lá dentro, negativo. Pedir esse sinal a quem digita seria pedir que ela
 * conhecesse a contabilidade do razão para consertar um erro de digitação — e errar o sinal num
 * ajuste custa o dobro do valor, na direção errada.
 *
 * <p>O extrato já inverte uma vez (RN-FIN-011). Aqui é a mesma inversão, do outro lado da porta.
 *
 * <h2>"NoRazao" no nome, e não é enfeite</h2>
 *
 * <p>O estoque tem o seu {@code RegistrarAjusteUseCase} (RT-EST-005), e os dois nomes simples
 * iguais colidiram como bean na primeira execução. O nome longo distingue o que são duas coisas
 * diferentes: ajustar estoque é corrigir contagem; ajustar o razão é caneta no livro.
 *
 * <h2>{@code financeiro:close}, e não {@code advance}</h2>
 *
 * <p>Escrever direto no razão é o ato mais perigoso do módulo, e quem o faz é quem responde pelo
 * repasse. Adiantar é instrumento com fato próprio; ajustar é caneta no livro.
 */
public class RegistrarAjusteNoRazaoUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegistrarAjusteNoRazaoUseCase.class);

    static final String ORIGEM_MANUAL = "MANUAL";

    private final LancamentosJdbc lancamentos;
    private final ProfissionalApi profissionais;
    private final Competencia competencia;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public RegistrarAjusteNoRazaoUseCase(LancamentosJdbc lancamentos, ProfissionalApi profissionais,
                                  Competencia competencia, AuditoriaApi auditoria,
                                  Relogio relogio) {
        this.lancamentos = lancamentos;
        this.profissionais = profissionais;
        this.competencia = competencia;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /**
     * @param valor no <strong>sinal de quem recebe</strong>: positivo aumenta o que a pessoa tem a
     *              receber. Nunca zero — um ajuste de zero é um motivo sem efeito
     */
    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_FECHAR + "')")
    @Transactional
    public Lancamento executar(UUID profissionalId, Money valor, String motivo) {
        if (valor == null || valor.ehZero()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "o ajuste precisa de um valor diferente de zero");
        }
        if (motivo == null || motivo.isBlank()) {
            // O motivo é o que separa correção de mexida silenciosa no livro. Sem ele, a linha
            // aparece no extrato de alguém sem nada que a explique — e é a primeira que vai ser
            // questionada, porque não veio de comanda nenhuma.
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "descreva por que o ajuste está sendo feito");
        }
        // Confere pela API do dono do dado: não há FK para profissional (RN-EQP-015), e um ajuste
        // apontando para alguém de outro salão ficaria gravado sem aparecer em extrato nenhum.
        if (profissionalId == null || profissionais.porId(profissionalId).isEmpty()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "profissional não encontrado");
        }

        UUID tenant = TenantContext.obrigatorio();
        LocalDate quando = competencia.hoje();
        Instant agora = relogio.agora();
        UUID por = UsuarioAtual.idOuNulo();

        // A inversão (RN-FIN-041): o que a pessoa recebe a mais sai do salão.
        Money noRazao = valor.negado();

        /*
         * O ajuste é o próprio documento.
         *
         * `origem_id` é `not null` (V40), e com razão: toda linha do razão aponta para o fato que
         * a produziu — a comanda, o vale, o fechamento. O ajuste não tem tabela própria, e o
         * documento dele é ele mesmo. Um id sorteado aqui mantém o par `(origem_tipo, origem_id)`
         * significando a mesma coisa que significa nas outras linhas, e faz
         * `estornarOrigem("MANUAL", id)` funcionar como funciona para as demais.
         *
         * A alternativa — apontar para o profissional — faria `origem_id` repetir
         * `contraparte_id` e agrupar num "documento" todos os ajustes daquela pessoa: estornar um
         * estornaria todos.
         */
        UUID documento = UUID.randomUUID();

        UUID id = lancamentos.inserir(tenant, quando, TipoDeLancamento.AJUSTE, ORIGEM_MANUAL,
                documento, "PROFISSIONAL", profissionalId, noRazao,
                "Ajuste — " + motivo.strip(), null, agora, por);

        auditoria.registrar(RegistroDeAuditoria.criacao("ajuste_de_razao", id, Map.of(
                "profissionalId", String.valueOf(profissionalId),
                "valorParaQuemRecebe", valor.toString(),
                "valorNoRazao", noRazao.toString(),
                "motivo", motivo.strip())));

        // WARN e não INFO: é escrita manual no livro-razão, e o dono quer ver isso no log.
        log.warn("Ajuste manual de {} para o profissional {} em {}: {}",
                valor, profissionalId, quando, motivo.strip());

        return lancamentos.porId(id).orElseThrow();
    }
}
