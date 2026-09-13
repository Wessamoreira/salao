package br.com.salao.conversacional.internal.infra;

import br.com.salao.arquivos.api.TipoDeArquivo;
import br.com.salao.conversacional.internal.application.Transcritor;

/**
 * RT-CNV-003 — o transcritor de quando nenhum provedor foi escolhido.
 *
 * <p>Honesto sobre o que é, como {@code MensageiroDeRegistro}: não finge transcrever, não devolve
 * texto vazio como se o áudio fosse mudo. {@link #configurado()} é falso, e o processador deixa as
 * transcrições esperando em {@code BAIXADA} — com o áudio já guardado, para que a escolha do provedor
 * alcance o que chegou antes dela.
 *
 * <p>Um provedor real entra como bean {@code @Primary} que implementa {@link Transcritor}.
 */
public class TranscritorNaoConfigurado implements Transcritor {

    @Override
    public boolean configurado() {
        return false;
    }

    @Override
    public TextoTranscrito transcrever(byte[] audio, TipoDeArquivo tipo) {
        throw new IllegalStateException(
                "nenhum provedor de transcrição configurado; o processador não deveria ter chamado");
    }
}
