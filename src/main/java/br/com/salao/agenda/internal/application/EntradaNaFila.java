package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.internal.infra.FilaDeEsperaJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * RT-AGD-010 — gravar uma espera, pelas duas portas: a entrada comum e o dia da série que não
 * coube.
 *
 * <p>Existe para a tradução do erro ficar num lugar só. Quem garante RN-AGD-028 é a exclusion
 * constraint — duas recepcionistas ao mesmo tempo passariam por qualquer {@code if} —, e o que
 * sobra para o código é dizer em português o que o banco recusou.
 */
final class EntradaNaFila {

    private static final String SOBREPOSICAO = "fila_sem_sobreposicao";

    private EntradaNaFila() {
    }

    static UUID gravar(FilaDeEsperaJdbc fila, UUID estabelecimentoId, EsperaNaFila pedido) {
        try {
            return fila.entrar(estabelecimentoId, pedido, UsuarioAtual.idOuNulo());
        } catch (DataIntegrityViolationException e) {
            String mensagem = e.getMostSpecificCause().getMessage();
            if (mensagem != null && mensagem.contains(SOBREPOSICAO)) {
                // Duas esperas nos mesmos dias seriam duas ligações pela mesma vaga, e a segunda é
                // a que faz o salão parecer desorganizado justamente com quem já está esperando.
                throw new ErroDeDominio(ErrosDaAgenda.JA_ESTA_NA_FILA,
                        "esta cliente já está na fila para este serviço nestes dias");
            }
            throw e;
        }
    }
}
