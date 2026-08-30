package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.internal.domain.Nomes;
import br.com.salao.cliente.internal.domain.Semelhanca;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CLI-003 — avisar, nunca impedir.
 *
 * <h2>Por que isto não bloqueia o cadastro</h2>
 *
 * <p>É a mesma razão pela qual não existe {@code unique} em telefone (RN-CLI-005): recusar um
 * cadastro legítimo ensina a recepção a contornar, e o contorno é sempre pior — "Maria 2", um
 * telefone inventado, um sobrenome trocado. Um aviso que a pessoa pode ignorar em três segundos
 * preserva o fluxo e ainda resolve a maioria dos casos.
 *
 * <p>Por isso a detecção é um endpoint <strong>separado</strong> do cadastro, consultado enquanto
 * se digita (R-UX-15). Embutir a checagem no {@code POST} obrigaria a escolher entre bloquear e
 * ignorar; consultando antes, quem decide é quem está olhando para a cliente.
 */
public class DetectarDuplicadosUseCase {

    /** Rede larga no banco, decisão fina no domínio: sobra é descartada antes de chegar à tela. */
    private static final int CANDIDATOS_EXAMINADOS = 50;

    /** Mais que isso na tela vira lista para ler, não aviso para reconhecer. */
    private static final int AVISOS_EXIBIDOS = 5;

    private final ClientesJdbc clientes;

    public DetectarDuplicadosUseCase(ClientesJdbc clientes) {
        this.clientes = clientes;
    }

    /**
     * @param telefone já normalizado em E.164, ou {@code null}
     * @param ignorar  ficha a desconsiderar — ao editar, ela mesma sempre seria o melhor palpite
     */
    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_LER + "')")
    @Transactional(readOnly = true)
    public List<PossivelDuplicado> executar(String nome, String telefone, UUID ignorar) {
        String limpo = Nomes.opcional(nome);
        if (limpo == null && telefone == null) {
            return List.of();
        }
        String paraComparar = limpo == null ? "" : limpo;

        return clientes.candidatos(paraComparar, telefone, ignorar, CANDIDATOS_EXAMINADOS)
                .stream()
                .flatMap(candidato -> Semelhanca.avaliar(paraComparar,
                                candidato.cliente().nome(), candidato.mesmoTelefone(),
                                candidato.escore())
                        .map(grau -> new PossivelDuplicado(candidato.cliente(), grau))
                        .stream())
                // PROVAVEL antes de POSSIVEL antes de MESMO_TELEFONE: a ordem do enum é a ordem
                // em que a recepção precisa ver, e vale a pena que seja assim de propósito.
                .sorted(Comparator.comparing(PossivelDuplicado::grau))
                .limit(AVISOS_EXIBIDOS)
                .toList();
    }
}
