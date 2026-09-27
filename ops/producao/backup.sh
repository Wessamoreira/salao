#!/bin/bash
# RT-INF-009 — dump diário do banco, guardado sete dias na própria máquina.
#
# Roda dentro do contêiner `backup` do compose de produção, que é a imagem oficial
# do Postgres: o pg_dump é o da MESMA versão do servidor. Dump feito por cliente
# mais velho falha na restauração, e falha tarde.
#
# Formato `custom` (-Fc), e não SQL de texto: comprime, e o pg_restore consegue
# restaurar uma tabela só — que é o caso real ("alguém apagou a agenda de ontem").
set -euo pipefail

DESTINO=/backups
RETENCAO_DIAS="${RETENCAO_DIAS:-7}"
# A senha que cifra a cópia que SAI da máquina. Vazia: nada sai cifrado, e o serviço
# `backup-remoto` recusa enviar — dump em claro no bucket de outra pessoa é a agenda
# inteira do salão legível por quem tiver a credencial.
SENHA_DO_ARQUIVO="${BACKUP_SENHA_DO_ARQUIVO:-}"

dump() {
	local arquivo="$DESTINO/salao-$(date +%Y%m%d-%H%M).dump"
	# Para o arquivo parcial não parecer um backup bom: só vira .dump no fim.
	if pg_dump -Fc --file="$arquivo.parcial"; then
		mv "$arquivo.parcial" "$arquivo"
		echo "backup: gravado $arquivo ($(du -h "$arquivo" | cut -f1))"
		cifrar "$arquivo"
	else
		rm -f "$arquivo.parcial"
		echo "backup: FALHOU o dump de $(date -Iseconds)" >&2
		return 1
	fi
}

# ---------------------------------------------------------------------------
# A cópia que sai da máquina vai cifrada; a que fica, não.
#
# São dois riscos diferentes. A cópia local protege contra erro humano — "alguém
# apagou a agenda de ontem" — e vive dentro da VM, com o mesmo acesso do banco;
# cifrá-la só acrescentaria uma senha a perder no dia em que ela for a única que
# resta. A cópia remota vive no disco de outra pessoa: quem tiver a credencial do
# bucket lê a agenda, o telefone e a ficha de todas as clientes (LGPD).
#
# `openssl enc` com `-pbkdf2`, e não a derivação antiga: sem ele a chave sai de um
# MD5 de uma passada, e a senha do arquivo vira quase decorativa.
# ---------------------------------------------------------------------------
cifrar() {
	local arquivo="$1"
	if [ -z "$SENHA_DO_ARQUIVO" ]; then
		return 0
	fi
	if printf '%s' "$SENHA_DO_ARQUIVO" \
		| openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt \
			-in "$arquivo" -out "$arquivo.enc.parcial" -pass stdin; then
		mv "$arquivo.enc.parcial" "$arquivo.enc"
		echo "backup: cifrado $arquivo.enc"
	else
		rm -f "$arquivo.enc.parcial"
		# Sem cifra não há envio, e é melhor gritar aqui do que descobrir no dia do
		# restore que o bucket tem sete dias de arquivo que ninguém abre.
		echo "backup: FALHOU ao cifrar $arquivo" >&2
		return 1
	fi
}

limpar() {
	# Só depois de um dump bom: apagar o antigo primeiro deixaria a máquina sem
	# nenhum backup no dia em que o dump falhasse.
	find "$DESTINO" -name 'salao-*.dump' -mtime "+$RETENCAO_DIAS" -print -delete
	find "$DESTINO" -name 'salao-*.dump.enc' -mtime "+$RETENCAO_DIAS" -print -delete
	find "$DESTINO" -name '*.parcial' -mtime +1 -print -delete
}

# Um dump ao subir: assim o primeiro backup não espera um dia, e um erro de
# credencial aparece no `up`, não na madrugada seguinte.
while true; do
	if dump; then
		limpar
	fi
	sleep 86400
done
