package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.internal.domain.BotaoDeAceite;
import br.com.salao.conversacional.internal.domain.CodigoDeConfirmacao;
import br.com.salao.conversacional.internal.infra.IntencoesJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.agente.FerramentaDeEscrita;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * RT-CNV-005 — guardar o que o agente propôs, com o pedido de confirmação já escrito.
 *
 * <p>Um commit só: a pendente anterior do mesmo telefone é encerrada e a nova entra, com o código
 * sorteado e o texto que a pessoa vai ler. Separados, uma queda entre eles deixaria o telefone com
 * duas pendentes — ou sem nenhuma, e com um código que ninguém guardou.
 *
 * <p>Uma pendente por telefone (RN-CNV-015): duas ao mesmo tempo fariam a pessoa confirmar uma
 * olhando para a outra.
 *
 * <p>RT-CNV-006: quando a simulação pede o aceite de alguém, quem, o número e o token do botão são
 * gravados aqui, junto com o resto do que foi proposto — e o pedido de confirmação diz que, depois do
 * SIM, ainda falta esse aceite.
 */
public class ProporIntencao {

    /** Não repetir código que o mesmo telefone recebeu no último dia. */
    private static final Duration SEM_REPETIR = Duration.ofDays(1);

    private final IntencoesJdbc intencoes;
    private final EstabelecimentoApi estabelecimentos;
    private final RandomGenerator aleatorio;
    private final Relogio relogio;
    private final Duration validade;

    public ProporIntencao(IntencoesJdbc intencoes, EstabelecimentoApi estabelecimentos,
                          RandomGenerator aleatorio, Relogio relogio, Duration validade) {
        if (validade == null || validade.isNegative() || validade.isZero()) {
            throw new IllegalArgumentException("a validade da intenção precisa ser positiva");
        }
        this.intencoes = intencoes;
        this.estabelecimentos = estabelecimentos;
        this.aleatorio = aleatorio;
        this.relogio = relogio;
        this.validade = validade;
    }

    /**
     * @param argumentos o que o modelo pediu, para o rastro
     * @param simulacao  o que a ferramenta simulou, em nome de {@code usuarioEfetivoId}
     */
    public record Proposta(UUID execucaoId, String telefone, UUID usuarioEfetivoId,
                           String ferramenta, JsonNode argumentos,
                           FerramentaDeEscrita.Simulacao simulacao) {
    }

    public record Registrada(UUID id, String codigo, String pedidoDeConfirmacao, Instant expiraEm) {
    }

    @Transactional
    public Registrada executar(Proposta proposta) {
        var simulacao = proposta.simulacao();
        if (simulacao == null || simulacao.resumo() == null || simulacao.resumo().isBlank()
                || simulacao.comando() == null || !simulacao.comando().isObject()) {
            // Defeito da ferramenta, não da conversa: sem resumo não há o que a pessoa confirmar, e
            // sem comando não há o que executar.
            throw new IllegalStateException(
                    "a ferramenta " + proposta.ferramenta() + " simulou sem resumo ou sem comando");
        }
        var aceite = simulacao.aceite();
        if (aceite != null && (aceite.telefone() == null
                || !aceite.telefone().matches("[0-9]{10,15}") || aceite.nome() == null)) {
            throw new IllegalStateException(
                    "a ferramenta " + proposta.ferramenta() + " pediu aceite sem nome ou telefone");
        }
        UUID salao = TenantContext.obrigatorio();
        intencoes.travarTelefone(salao, proposta.telefone());

        Instant agora = relogio.agora();
        String codigo = CodigoDeConfirmacao.sortear(aleatorio,
                intencoes.codigosDesde(proposta.telefone(), agora.minus(SEM_REPETIR)));
        Instant expira = agora.plus(validade);
        String pedido = CodigoDeConfirmacao.pedido(simulacao.resumo(), codigo,
                expira.atZone(estabelecimentos.fusoDe(salao)).toLocalTime(),
                aceite == null ? null : aceite.nome(), aceite == null ? null : aceite.motivo());

        intencoes.encerrarPendente(proposta.telefone(), agora);
        UUID id = intencoes.inserir(salao, new IntencoesJdbc.Nova(proposta.execucaoId(),
                proposta.telefone(), proposta.usuarioEfetivoId(), proposta.ferramenta(),
                proposta.argumentos() == null ? JsonNodeFactory.instance.objectNode()
                        : proposta.argumentos(),
                simulacao.comando(), simulacao.resumo(), pedido, codigo, agora, expira,
                aceite == null ? null : new IntencoesJdbc.Aceite(aceite.nome(), aceite.telefone(),
                        aceite.motivo(), BotaoDeAceite.novoToken(aleatorio), null)));
        return new Registrada(id, codigo, pedido, expira);
    }
}
