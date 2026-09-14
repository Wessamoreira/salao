package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.api.ErrosDoConversacional;
import br.com.salao.conversacional.internal.infra.IntencoesJdbc;
import br.com.salao.iam.api.UsuarioEfetivoApi;
import br.com.salao.shared.agente.FerramentaDeEscrita;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-005 — a pessoa respondeu com o código: executar o que ela confirmou, e só isso.
 *
 * <h2>Um commit para as duas coisas — RN-CNV-016</h2>
 *
 * <p>A intenção vira CONFIRMADA na MESMA transação em que a ferramenta grava. É o que dá "no máximo
 * uma vez" sem chave de idempotência: se o processo cair entre a gravação e a marcação, não houve
 * gravação; e duas confirmações da mesma intenção se enfileiram na trava da linha — a segunda
 * encontra CONFIRMADA e não grava de novo.
 *
 * <h2>As trancas de novo, na hora de gravar — RN-CNV-017</h2>
 *
 * <p>Nada do momento da proposta autoriza a gravação. O usuário efetivo é resolvido AGORA
 * ({@code emNomeDe}), precisa ser o mesmo que propôs, precisa ter as permissões que a ferramenta
 * declara, e o {@code @PreAuthorize} do caso de uso confere por último. Quem perdeu a permissão entre
 * o resumo e o "SIM" não grava.
 *
 * <p>Recusa sai como exceção e desfaz a transação inteira — inclusive a gravação que tivesse começado.
 * Registrar a recusa é de {@link ConfirmarPeloCodigo}, numa transação própria: registrada aqui dentro,
 * seria desfeita junto (a lição de RN-ARQ-003).
 */
public class EfetivarIntencao {

    private final IntencoesJdbc intencoes;
    private final CatalogoDeFerramentas catalogo;
    private final UsuarioEfetivoApi usuarios;
    private final Relogio relogio;

    public EfetivarIntencao(IntencoesJdbc intencoes, CatalogoDeFerramentas catalogo,
                            UsuarioEfetivoApi usuarios, Relogio relogio) {
        this.intencoes = intencoes;
        this.catalogo = catalogo;
        this.usuarios = usuarios;
        this.relogio = relogio;
    }

    public sealed interface Decisao {

        record Confirmada(String resposta) implements Decisao {
        }

        record JaDecidida(IntencoesJdbc.Status status) implements Decisao {
        }

        record Vencida() implements Decisao {
        }

        record NaoEncontrada() implements Decisao {
        }
    }

    @Transactional
    public Decisao executar(UUID intencaoId, String telefone, UUID usuarioId, UUID execucaoId) {
        var travada = intencoes.travar(intencaoId);
        if (travada.isEmpty() || !travada.get().usuarioEfetivoId().equals(usuarioId)) {
            return new Decisao.NaoEncontrada();
        }
        var intencao = travada.get();
        if (intencao.status() != IntencoesJdbc.Status.PENDENTE) {
            return new Decisao.JaDecidida(intencao.status());
        }
        Instant agora = relogio.agora();
        if (!agora.isBefore(intencao.expiraEm())) {
            intencoes.expirar(intencao.id(), execucaoId, agora);
            return new Decisao.Vencida();
        }

        // Pelo nome guardado, e só se ainda for escrita: executar "o que tiver o mesmo nome" depois
        // de uma troca de versão seria executar outra coisa.
        FerramentaDeEscrita ferramenta = catalogo.porNome(intencao.ferramenta())
                .filter(FerramentaDeEscrita.class::isInstance)
                .map(FerramentaDeEscrita.class::cast)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoConversacional.ACAO_INDISPONIVEL,
                        "esta ação não está mais disponível por aqui."));

        var efetivacao = usuarios.emNomeDe(telefone, usuario -> {
            if (!usuario.usuarioId().equals(intencao.usuarioEfetivoId())) {
                throw new AccessDeniedException("o telefone agora é de outra pessoa");
            }
            if (!CatalogoDeFerramentas.podeUsar(usuario, ferramenta)) {
                throw new AccessDeniedException("sem a permissão que a ferramenta declara");
            }
            return ferramenta.confirmar(intencao.id(), intencao.comando());
        });

        if (intencoes.confirmar(intencao.id(), execucaoId, efetivacao.mensagem(),
                efetivacao.registro(), agora) != 1) {
            throw new IllegalStateException(
                    "a intenção " + intencao.id() + " deixou de estar pendente sob trava");
        }
        return new Decisao.Confirmada(efetivacao.mensagem());
    }
}
