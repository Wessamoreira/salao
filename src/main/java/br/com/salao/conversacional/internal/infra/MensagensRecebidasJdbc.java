package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.api.TipoDeMensagem;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** RT-CNV-001 — a caixa de entrada do salão. */
public class MensagensRecebidasJdbc {

    private final JdbcClient jdbc;

    public MensagensRecebidasJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Grava a mensagem, ou não faz nada se ela já estava lá.
     *
     * <p><strong>O vazio é resposta, não erro.</strong> {@code on conflict do nothing} devolve zero
     * linhas quando o {@code provedor_id} já existe, e é assim que o reenvio da Meta se torna
     * inofensivo: quem chama não publica evento e não processa de novo (RN-CNV-002).
     *
     * <p>Poderia ser "consulta antes, insere depois". Não poderia: entre a consulta e a inserção
     * cabe o reenvio, e duas requisições concorrentes com o mesmo {@code wamid} passariam as duas.
     * Quem arbitra é o índice único — a aplicação valida por educação, o banco garante.
     *
     * @param botaoId RT-CNV-006 — o id do botão tocado, só em {@code BOTAO}
     * @return o id da mensagem gravada, ou vazio quando ela já existia
     */
    @Transactional
    public Optional<UUID> inserir(UUID estabelecimentoId, String provedorId, String deTelefone,
                                  TipoDeMensagem tipo, String texto, String midiaId,
                                  String midiaMime, String botaoId, Instant enviadaEm,
                                  Instant recebidaEm) {
        return jdbc.sql("""
                insert into mensagem_recebida
                    (estabelecimento_id, canal, provedor_id, de_telefone, tipo, texto,
                     midia_id, midia_mime, botao_id, enviada_em, recebida_em)
                values (:estabelecimento, 'WHATSAPP', :provedor, :de, :tipo, :texto,
                        :midiaId, :midiaMime, :botaoId, :enviada, :recebida)
                on conflict (estabelecimento_id, provedor_id) do nothing
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("provedor", provedorId)
                .param("de", deTelefone)
                .param("tipo", tipo.name())
                .param("texto", texto)
                .param("midiaId", midiaId)
                .param("midiaMime", midiaMime)
                .param("botaoId", botaoId)
                .param("enviada", java.sql.Timestamp.from(enviadaEm))
                .param("recebida", java.sql.Timestamp.from(recebidaEm))
                .query(UUID.class)
                .optional();
    }

    /** RT-CNV-003 — o bilhete da mídia de uma mensagem, se ela tiver um. */
    @Transactional(readOnly = true)
    public Optional<MidiaDaMensagem> midiaDe(UUID mensagemId) {
        return jdbc.sql("""
                select midia_id, midia_mime from mensagem_recebida
                 where id = :id and midia_id is not null
                """)
                .param("id", mensagemId)
                .query((rs, n) -> new MidiaDaMensagem(rs.getString("midia_id"),
                        rs.getString("midia_mime")))
                .optional();
    }

    public record MidiaDaMensagem(String midiaId, String mimeDeclarado) {
    }

    /** RT-CNV-004 — quem mandou, e o texto ou o botão, conforme o tipo. */
    @Transactional(readOnly = true)
    public Optional<Remetente> remetente(UUID mensagemId) {
        return jdbc.sql("select de_telefone, texto, botao_id from mensagem_recebida where id = :id")
                .param("id", mensagemId)
                .query((rs, n) -> new Remetente(rs.getString("de_telefone"), rs.getString("texto"),
                        rs.getString("botao_id")))
                .optional();
    }

    public record Remetente(String telefone, String texto, String botaoId) {
    }
}
