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

dump() {
	local arquivo="$DESTINO/salao-$(date +%Y%m%d-%H%M).dump"
	# Para o arquivo parcial não parecer um backup bom: só vira .dump no fim.
	if pg_dump -Fc --file="$arquivo.parcial"; then
		mv "$arquivo.parcial" "$arquivo"
		echo "backup: gravado $arquivo ($(du -h "$arquivo" | cut -f1))"
	else
		rm -f "$arquivo.parcial"
		echo "backup: FALHOU o dump de $(date -Iseconds)" >&2
		return 1
	fi
}

limpar() {
	# Só depois de um dump bom: apagar o antigo primeiro deixaria a máquina sem
	# nenhum backup no dia em que o dump falhasse.
	find "$DESTINO" -name 'salao-*.dump' -mtime "+$RETENCAO_DIAS" -print -delete
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
