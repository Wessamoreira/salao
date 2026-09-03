package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ErrosDoCatalogo;
import br.com.salao.catalogo.api.Recurso;
import br.com.salao.shared.erro.CampoInvalido;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RT-CAT-003 — um recurso como veio de fora, ainda não validado.
 *
 * <p>Mesma família de {@code EtapaBruta} e {@code PeriodoBruto}: {@link Recurso} recusa entrada
 * inválida com {@code IllegalArgumentException}, correto no domínio e 500 na borda. A
 * {@code quantidade} é {@code Integer} porque campo ausente precisa ser distinguível de zero — com
 * o primitivo, quem não informou nada receberia uma mensagem sobre um número que não digitou.
 *
 * <p>Cadastrar e atualizar validam a mesma coisa, e ela mora num lugar só. Foi assim que a
 * normalização de DDD acabou fora do domínio em RT-CFG-001: escrita duas vezes, corrigida numa.
 */
record RecursoBruto(String nome, Integer quantidade) {

    /** Devolve o recurso validado — sem id nem versão de verdade, que são do banco. */
    Recurso validar() {
        String limpo = nome == null ? null : nome.strip().replaceAll("\\s{2,}", " ");
        if (limpo == null || limpo.isEmpty()) {
            throw new ErroDeDominio(ErrosDoCatalogo.DADOS_INVALIDOS,
                    "o nome do recurso é obrigatório", Map.of(),
                    List.of(new CampoInvalido("nome", "OBRIGATORIO", "Informe o nome")));
        }
        try {
            return new Recurso(UUID.randomUUID(), limpo, quantidade == null ? 1 : quantidade,
                    true, 0);
        } catch (IllegalArgumentException e) {
            throw new ErroDeDominio(ErrosDoCatalogo.DADOS_INVALIDOS, e.getMessage(), Map.of(),
                    List.of(new CampoInvalido("quantidade", "INVALIDO",
                            "Informe ao menos 1 unidade")));
        }
    }
}
