package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.BlocoDoServico;
import br.com.salao.catalogo.api.ErrosDoCatalogo;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.internal.infra.RecursosJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * De etapas cruas a uma receita válida — o trabalho comum de cadastrar e de editar um serviço.
 *
 * <h2>Por que não é um caso de uso</h2>
 *
 * Porque não é um ato do negócio: ninguém "monta uma receita" como tarefa. É o passo que
 * cadastrar (RT-CAT-001) e editar (RT-CAT-005) fazem igual, e que nenhum dos dois pode pular.
 * Deixá-lo dentro de um deles obrigaria o outro a depender do primeiro — dizendo, na estrutura,
 * que editar é um caso de cadastrar, o que não é verdade.
 *
 * <h2>Por que quem chama nunca constrói {@link SequenciaDeBlocos} sozinho</h2>
 *
 * Ela recusa sequência inválida com {@code IllegalArgumentException} (RN-CAT-001, 002, 003), e
 * essa exceção vinda da borda viraria 500 — erro de servidor para um erro de quem digitou.
 */
class MontadorDeReceita {

    private final RecursosJdbc recursos;

    MontadorDeReceita(RecursosJdbc recursos) {
        this.recursos = recursos;
    }

    SequenciaDeBlocos montar(List<EtapaBruta> etapas) {
        if (etapas == null || etapas.isEmpty()) {
            throw new ErroDeDominio(ErrosDoCatalogo.SEQUENCIA_INVALIDA,
                    "o serviço precisa de ao menos uma etapa");
        }
        SequenciaDeBlocos sequencia;
        try {
            sequencia = new SequenciaDeBlocos(etapas.stream()
                    .map(etapa -> new BlocoDoServico(etapa.tipo(), etapa.duracaoMinutos(),
                            etapa.recursosExigidos()))
                    .toList());
        } catch (IllegalArgumentException e) {
            throw new ErroDeDominio(ErrosDoCatalogo.SEQUENCIA_INVALIDA, e.getMessage());
        }
        conferirRecursos(sequencia.recursosExigidos());
        return sequencia;
    }

    /**
     * RT-CAT-003 — os recursos exigidos existem, estão ativos e são deste salão.
     *
     * <p>As três perguntas numa consulta só, e a terceira é a que importa: no PostgreSQL as
     * checagens de chave estrangeira rodam como o dono da tabela e <strong>não passam pela
     * RLS</strong>. A FK de {@code bloco_exige_recurso} aceitaria um recurso de outro salão sem
     * reclamar — é aqui, numa consulta comum sujeita à política de tenant, que ele não volta.
     */
    private void conferirRecursos(Set<UUID> exigidos) {
        if (exigidos.isEmpty()) {
            return;
        }
        Set<UUID> encontrados = recursos.idsAtivos(exigidos);
        var faltando = new LinkedHashSet<>(exigidos);
        faltando.removeAll(encontrados);
        if (!faltando.isEmpty()) {
            // TreeSet só para a mensagem sair estável: um erro que muda de texto a cada execução
            // é um erro que ninguém consegue procurar no log.
            var ordenados = new TreeSet<String>();
            faltando.forEach(id -> ordenados.add(id.toString()));
            throw new ErroDeDominio(ErrosDoCatalogo.RECURSO_NAO_ENCONTRADO,
                    "recurso inexistente ou inativo: " + String.join(", ", ordenados),
                    Map.of("recursos", List.copyOf(ordenados)));
        }
    }
}
