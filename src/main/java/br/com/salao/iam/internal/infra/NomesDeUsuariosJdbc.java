package br.com.salao.iam.internal.infra;

import br.com.salao.iam.api.NomesDeUsuariosApi;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** RT-CNV-007 — nomes por id, dentro do escopo do salão (a RLS filtra). */
public class NomesDeUsuariosJdbc implements NomesDeUsuariosApi {

    private final JdbcClient jdbc;

    public NomesDeUsuariosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> nomesDe(Collection<UUID> usuarioIds) {
        if (usuarioIds == null || usuarioIds.isEmpty()) {
            return Map.of();
        }
        var nomes = new LinkedHashMap<UUID, String>();
        jdbc.sql("select id, nome from usuario where id in (:ids)")
                .param("ids", List.copyOf(usuarioIds))
                .query((rs, n) -> nomes.put(rs.getObject("id", UUID.class), rs.getString("nome")))
                .list();
        return nomes;
    }
}
