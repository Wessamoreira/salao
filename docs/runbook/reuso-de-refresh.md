# Runbook — reuso de refresh token detectado

> Escrito antes de acontecer, ao contrário dos demais runbooks deste projeto. A regra geral aqui
> é escrever o runbook na primeira ocorrência, quando o contexto está fresco — mas resposta a
> incidente de segurança é a exceção: no meio dele ninguém pensa com clareza, e a decisão de
> quanto revogar não pode ser improvisada.

**Alerta:** `ReusoDeRefreshDetectado` · **Métrica:** `auth.refresh.reuso.detectado`

## O que aconteceu

Um refresh token de uso único foi reapresentado depois de já ter sido trocado. Só há uma
explicação: **o token existia em dois lugares**.

O sistema já reagiu sozinho — a família inteira foi revogada e o usuário terá de entrar de novo.
O alerta não é para mandar agir; é para investigar **como** o token saiu.

## Descarte primeiro o falso positivo

A janela de tolerância de 10 segundos cobre reenvio por rede instável. Um reuso **fora** dela é
improvável de ser inocente, mas confira:

```sql
select usuario_id, familia_id, emitido_em, usado_em, revogado_em, ip, user_agent
  from refresh_token
 where familia_id = '<familia>'
 order by emitido_em;
```

Se `usado_em` e a reapresentação estiverem separados por poucos segundos e vierem do **mesmo IP e
user agent**, é cliente repetindo — aumente `app.auth.refresh.tolerancia-de-reenvio` e encerre.

## Se os IPs ou os user agents forem diferentes

Trate como vazamento.

1. **Encerre todas as sessões do usuário**, não só a família afetada:
   `POST /api/v1/auth/logout-all` como o próprio usuário, ou pelo banco:
   ```sql
   update refresh_token set revogado_em = now(), motivo_revogacao = 'incidente'
    where usuario_id = '<usuario>' and revogado_em is null;
   ```
2. **Force troca de senha.** O refresh pode ter vazado junto com a senha.
3. **Lembre da janela do access token:** revogar refresh não invalida o access token já emitido —
   ele vale por até 15 minutos. Considere esse intervalo ao avaliar o que pode ter sido acessado.
4. **Levante o que foi feito** com aquela sessão: `auditoria` filtrada por `usuario_id` e pela
   janela entre `emitido_em` e `revogado_em`.
5. Se houver mais de um usuário afetado no mesmo período, pare de tratar caso a caso — o vazamento
   provavelmente não é do dispositivo, e sim do transporte ou de um log.

## Depois

Registre no `CHANGELOG` e reveja: o cookie estava com `Secure`? Havia log gravando o token? O
`Path` do cookie estava restrito? A resposta costuma estar num desses três.
