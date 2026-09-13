package br.com.salao.arquivos.internal.domain;

import br.com.salao.arquivos.api.TipoDeArquivo;
import java.util.EnumSet;
import java.util.Set;

/**
 * RT-CNV-003 — o tipo tem de caber no dono.
 *
 * <p>Antes desta rotina a lista branca era uma só, porque todo dono era de anexo. Com áudio entrando
 * pelo WhatsApp, uma lista só abriria o upload de <em>foto de cliente</em> para OGG: os magic bytes
 * reconheceriam um áudio legítimo, e ele passaria como anexo de uma ficha que a tela desenha como
 * imagem.
 *
 * <p>Por isso a pergunta deixou de ser "este formato é aceito?" e virou "este formato é aceito PARA
 * ESTE DONO?". O banco faz a mesma pergunta ({@code arquivo_tipo_coerente_com_o_dono}, V52).
 */
public final class TiposPorDono {

    private static final Set<TipoDeArquivo> ANEXOS = EnumSet.of(
            TipoDeArquivo.JPEG, TipoDeArquivo.PNG, TipoDeArquivo.HEIC, TipoDeArquivo.WEBP,
            TipoDeArquivo.PDF);

    private static final Set<TipoDeArquivo> AUDIOS = EnumSet.of(
            TipoDeArquivo.OGG, TipoDeArquivo.MP3, TipoDeArquivo.M4A, TipoDeArquivo.AMR);

    private TiposPorDono() {
    }

    /** Dono desconhecido não aceita nada: falha fechada. */
    public static boolean aceita(String donoTipo, TipoDeArquivo tipo) {
        if (donoTipo == null || tipo == null) {
            return false;
        }
        return switch (donoTipo) {
            case "CLIENTE", "PROFISSIONAL" -> ANEXOS.contains(tipo);
            case "MENSAGEM" -> AUDIOS.contains(tipo);
            default -> false;
        };
    }
}
