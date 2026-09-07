package br.com.salao.financeiro.internal.application;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.api.Vale;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.financeiro.internal.infra.ValesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.idempotencia.ChaveDeIdempotencia;
import br.com.salao.shared.idempotencia.Idempotencia;
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
 * RT-FIN-005 — registrar um adiantamento.
 *
 * <h2>Duas escritas, uma transação</h2>
 *
 * <p>O {@code vale} guarda o <strong>fato</strong> — de quem, quanto, por qual meio o dinheiro saiu
 * e por quê. O razão guarda o <strong>efeito</strong> — menos a receber. As duas commitam juntas:
 * um vale sem lançamento seria dinheiro entregue que ninguém desconta, e um lançamento sem vale
 * seria um desconto que ninguém consegue explicar. Das duas, a primeira é a que custa caro, e é
 * exatamente a que uma transação separada produziria.
 *
 * <h2>O sinal é positivo, e é isso que reduz o que a pessoa tem a receber</h2>
 *
 * <p>No razão, a comissão é negativa: sai do salão. O vale é <strong>positivo</strong> na mesma
 * conta — ele devolve ao salão parte do que estava devendo. O extrato inverte tudo uma vez
 * (RN-FIN-011), e é lá que o vale aparece com o sinal de menos que a pessoa espera ver.
 *
 * <h2>Não há teto</h2>
 *
 * <p>Adiantar mais do que a pessoa já ganhou é o que "adiantamento" significa, e recusar seria
 * inventar uma regra que o salão não tem. O saldo negativo é legítimo e atravessa para o próximo
 * período — quando RT-FIN-006 existir, é ele que vai carregá-lo.
 *
 * <p>Comparar com um saldo aqui exigiria escolher um período, e escolher um período no lugar de
 * quem pergunta é exatamente o que RT-FIN-004 recusou fazer no extrato.
 */
public class RegistrarValeUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegistrarValeUseCase.class);

    static final String ORIGEM_VALE = "VALE";

    private final ValesJdbc vales;
    private final LancamentosJdbc lancamentos;
    private final ProfissionalApi profissionais;
    private final Competencia competencia;
    private final AuditoriaApi auditoria;
    private final Idempotencia idempotencia;
    private final Relogio relogio;

    public RegistrarValeUseCase(ValesJdbc vales, LancamentosJdbc lancamentos,
                                ProfissionalApi profissionais, Competencia competencia,
                                AuditoriaApi auditoria, Idempotencia idempotencia,
                                Relogio relogio) {
        this.vales = vales;
        this.lancamentos = lancamentos;
        this.profissionais = profissionais;
        this.competencia = competencia;
        this.auditoria = auditoria;
        this.idempotencia = idempotencia;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_ADIANTAR + "')")
    @Transactional
    public Vale executar(NovoVale pedido) {
        if (pedido.chaveIdempotencia() == null) {
            return registrar(pedido);
        }
        // Dentro da transação de propósito (RT-INF-005): o registro de idempotência precisa
        // commitar junto com o vale. Fora dela existe a janela em que o dinheiro saiu e o registro
        // não — e a repetição entrega o segundo.
        return idempotencia.executar(
                new ChaveDeIdempotencia("registrar-vale", pedido.chaveIdempotencia()),
                pedido, Vale.class, () -> registrar(pedido)).valor();
    }

    private Vale registrar(NovoVale pedido) {
        UUID tenant = TenantContext.obrigatorio();
        valorValido(pedido.valor());
        if (pedido.forma() == null) {
            // Sem a forma não se sabe se falta dinheiro na gaveta ou se saiu do banco — e é essa a
            // conta que o dono faz no fim do dia.
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "informe por onde o dinheiro saiu");
        }
        // Confere pela API do dono do dado: não há FK para profissional (RN-EQP-015), e um vale
        // apontando para alguém de outro salão ficaria gravado sem nunca aparecer em extrato
        // nenhum — silencioso, que é a pior forma de errar com dinheiro.
        if (pedido.profissionalId() == null || profissionais.porId(pedido.profissionalId())
                .isEmpty()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "profissional não encontrado");
        }

        LocalDate quando = competencia.hoje();
        Instant agora = relogio.agora();
        UUID por = UsuarioAtual.idOuNulo();

        UUID id = vales.inserir(tenant, pedido.profissionalId(), pedido.valor(), pedido.forma(),
                quando, pedido.motivo(), agora, por);

        lancamentos.inserir(tenant, quando, TipoDeLancamento.VALE, ORIGEM_VALE, id,
                "PROFISSIONAL", pedido.profissionalId(),
                // Positivo: devolve ao salão parte do que ele estava devendo.
                pedido.valor(), descricao(pedido), null, agora, por);

        auditoria.registrar(RegistroDeAuditoria.criacao("vale", id, Map.of(
                "profissionalId", String.valueOf(pedido.profissionalId()),
                "valor", pedido.valor().toString(),
                "forma", pedido.forma().name(),
                // `String.valueOf` e não um vazio: motivo ausente é diferente de motivo em branco,
                // e a trilha registra o que aconteceu, não o que ficaria bonito.
                "motivo", String.valueOf(pedido.motivo()))));

        log.info("Vale {} de {} para o profissional {} em {}", id, pedido.valor(),
                pedido.profissionalId(), pedido.forma());
        return vales.porId(id).orElseThrow();
    }

    /**
     * O que a pessoa lê no extrato, sem abrir mais nada.
     *
     * <p>A linha do razão é a única coisa que sobrevive a tudo, e ela precisa se explicar sozinha —
     * a mesma razão pela qual a comissão carrega o percentual e a origem da regra (RT-FIN-003).
     */
    private String descricao(NovoVale pedido) {
        String base = "Vale em " + pedido.forma().name().toLowerCase();
        return pedido.motivo() == null || pedido.motivo().isBlank()
                ? base
                : base + " — " + pedido.motivo().strip();
    }

    /**
     * O banco já recusa (V41: {@code check (valor > 0)}); isto recusa com uma frase.
     *
     * <p>É a divisão de sempre: o banco garante, a aplicação valida por educação. Sem isto, quem
     * digitou zero receberia uma violação de constraint que não diz o que fazer.
     */
    private void valorValido(Money valor) {
        if (valor == null || valor.ehZero() || valor.ehNegativo()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "o vale precisa de um valor positivo; veio " + valor);
        }
    }
}
