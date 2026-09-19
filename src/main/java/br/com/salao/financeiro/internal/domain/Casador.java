package br.com.salao.financeiro.internal.domain;

import br.com.salao.financeiro.api.FormaDePagamento;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * RT-FIN-010 — casa pagamentos do salão com vendas da adquirente (RN-FIN-029 a 031).
 *
 * <h2>Três passadas, da prova mais forte para a mais fraca</h2>
 *
 * <ol>
 *   <li><strong>NSU igual</strong> — e só um de cada lado. NSU repetido não prova nada, e cai para as
 *       passadas seguintes;
 *   <li><strong>autorização igual, dentro da janela</strong> — o código se repete ao longo do tempo, e
 *       sozinho não prova nada;
 *   <li><strong>valor exato, dentro da janela</strong> — todos os pares possíveis, do mais próximo no tempo
 *       para o mais distante; na mesma distância, a mesma forma primeiro. Cada lado casa uma vez.
 * </ol>
 *
 * <h2>A distância antes da forma</h2>
 *
 * <p>O pagamento é registrado logo depois de passar o cartão: o tempo é a prova física. Preferir a mesma
 * forma a qualquer distância casaria um crédito com a venda de crédito de uma hora depois, e deixaria sem
 * par a venda de um minuto antes que foi registrada como débito por engano — que é o caso comum.
 *
 * <p>Empate de valor na janela casa pela ordem de horário (decisão do dono): trocar dois pares de mesmo
 * valor não muda dinheiro. Ordem total no fim, pelos ids, para a mesma entrada dar sempre o mesmo par.
 */
public final class Casador {

    private Casador() {
    }

    /** Um pagamento no cartão, ainda sem par. {@code parcelas} 1 é o que o sistema assume sem informação. */
    public record Pagamento(UUID id, BigDecimal valor, FormaDePagamento forma, Instant registradoEm,
                            String nsu, String autorizacao, String bandeira, int parcelas) {
    }

    /** Uma venda aprovada na adquirente, ainda sem par. */
    public record Venda(UUID id, BigDecimal valor, ProdutoDoCartao produto, Instant capturadaEm,
                        boolean horaInformada, String nsu, String autorizacao, String bandeira,
                        int parcelas) {
    }

    public record Par(UUID pagamentoId, UUID vendaId, CriterioDoCasamento criterio,
                      Set<Divergencia> divergencias) {

        public boolean divergente() {
            return !divergencias.isEmpty();
        }
    }

    public static List<Par> casar(Collection<Pagamento> pagamentos, Collection<Venda> vendas,
                                  Duration janela, ZoneId fuso) {
        Map<UUID, Pagamento> livresP = new LinkedHashMap<>();
        pagamentos.forEach(p -> livresP.put(p.id(), p));
        Map<UUID, Venda> livresV = new LinkedHashMap<>();
        vendas.forEach(v -> livresV.put(v.id(), v));
        List<Par> pares = new ArrayList<>();

        porCodigo(livresP, livresV, pares, Pagamento::nsu, Venda::nsu, CriterioDoCasamento.NSU,
                (p, v) -> true);
        porCodigo(livresP, livresV, pares, Pagamento::autorizacao, Venda::autorizacao,
                CriterioDoCasamento.AUTORIZACAO, (p, v) -> dentroDaJanela(p, v, janela, fuso));

        record Candidato(Pagamento pagamento, Venda venda, long distancia, boolean formaDiferente) {
        }
        List<Candidato> candidatos = new ArrayList<>();
        for (Pagamento p : livresP.values()) {
            for (Venda v : livresV.values()) {
                if (p.valor().compareTo(v.valor()) == 0 && dentroDaJanela(p, v, janela, fuso)) {
                    candidatos.add(new Candidato(p, v,
                            Duration.between(v.capturadaEm(), p.registradoEm()).abs().toSeconds(),
                            formaDiferente(p, v)));
                }
            }
        }
        candidatos.sort(Comparator.comparingLong(Candidato::distancia)
                .thenComparing(Candidato::formaDiferente)
                .thenComparing(c -> c.pagamento().registradoEm())
                .thenComparing(c -> c.venda().capturadaEm())
                .thenComparing(c -> c.pagamento().id())
                .thenComparing(c -> c.venda().id()));
        for (Candidato c : candidatos) {
            if (livresP.containsKey(c.pagamento().id()) && livresV.containsKey(c.venda().id())) {
                livresP.remove(c.pagamento().id());
                livresV.remove(c.venda().id());
                pares.add(new Par(c.pagamento().id(), c.venda().id(),
                        CriterioDoCasamento.VALOR_E_HORA, divergencias(c.pagamento(), c.venda())));
            }
        }
        return pares;
    }

    /** Casa pelo código quando ele é único dos dois lados — código repetido não prova nada. */
    private static void porCodigo(Map<UUID, Pagamento> livresP, Map<UUID, Venda> livresV,
                                  List<Par> pares, Function<Pagamento, String> doPagamento,
                                  Function<Venda, String> daVenda, CriterioDoCasamento criterio,
                                  BiPredicate<Pagamento, Venda> tambem) {
        Map<String, List<Pagamento>> pagamentosPorCodigo = new LinkedHashMap<>();
        for (Pagamento p : livresP.values()) {
            String codigo = codigo(doPagamento.apply(p));
            if (codigo != null) {
                pagamentosPorCodigo.computeIfAbsent(codigo, k -> new ArrayList<>()).add(p);
            }
        }
        Map<String, List<Venda>> vendasPorCodigo = new LinkedHashMap<>();
        for (Venda v : livresV.values()) {
            String codigo = codigo(daVenda.apply(v));
            if (codigo != null) {
                vendasPorCodigo.computeIfAbsent(codigo, k -> new ArrayList<>()).add(v);
            }
        }
        for (var entrada : pagamentosPorCodigo.entrySet()) {
            List<Venda> vendasDoCodigo = vendasPorCodigo.get(entrada.getKey());
            if (vendasDoCodigo == null || entrada.getValue().size() != 1 || vendasDoCodigo.size() != 1) {
                continue;
            }
            Pagamento p = entrada.getValue().getFirst();
            Venda v = vendasDoCodigo.getFirst();
            if (tambem.test(p, v)) {
                livresP.remove(p.id());
                livresV.remove(v.id());
                pares.add(new Par(p.id(), v.id(), criterio, divergencias(p, v)));
            }
        }
    }

    /** Sem hora no arquivo, o mesmo dia do salão faz as vezes da janela. */
    static boolean dentroDaJanela(Pagamento p, Venda v, Duration janela, ZoneId fuso) {
        if (!v.horaInformada()) {
            return p.registradoEm().atZone(fuso).toLocalDate()
                    .equals(v.capturadaEm().atZone(fuso).toLocalDate());
        }
        return Duration.between(v.capturadaEm(), p.registradoEm()).abs().compareTo(janela) <= 0;
    }

    static Set<Divergencia> divergencias(Pagamento p, Venda v) {
        var divergencias = EnumSet.noneOf(Divergencia.class);
        if (p.valor().compareTo(v.valor()) != 0) {
            divergencias.add(Divergencia.VALOR);
        }
        if (formaDiferente(p, v)) {
            divergencias.add(Divergencia.FORMA);
        }
        if (p.bandeira() != null && v.bandeira() != null
                && !codigo(p.bandeira()).equals(codigo(v.bandeira()))) {
            divergencias.add(Divergencia.BANDEIRA);
        }
        if (p.parcelas() > 1 && p.parcelas() != v.parcelas()) {
            divergencias.add(Divergencia.PARCELAS);
        }
        return Collections.unmodifiableSet(divergencias);
    }

    /** Venda sem produto, ou "outro", não diz a forma — e não diverge por ela. */
    static boolean formaDiferente(Pagamento p, Venda v) {
        if (v.produto() == null || v.produto() == ProdutoDoCartao.OUTRO) {
            return false;
        }
        return (p.forma() == FormaDePagamento.CREDITO) != (v.produto() == ProdutoDoCartao.CREDITO);
    }

    /** "000123", "123" e " 123 " são o mesmo NSU; autorização sem caixa nem espaço. */
    static String codigo(String texto) {
        if (texto == null) {
            return null;
        }
        String limpo = texto.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (limpo.matches("\\d+")) {
            limpo = limpo.replaceFirst("^0+(?=\\d)", "");
        }
        return limpo.isEmpty() ? null : limpo;
    }
}
