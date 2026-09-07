package br.com.salao.financeiro.api;

import java.util.UUID;

/**
 * RT-FIN-002 — contrato da cascata de comissão.
 *
 * <p>Quem lança um item pergunta <em>"quanto esta pessoa ganha por este serviço?"</em> e recebe o
 * percentual junto com o nível que o decidiu. Não recebe as regras: compor a cascata do lado de
 * fora seria a mesma decisão escrita em dois lugares, e a segunda cópia é a que continua pagando
 * errado depois que a primeira mudar.
 */
public interface ComissaoApi {

    /**
     * O percentual que vale para este profissional neste serviço, agora.
     *
     * <p>Percorre, do mais específico para o mais geral: regra do par profissional × serviço,
     * regra do serviço, percentual da ficha do profissional, padrão do salão. Nada configurado em
     * nenhum nível devolve {@link ComissaoResolvida#semRegra()}.
     */
    ComissaoResolvida resolver(UUID profissionalId, UUID servicoId);
}
