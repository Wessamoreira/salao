package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.internal.infra.ServicosJdbc;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.tempo.Relogio;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-010 / ADR-0011 — o cardápio da parede, para quem não tem conta.
 *
 * <p><strong>Sem {@code @PreAuthorize}, e isso é a decisão, não um esquecimento.</strong> Quem
 * pergunta é uma pessoa sem conta e sem perfil; não há principal a conferir. Está na lista
 * {@code PUBLICOS_DO_BALCAO} do {@code ArquiteturaTest}, que também garante que só a ferramenta do
 * balcão o alcança — nenhum controller, nenhuma ferramenta da equipe.
 *
 * <h2>A tranca é a FORMA, não um filtro</h2>
 *
 * <p>Ele não devolve menos que {@link ListarServicosUseCase} porque esconde campos: devolve menos
 * porque <em>não consulta</em> mais. Não há aqui como chegar a serviço inativo, a id de
 * profissional, a histórico de preço nem a margem — o que sai é nome, duração e o preço de hoje,
 * que é o que está escrito na parede do salão.
 *
 * <p>Filtrar a saída de {@link ListarServicosUseCase} seria a mesma tranca escrita duas vezes, e a
 * segunda é a que deixa passar um campo no dia em que alguém acrescenta um ao DTO.
 */
public class ServicosDoBalcaoUseCase {

    private final ServicosJdbc servicos;
    private final Relogio relogio;

    public ServicosDoBalcaoUseCase(ServicosJdbc servicos, Relogio relogio) {
        this.servicos = servicos;
        this.relogio = relogio;
    }

    /**
     * O que está na parede.
     *
     * @param preco nulo quando o serviço não tem preço vigente — e nulo não é zero: quer dizer
     *              "pergunte no salão", e é assim que a ferramenta o apresenta
     */
    public record ServicoDaParede(String nome, String descricao, int duracaoMinutos, Money preco) {
    }

    @Transactional(readOnly = true)
    public List<ServicoDaParede> executar(ZoneId fusoDoSalao) {
        LocalDate hoje = relogio.hojeEm(fusoDoSalao);
        List<ServicoResumo> ativos = servicos.ativos();
        var precosDeHoje = servicos.precosVigentesEm(
                ativos.stream().map(ServicoResumo::id).toList(), hoje);
        return ativos.stream()
                .map(s -> new ServicoDaParede(s.nome(), s.descricao(), s.duracaoTotal(),
                        precosDeHoje.get(s.id())))
                .toList();
    }
}
