package br.com.salao.estoque.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * RT-EST-007 — o que a execução de um serviço tirou do estoque.
 *
 * @param linhas uma por insumo da ficha. Vazia quando o serviço não tem ficha — e não ter ficha é
 *               o caso normal de corte, escova e manicure
 */
public record ConsumoDeServico(UUID servicoId, List<LinhaConsumida> linhas) {

    /**
     * @param desejada o que a ficha técnica pedia
     * @param baixada  o que havia. Menor que a desejada quando o insumo acabou
     */
    public record LinhaConsumida(
            UUID produtoId,
            String produto,
            BigDecimal desejada,
            BigDecimal baixada) {

        public BigDecimal faltou() {
            return desejada.subtract(baixada);
        }

        public boolean completa() {
            return faltou().signum() <= 0;
        }
    }

    /**
     * Os insumos que não deram para baixar por inteiro.
     *
     * <p>É a informação que o log e a auditoria carregam. Não é erro: o serviço já foi executado,
     * e um insumo que o sistema diz não existir ou estava lá (o saldo está errado) ou foi
     * substituído — nos dois casos, o registro honesto é "tirei o que havia".
     */
    public List<LinhaConsumida> incompletas() {
        return linhas.stream().filter(linha -> !linha.completa()).toList();
    }
}
