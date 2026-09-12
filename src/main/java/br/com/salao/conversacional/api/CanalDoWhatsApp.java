package br.com.salao.conversacional.api;

/**
 * RT-CNV-001 — o canal do salão, como a tela o vê.
 *
 * <p>Mora no {@code api} e não dentro do repositório: o controller precisa dele, e {@code web}
 * depender de {@code infra} pula a camada de aplicação — o build reprova, e está certo. Foi assim
 * que este record nasceu.
 *
 * @param phoneNumberId  o id do número na Cloud API, que é a chave de roteamento do webhook
 * @param numeroExibicao o número em E.164, só para a tela dizer qual é
 * @param ativo          {@code false} é canal desligado: as mensagens dele são descartadas, e o
 *                       descarte aparece na métrica em vez de acontecer em silêncio
 */
public record CanalDoWhatsApp(String phoneNumberId, String numeroExibicao, boolean ativo) {
}
