#!/bin/sh
# RT-INF-009 — tirar o backup de dentro da máquina.
#
# Sete dias de dump no mesmo disco protegem contra erro humano e não contra perder
# a VM. Este serviço manda a cópia CIFRADA para um bucket compatível com S3 — AWS,
# Cloudflare R2, Backblaze B2, MinIO —, que é o que existe em qualquer lugar onde o
# salão vá hospedar.
#
# Por que um serviço à parte, e não mais um laço no `backup.sh`:
# a imagem do dump é `postgres:18` de propósito (o `pg_dump` precisa ser o da MESMA
# versão do servidor) e ela não tem cliente HTTP nenhum — nem curl, nem wget, nem
# python. Instalar um a cada boot seria uma dependência de rede no caminho do
# backup. Cada imagem faz o que sabe: uma dumpa, a outra fala com bucket.
set -eu

ORIGEM=/backups
DESTINO="${BACKUP_DESTINO:-}"
INTERVALO="${BACKUP_INTERVALO_SEGUNDOS:-3600}"
# A marca NÃO mora junto dos dumps: `/backups` é montado aqui como somente-leitura,
# porque quem apaga dump é o serviço do dump, com a retenção dele. Um volume próprio
# para o estado custa nada e mantém essa fronteira.
MARCA=/estado/ultimo-envio

# ---------------------------------------------------------------------------
# Sem destino, o backup não sai da máquina — e isso não é erro.
#
# É o estado de hoje, por decisão do dono (22/09/2026). O serviço fica de pé e
# diz isso no log a cada volta, em vez de não existir: um serviço ausente não
# aparece no `docker compose ps`, e o que não aparece ninguém lembra de ligar.
# ---------------------------------------------------------------------------
if [ -z "$DESTINO" ]; then
	while true; do
		echo "envio: SEM DESTINO — o backup fica só nesta máquina." \
			"Defina BACKUP_DESTINO (s3://bucket/caminho) para tirá-lo daqui."
		sleep "$INTERVALO"
	done
fi

# ---------------------------------------------------------------------------
# Com destino e sem senha, não sai nada.
#
# O dump tem o telefone, a agenda e a ficha de todas as clientes. Mandá-lo em
# claro para o disco de outra pessoa é entregar isso a quem tiver a credencial do
# bucket. Falhar ao subir é o comportamento certo: o erro aparece no `up`, não na
# madrugada.
# ---------------------------------------------------------------------------
if [ -z "${BACKUP_SENHA_DO_ARQUIVO:-}" ]; then
	echo "envio: RECUSADO — há destino e não há BACKUP_SENHA_DO_ARQUIVO." \
		"O dump não sai desta máquina em claro." >&2
	exit 1
fi

enviar() {
	# `sync` e não `cp`: o que já está lá não sobe de novo, e a conta de quem paga
	# por requisição agradece. `--exact-timestamps` porque o nome do arquivo já
	# carrega a data — tamanho igual com nome igual é o mesmo arquivo.
	aws s3 sync "$ORIGEM" "$DESTINO" \
		--exclude '*' --include 'salao-*.dump.enc' \
		--only-show-errors ${AWS_ENDPOINT_URL:+--endpoint-url "$AWS_ENDPOINT_URL"}
}

while true; do
	if [ -z "$(find "$ORIGEM" -name 'salao-*.dump.enc' -print -quit)" ]; then
		# O dump cifrado ainda não existe: o serviço `backup` acabou de subir, ou a
		# senha chegou depois dele. Não é falha de envio, e marcar como tal deixaria
		# o deploy travado por um arquivo que aparece em segundos.
		echo "envio: ainda não há dump cifrado em $ORIGEM"
	elif enviar; then
		# A marca é o que o healthcheck lê. Sem ela, um envio que quebrasse em
		# silêncio recriaria exatamente o problema que o healthcheck do dump existe
		# para resolver — descobrir no dia do restore.
		touch "$MARCA"
		echo "envio: $DESTINO em dia ($(date -Iseconds))"
	else
		echo "envio: FALHOU para $DESTINO" >&2
	fi
	sleep "$INTERVALO"
done
