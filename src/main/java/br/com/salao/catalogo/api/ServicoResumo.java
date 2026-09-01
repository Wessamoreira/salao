package br.com.salao.catalogo.api;

import java.util.UUID;

/**
 * RT-CAT-001 — o serviço como os outros módulos o veem.
 *
 * <p>Sem preço: ele tem vigência e histórico próprios (RT-CAT-002), e embuti-lo aqui faria a
 * resposta desta consulta significar coisas diferentes conforme a data.
 */
public record ServicoResumo(
        UUID id,
        String nome,
        String descricao,
        SequenciaDeBlocos blocos,
        boolean ativo,
        int versao) {

    public int duracaoTotal() {
        return blocos.duracaoTotal();
    }

    public int minutosDoProfissional() {
        return blocos.minutosDoProfissional();
    }
}
