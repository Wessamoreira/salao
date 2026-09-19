package br.com.salao.iam.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * RT-CNV-007 — os nomes de um punhado de usuários, numa consulta.
 *
 * <p>Existe para quem precisa mostrar QUEM sem ganhar acesso ao cadastro: o painel de revisão do
 * agente diz em nome de quem ele agiu. Só o nome, como {@code ClienteApi.nomesDe} — e-mail, telefone e
 * perfil não têm o que fazer numa lista de ações, e o que não é carregado não vaza.
 *
 * <p>O nome de HOJE. A trilha guarda o id, que é a verdade; o nome é só como a tela chama a pessoa.
 */
public interface NomesDeUsuariosApi {

    /** Ids de outro salão, ou que não existem, simplesmente não aparecem no mapa. */
    Map<UUID, String> nomesDe(Collection<UUID> usuarioIds);
}
