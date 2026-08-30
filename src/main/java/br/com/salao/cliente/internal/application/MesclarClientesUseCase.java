package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ErrosDoCliente;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.cliente.internal.infra.ClientesJdbc.EstadoDaFicha;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CLI-003 — juntar duas fichas da mesma pessoa.
 *
 * <h2>Mesclar é redirecionar</h2>
 *
 * <p>A ficha absorvida não é apagada: ela passa a apontar para a sobrevivente. Assim, tudo que já
 * guardou aquele {@code cliente_id} — agendamento, comanda, extrato de comissão, e o que a Fase 2
 * ainda vai criar — continua resolvendo para a pessoa certa <strong>sem ser reescrito</strong>.
 *
 * <p>Apagar exigiria encontrar e repontar todas as referências numa transação só, e a que fosse
 * esquecida viraria um registro órfão. O redirecionamento falha do lado seguro: referência
 * esquecida continua achando a pessoa.
 *
 * <h2>Permissão: a mesma de cadastrar</h2>
 *
 * <p>Quem cria o duplicado é a recepção, no balcão, e é ela quem percebe na hora. Exigir um perfil
 * mais alto significaria duplicado acumulando até alguém com a permissão certa aparecer — e ficha
 * duplicada é o tipo de sujeira que só piora com o tempo, porque cada uma vai juntando histórico
 * próprio.
 */
public class MesclarClientesUseCase {

    private static final Logger log = LoggerFactory.getLogger(MesclarClientesUseCase.class);

    private final ClientesJdbc clientes;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public MesclarClientesUseCase(ClientesJdbc clientes, AuditoriaApi auditoria, Relogio relogio) {
        this.clientes = clientes;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /**
     * @param sobreviventeId a ficha que fica — normalmente a mais antiga, com mais histórico
     * @param absorvidaId    a ficha que passa a redirecionar para a outra
     */
    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_ESCREVER + "')")
    @Transactional
    public UUID executar(UUID sobreviventeId, UUID absorvidaId) {
        EstadoDaFicha absorvida = ficha(absorvidaId);
        // Se a sobrevivente já foi absorvida por outra, o destino real é aquela outra. Resolver
        // aqui evita criar cadeia e evita recusar uma operação que faz sentido: duas recepções
        // mesclando ao mesmo tempo chegam neste caso sem ter feito nada errado.
        EstadoDaFicha sobrevivente = destinoFinal(ficha(sobreviventeId));

        if (sobrevivente.id().equals(absorvida.id())) {
            throw new ErroDeDominio(ErrosDoCliente.MESCLAGEM_INVALIDA,
                    "as duas fichas são a mesma pessoa neste ponto");
        }
        if (absorvida.mescladoPara() != null) {
            throw new ErroDeDominio(ErrosDoCliente.MESCLAGEM_INVALIDA,
                    "a ficha " + absorvidaId + " já foi mesclada");
        }

        clientes.absorverCampos(sobrevivente.id(), absorvida.id());
        clientes.redirecionar(absorvida.id(), sobrevivente.id(), relogio.agora());

        auditoria.registrar(RegistroDeAuditoria.alteracao("CLIENTE_MESCLADO", "cliente",
                absorvida.id(),
                Map.of("id", absorvida.id().toString()),
                Map.of("mescladoPara", sobrevivente.id().toString())));
        log.info("Cliente {} mesclado em {}", absorvida.id(), sobrevivente.id());
        return sobrevivente.id();
    }

    private EstadoDaFicha ficha(UUID id) {
        EstadoDaFicha estado = clientes.estado(id).orElseThrow(() -> new ErroDeDominio(
                ErrosDoCliente.CLIENTE_NAO_ENCONTRADO, "cliente " + id + " não encontrado"));
        if (estado.anonimizado()) {
            // Mesclar copiaria campos de volta para uma ficha cujos dados o titular pediu para
            // apagar — desfazendo a anonimização por um caminho lateral.
            throw new ErroDeDominio(ErrosDoCliente.MESCLAGEM_INVALIDA,
                    "ficha anonimizada não participa de mesclagem");
        }
        return estado;
    }

    /** Um salto basta: {@code redirecionar} mantém as cadeias planas. */
    private EstadoDaFicha destinoFinal(EstadoDaFicha estado) {
        return estado.mescladoPara() == null ? estado : ficha(estado.mescladoPara());
    }
}
