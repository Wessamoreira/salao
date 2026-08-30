---
id: RT-IAM-005
titulo: MFA TOTP
modulo: iam
fase: 0
perfil: completo
status: implementado
depende_de: [RT-IAM-002, RT-IAM-003]
permissoes: []
eventos: []
regras: [RN-IAM-010, RN-IAM-011, RN-IAM-012]
atualizado_em: 2026-08-29
---

# RT-IAM-005 — MFA TOTP

## 1. Objetivo

Segundo fator por aplicativo autenticador, com códigos de recuperação — a proteção que
`05-seguranca` promete como obrigatória para `ADMIN` e para quem tem `financeiro:read:all`.

## 2. TOTP implementado aqui, e por quê

A regra de não escrever primitiva criptográfica à mão continua valendo: **o HMAC vem do JDK**. O
que está no projeto é a parte especificada e verificável — contador por janela e truncagem
dinâmica —, e a RFC 6238 publica **vetores de teste oficiais**. `TotpTest` roda esses vetores,
então a corretude é *demonstrada*, não presumida.

O risco real do TOTP nunca esteve no algoritmo, que é trivial. Está na **política de verificação**:
tamanho da janela e reuso de código. Ambos ficam visíveis no código do projeto, e não escondidos
numa dependência.

**SHA-1 é o correto aqui.** A RFC define SHA-1 e é o que todo autenticador implementa. Não é a
fraqueza de colisão que importa — HMAC não depende de resistência a colisão, e o resultado é
truncado para seis dígitos válidos por trinta segundos. Trocar quebraria a compatibilidade com
Google Authenticator e Authy sem ganho prático.

## 3. Inscrever não ativa (RN-IAM-010)

Gerar o segredo apenas o guarda; o MFA só liga depois que a pessoa **prova que consegue gerar um
código**. Ativar na inscrição trancaria para fora quem digitasse o segredo errado no aplicativo, e
o único jeito de voltar seria um administrador.

## 4. Reuso de código é bloqueado (RN-IAM-011)

Um TOTP vale trinta segundos. Sem registrar a janela usada, quem interceptasse o código poderia
reapresentá-lo dentro desse intervalo.

```sql
update mfa_credencial set ultimo_contador = :contador
 where usuario_id = :usuario
   and (ultimo_contador is null or ultimo_contador < :contador)
```

Quem arbitra é o banco, como no refresh e na agenda. Tolerância de ±1 janela (±30s) cobre relógio
dessincronizado e o tempo entre ler e digitar; mais que isso multiplica a chance de acerto por
tentativa sem melhorar a usabilidade.

## 5. O desafio não é uma credencial (RN-IAM-012)

Com MFA ativo, o login **não emite token de acesso**. Emite um desafio de 5 minutos que atesta
apenas que a senha foi conferida.

O desafio é um JWT assinado por nós, e é aí que mora o risco: sem cuidado, ele seria aceito como
credencial em toda a API — **dando acesso a quem só passou pela senha e nunca apresentou o segundo
fator**. Seria o MFA virando teatro.

Duas barreiras, nas duas direções:

| Barreira | Impede |
|---|---|
| A cadeia do recurso protegido recusa todo token com a claim `escopo` | Usar o desafio como access token |
| `concluirLogin` exige `escopo = mfa-pendente` | Usar um access token antigo como desafio, pulando a senha |

**O contador de falhas não é zerado ao emitir o desafio.** Quem tem a senha ainda não provou ser o
dono da conta; zerar daria a quem a descobriu tentativas ilimitadas no segundo fator sem nunca
disparar o bloqueio.

## 6. Segredo cifrado em repouso

`CofreDeCampo` (AES-256-GCM) — a primeira aplicação da cifragem de campo que `05-seguranca` promete
também para a ficha do cliente.

O segredo TOTP **é** o segundo fator: quem o obtém gera códigos válidos para sempre. Um dump
vazado, um backup mal guardado ou um `SELECT` indevido não podem entregá-lo em claro.

**GCM, e não CBC:** é autenticado, então adulterar o texto cifrado faz a decifragem *falhar* em vez
de devolver lixo. Num segredo TOTP, lixo silencioso viraria um MFA que recusa todos os códigos sem
ninguém entender por quê.

A chave vive fora do banco. Guardá-la junto do dado cifrado tornaria o exercício decorativo.

## 7. Códigos de recuperação

Dez códigos, de uso único, hash SHA-256 (mesma lógica do refresh: alta entropia, sem dicionário a
explorar). Exibidos **uma vez**. Emitir novos invalida os antigos.

Minúsculas e sem separador de propósito: quem digita isso está sem o celular e com pressa.

## 8. Desativar exige código

Senão bastaria uma sessão aberta — ou roubada — para remover o segundo fator, e o MFA protegeria
apenas contra quem não tem sessão, que é justamente quem ele menos precisa deter.

## 9. Testes

- [x] `TotpTest` — 9, incluindo os **6 vetores oficiais da RFC 6238**
- [x] `SegundoFatorIT` — 8: inscrever não ativa · login devolve desafio · desafio + código abrem
      sessão · **código não pode ser reapresentado** · **access token não serve de desafio** ·
      recuperação de uso único · desativar exige código · **segredo não fica em claro no banco**

## 10. O que a implementação revelou

**O `ArquiteturaTest` reprovou o controller.** Eu tinha feito o `SegundoFatorController` injetar
`CredenciaisJdbc` para buscar o e-mail que vai no rótulo do autenticador — e `..web..` não pode
depender de `..infra..`. A correção não foi afrouxar a regra: buscar e-mail e nome do salão é
orquestração, e mudou para o caso de uso.

De quebra, o controller deixou de reler a claim do estabelecimento e passou a usar
`TenantContext.obrigatorio()` — o `TenantFilter` já a extraiu, e reler duplicaria a regra em dois
lugares que poderiam divergir.

É a segunda vez nesta fase que um guardrail pega um erro meu antes do teste de comportamento.

## 11. Pendências

- [ ] **Obrigatoriedade para `ADMIN` e `financeiro:read:all`.** O mecanismo existe, a imposição
      não: hoje ativar MFA é escolha do usuário. Exige `/me/capabilities` para o front forçar a
      inscrição (RT-IAM-006). **Enquanto isso, a promessa de `05-seguranca` está só metade
      cumprida** — e é a pendência mais importante desta rotina
- [ ] Administrador resetar o MFA de alguém que perdeu celular e códigos (RT-IAM-007). Hoje esse
      usuário fica sem acesso
- [ ] Rotação da chave de cifragem: hoje uma só, e trocá-la torna ilegível todo segredo já gravado
- [ ] Revogação imediata do access token: o gatilho que registrei em RT-IAM-004 era "MFA para
      ADMIN". O mecanismo chegou, mas a **imposição** não — o gatilho dispara com RT-IAM-006
- [ ] Bloqueio progressivo também nas tentativas de segundo fator: hoje só a senha conta falhas
