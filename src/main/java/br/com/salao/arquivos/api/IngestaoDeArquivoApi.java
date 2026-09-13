package br.com.salao.arquivos.api;

import java.util.Optional;
import java.util.UUID;

/**
 * RT-CNV-003 — guardar bytes que o PRÓPRIO SERVIDOR obteve.
 *
 * <p>O upload de RT-ARQ-001 existe para bytes que vêm do navegador, em dois passos, porque a
 * aplicação não os vê passar. Aqui é o contrário: o áudio do WhatsApp foi baixado pela aplicação, os
 * bytes estão em memória, e dois passos seriam cerimônia.
 *
 * <p>O que NÃO muda é o que o módulo existe para garantir: o tipo sai dos bytes, tem de caber no
 * dono, e a recusa vira registro — {@link #guardar} devolve o arquivo {@code RECUSADO} com o motivo,
 * e não lança (RN-ARQ-003). Nenhum outro módulo decide o que um arquivo é.
 *
 * <p><strong>Sem permissão, e sem porta HTTP.</strong> Quem chama é um job do sistema, sem usuário;
 * o teste de arquitetura reprova qualquer controller que dependa desta interface.
 */
public interface IngestaoDeArquivoApi {

    /**
     * Valida e guarda. Exige tenant em escopo.
     *
     * @return o arquivo {@code VALIDADO}, ou {@code RECUSADO} com o motivo — nunca lança por recusa
     */
    ArquivoResumo guardar(String donoTipo, UUID donoId, String nomeOriginal, byte[] conteudo);

    /** Os bytes de um arquivo VALIDADO do tenant em escopo. Aguardando ou recusado não tem conteúdo. */
    Optional<ConteudoDeArquivo> conteudo(UUID arquivoId);
}
