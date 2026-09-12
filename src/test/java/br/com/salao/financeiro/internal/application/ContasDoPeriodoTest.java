package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RT-FIN-006 — a ordem das posições é parte do contrato.
 *
 * <h2>Por que este teste é unitário, e não de integração</h2>
 *
 * <p>A ordem que precisa ser garantida é a de saída de dois {@code group by} SEM
 * {@code order by} — e o PostgreSQL não promete ordem ali: com {@code HashAggregate} ela
 * depende do layout do hash, e o planejador troca de estratégia conforme estatística e
 * {@code work_mem}.
 *
 * <p>A primeira tentativa deste teste foi de integração, e ele <strong>passou com o
 * {@code sort} removido</strong>: com quatro linhas numa tabela pequena, o banco devolveu numa
 * ordem que por sorte estava crescente. Um teste que só falha em 96% das execuções não é
 * guarda, é ruído — e o que ele estava medindo era o humor do planejador.
 *
 * <p>Aqui o repositório é dublê e devolve as chaves <strong>ao contrário</strong>. Assim a
 * ordenação de {@code ContasDoPeriodo} é a única coisa capaz de fazer a saída sair crescente:
 * apague aquela linha e este teste fica vermelho, sempre.
 *
 * <h2>O que aquela linha protege</h2>
 *
 * <p>{@code VersaoDaPrevia} faz SHA-256 desta lista para detectar mudança entre a prévia e a
 * confirmação. Prévia e confirmação são consultas separadas: sem ordem estável, o mesmo dado
 * gera hashes diferentes e o fechamento recusa com "os valores mudaram" sem nada ter mudado —
 * o dono atualizando a tela para receber a mesma recusa, sem conseguir pagar ninguém.
 */
class ContasDoPeriodoTest {

    private static final LocalDate DE = LocalDate.of(2026, 3, 1);
    private static final LocalDate ATE = LocalDate.of(2026, 3, 31);

    @Test
    @DisplayName("as posições saem em ordem estável mesmo quando o banco devolve ao contrário")
    void ordem_nao_depende_do_banco() {
        // Ordenados por texto e depois invertidos: é a pior ordem possível de receber.
        List<UUID> ids = List.of(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        List<UUID> aoContrario = ids.reversed();

        LancamentosJdbc lancamentos = mock(LancamentosJdbc.class);
        when(lancamentos.saldosAcumuladosAte(anyString(), any()))
                .thenReturn(mapa(aoContrario, "-10"));
        when(lancamentos.saldosNoPeriodo(anyString(), any(), any()))
                .thenReturn(mapa(aoContrario, "-60"));

        var posicoes = new ContasDoPeriodo(lancamentos).em(DE, ATE);

        assertThat(posicoes).hasSize(4);
        assertThat(posicoes)
                .extracting(p -> p.profissionalId().toString())
                .containsExactlyElementsOf(ids.stream().map(UUID::toString).toList());
    }

    @Test
    @DisplayName("quem só tem saldo anterior entra na lista, e na mesma ordem")
    void saldo_anterior_sozinho_entra() {
        // A dívida de quem não trabalhou no período precisa aparecer: sumir da lista faria o
        // salão perder o dinheiro sem nada acusar.
        UUID soAntes = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID soAgora = UUID.fromString("99999999-9999-4999-8999-999999999999");

        LancamentosJdbc lancamentos = mock(LancamentosJdbc.class);
        when(lancamentos.saldosAcumuladosAte(anyString(), any()))
                .thenReturn(mapa(List.of(soAntes), "40"));
        when(lancamentos.saldosNoPeriodo(anyString(), any(), any()))
                .thenReturn(mapa(List.of(soAgora), "-60"));

        var posicoes = new ContasDoPeriodo(lancamentos).em(DE, ATE);

        assertThat(posicoes).extracting(p -> p.profissionalId().toString())
                .containsExactly(soAntes.toString(), soAgora.toString());
        // Saldo do razão +40 significa que a PESSOA devia: invertido, −40.
        assertThat(posicoes.getFirst().saldoAnterior()).isEqualTo(Money.de("-40"));
        assertThat(posicoes.getFirst().aRepassar())
                .as("quem deve não gera repasse negativo").isEqualTo(Money.ZERO);
    }

    /** `LinkedHashMap` para a ordem de inserção ser exatamente a que o teste escolheu. */
    private static Map<UUID, Money> mapa(List<UUID> ids, String valor) {
        Map<UUID, Money> saldos = new LinkedHashMap<>();
        for (UUID id : ids) {
            saldos.put(id, Money.de(valor));
        }
        return saldos;
    }
}
