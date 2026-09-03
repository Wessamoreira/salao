package br.com.salao.catalogo.api;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * RT-CAT-001 — um trecho do serviço, com duração fixa.
 *
 * <p>RT-CAT-003 acrescentou os recursos: quais coisas contáveis do salão este trecho ocupa
 * (lavatório, cadeira, sala). A exigência mora <strong>no bloco</strong> e não no serviço porque a
 * coloração ocupa o lavatório só na lavagem — 30 minutos de 100. Pendurá-la no serviço reservaria
 * o lavatório pelos 100, que é o mesmo desperdício que os blocos existem para eliminar.
 *
 * <p>E um bloco {@code PAUSA} pode exigir recurso: durante a química a profissional está livre,
 * mas a cliente continua ocupando a cadeira. Um modelo que não saiba dizer isso vende a mesma
 * cadeira duas vezes.
 *
 * @param duracaoMinutos sempre maior que zero; bloco de duração nula não é etapa, é ruído
 * @param recursosExigidos ids dos recursos ocupados durante o bloco; vazio é o caso comum
 */
public record BlocoDoServico(TipoDeBloco tipo, int duracaoMinutos, Set<UUID> recursosExigidos) {

    public BlocoDoServico {
        if (tipo == null) {
            throw new IllegalArgumentException("o tipo do bloco é obrigatório");
        }
        if (duracaoMinutos <= 0) {
            throw new IllegalArgumentException("duração precisa ser positiva: " + duracaoMinutos);
        }
        // LinkedHashSet antes do copyOf: preserva a ordem em que o salão declarou, o que faz a
        // mensagem de erro e a trilha saírem estáveis em vez de embaralhadas a cada execução.
        recursosExigidos = recursosExigidos == null
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(recursosExigidos));
    }

    /** O caso comum: um bloco que não disputa equipamento com ninguém. */
    public BlocoDoServico(TipoDeBloco tipo, int duracaoMinutos) {
        this(tipo, duracaoMinutos, Set.of());
    }

    public static BlocoDoServico ativo(int minutos) {
        return new BlocoDoServico(TipoDeBloco.ATIVO, minutos);
    }

    public static BlocoDoServico pausa(int minutos) {
        return new BlocoDoServico(TipoDeBloco.PAUSA, minutos);
    }

    public BlocoDoServico exigindo(UUID... recursos) {
        return new BlocoDoServico(tipo, duracaoMinutos, Set.of(recursos));
    }
}
