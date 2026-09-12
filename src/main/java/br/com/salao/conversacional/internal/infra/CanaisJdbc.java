package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.api.CanalDoWhatsApp;
import br.com.salao.conversacional.api.ErrosDoConversacional;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-001 — o canal do WhatsApp de cada salão.
 *
 * <h2>Duas conexões, e o motivo de cada uma</h2>
 *
 * <p>O <strong>roteamento</strong> ({@link #estabelecimentoAtivoDe}) roda sem tenant: é ele que
 * descobre o tenant. Vai pela role de manutenção, com {@code select} e mais nada — mesmo padrão do
 * despachante de mensagens (V30).
 *
 * <p>O <strong>cadastro</strong> roda dentro do escopo do salão, pela conexão da aplicação, e a
 * RLS garante que ninguém cadastre canal para outro estabelecimento.
 *
 * <h2>Por que o roteamento NÃO é cacheado</h2>
 *
 * <p>Seria tentador: a tabela é minúscula e quase nunca muda, e o pool de manutenção tem duas
 * conexões. Foi recusado.
 *
 * <p>O cache do projeto expira por escrita em 30 minutos (RT-INF-007), e esse prazo é o teto do
 * estrago quando o ouvinte de invalidação cai. Aqui o estrago seria mensagem de cliente caindo na
 * caixa do salão errado por meia hora — sobre a <em>única</em> decisão que o webhook toma.
 *
 * <p>E a pressão sobre o pool é teórica: nenhuma requisição chega até esta consulta sem HMAC
 * válido (RN-CNV-001). Quem não tem o segredo é recusado antes de tocar no banco, então o tráfego
 * que chega aqui é o tráfego real do salão — dezenas de mensagens por dia, não por segundo.
 */
public class CanaisJdbc {

    private final JdbcClient jdbc;
    private final ConexaoDeManutencao manutencao;

    public CanaisJdbc(JdbcClient jdbc, ConexaoDeManutencao manutencao) {
        this.jdbc = jdbc;
        this.manutencao = manutencao;
    }

    /**
     * De quem é este número — a pergunta que o webhook faz antes de qualquer outra coisa.
     *
     * <p>Filtra por {@code ativo}: canal desligado devolve vazio, e o webhook descarta a mensagem
     * contando o descarte. É o que torna "desliguei o WhatsApp deste salão" uma operação com
     * efeito observável, em vez de apagar a linha e passar a perder mensagem em silêncio.
     */
    public Optional<UUID> estabelecimentoAtivoDe(String phoneNumberId) {
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            return Optional.empty();
        }
        return manutencao.jdbc().sql("""
                select estabelecimento_id from canal_whatsapp
                 where phone_number_id = :numero and ativo
                """)
                .param("numero", phoneNumberId)
                .query(UUID.class)
                .optional();
    }

    /** O canal do salão em escopo, se houver. */
    @Transactional(readOnly = true)
    public Optional<CanalDoWhatsApp> doSalao() {
        return jdbc.sql("""
                select phone_number_id, numero_exibicao, ativo from canal_whatsapp
                 order by criado_em
                 limit 1
                """)
                .query((rs, linha) -> new CanalDoWhatsApp(
                        rs.getString("phone_number_id"),
                        rs.getString("numero_exibicao"),
                        rs.getBoolean("ativo")))
                .optional();
    }

    /**
     * Cadastra ou atualiza o canal do salão em escopo.
     *
     * <p>{@code update} antes de {@code insert}, e não {@code on conflict do update}: com RLS
     * ligada, o {@code ON CONFLICT DO UPDATE} sobre uma linha que a política não deixa ver tem
     * comportamento que depende de detalhe de versão do Postgres. Separado, o resultado é óbvio —
     * o {@code update} só alcança linha do próprio tenant, e o {@code insert} de um número que
     * pertence a outro salão bate no índice único.
     *
     * <p>O {@code catch} não faz consulta nenhuma: depois de violar constraint o Postgres
     * <strong>aborta a transação</strong>, e qualquer comando seguinte falharia com 25P02 —
     * lição de RT-EST-009. Quem já tem o número não é dito, e é melhor assim: seria contar a um
     * salão sobre o cadastro de outro.
     */
    @Transactional
    public void registrar(UUID estabelecimentoId, String phoneNumberId, String numeroExibicao,
                          boolean ativo) {
        int atualizadas = jdbc.sql("""
                update canal_whatsapp
                   set numero_exibicao = :exibicao, ativo = :ativo, atualizado_em = now()
                 where phone_number_id = :numero
                """)
                .param("numero", phoneNumberId)
                .param("exibicao", numeroExibicao)
                .param("ativo", ativo)
                .update();
        if (atualizadas > 0) {
            return;
        }
        try {
            jdbc.sql("""
                    insert into canal_whatsapp
                        (phone_number_id, estabelecimento_id, numero_exibicao, ativo)
                    values (:numero, :estabelecimento, :exibicao, :ativo)
                    """)
                    .param("numero", phoneNumberId)
                    .param("estabelecimento", estabelecimentoId)
                    .param("exibicao", numeroExibicao)
                    .param("ativo", ativo)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ErroDeDominio(ErrosDoConversacional.NUMERO_JA_EM_USO,
                    "o número " + phoneNumberId + " já está ligado a outro estabelecimento");
        }
    }
}
