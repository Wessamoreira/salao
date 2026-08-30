---
id: RT-IAM-002
titulo: Login com Argon2id e bloqueio progressivo
modulo: iam
fase: 0
perfil: completo
status: implementado
depende_de: [RT-IAM-001, RT-INF-003]
permissoes: []
eventos: []
regras: [RN-IAM-004, RN-IAM-005, RN-IAM-006]
atualizado_em: 2026-08-29
---

# RT-IAM-002 — Login com Argon2id e bloqueio progressivo

## 1. Objetivo

Trocar e-mail e senha por um token que carrega o estabelecimento — e que passa a ser a fonte do
tenant de toda requisição.

## 2. O login é o único fluxo que começa sem tenant

A pessoa digita e-mail e senha; o estabelecimento é **consequência** do e-mail, não entrada. Isso
faz da busca da credencial o único alcance cross-tenant do fluxo: uma consulta estreita pela
conexão de plataforma (ADR-0010), a mesma usada no provisionamento.

A partir do instante em que o tenant é conhecido, **tudo o mais corre dentro do escopo dele** —
conferir senha, contar falhas, aplicar bloqueio, registrar acesso. O alcance cross-tenant fica
reduzido a uma consulta, e ela devolve só o que a decisão exige: nem telefone, nem documento.

## 3. Três decisões contra enumeração de usuários (RN-IAM-006)

| Decisão | Sem ela |
|---|---|
| **Um código de erro só** para senha errada, e-mail inexistente e usuário inativo | A API responde de graça "este e-mail existe aqui?" |
| **Custo de tempo igual**: e-mail inexistente confere a senha contra um hash descartável | Resposta em milissegundos contra centenas — diferença mensurável de fora |
| **Bloqueio recusa antes de conferir a senha** | Dava para descobrir a senha correta durante o bloqueio, pela diferença de tempo |

## 4. Bloqueio progressivo (RN-IAM-005)

Até 4 falhas não bloqueiam; da 5ª em diante começa em 30s e dobra, com teto de 15 minutos.

Bloqueio fixo tem os dois defeitos ao mesmo tempo: curto demais não atrapalha um ataque
automatizado, longo demais transforma a recepcionista que errou a senha duas vezes num chamado de
suporte no meio do expediente.

**O teto não é detalhe.** Sem ele, um ataque contra o e-mail de alguém deixaria essa pessoa
permanentemente fora do sistema — o ataque falharia em entrar e teria sucesso em derrubar.

Contagem **no banco**, não em memória: em memória, duas instâncias contariam metade cada, e subir
uma terceira afrouxaria a proteção.

Senha correta em usuário inativo **não** conta como falha: o usuário não errou nada, e contar
bloquearia uma conta que já está inacessível.

## 5. Argon2id, e a armadilha do BouncyCastle

BCrypt resiste a GPU mas não a hardware com muita memória; Argon2id foi desenhado para ser caro em
memória, que é o recurso difícil de paralelizar barato.

`Argon2PasswordEncoder` **delega ao BouncyCastle** e não funciona sem ele no classpath — a falha
aparece só em runtime, na primeira tentativa de hash. A armadilha estava anotada em
`05-seguranca` desde o começo do projeto, e a dependência foi declarada com esse aviso escrito ao
lado.

## 6. O token, e o fim do resolvedor por cabeçalho

Access token de **15 minutos**. Ele não é revogável: emitido, vale até expirar. Se um usuário for
desativado ou trocar de perfil, o token antigo segue aceito até o fim da validade — e é essa
janela que a duração curta limita. Aumentar para "reduzir logins" trocaria conforto por uma janela
maior de acesso indevido; quem resolve o conforto é o refresh (RT-IAM-003).

A claim `estabelecimentoId` é o que permite ao `ResolvedorDeTenantPorJwt` descobrir o tenant sem
ida ao banco a cada requisição. **Isso encerra o uso do `ResolvedorDeTenantPorCabecalho` em
produção** — ele sempre foi restrito a dev e test, justamente porque um cabeçalho escolhido pelo
cliente decidindo o estabelecimento é troca de identidade por HTTP.

O `jti` existe para permitir revogar um token específico quando houver lista de revogação
(RT-IAM-004).

**A ordem dos filtros mudou por causa disso.** O `TenantFilter` rodava antes de tudo; agora roda
**depois** da cadeia do Spring Security, porque o resolvedor lê o `SecurityContext`, que só existe
depois da autenticação. Antes dela, ele encontraria sempre `null` e toda requisição autenticada
cairia em `TenantNaoDefinidoException`.

## 7. Provisionamento passou a criar o primeiro administrador

Fecha a pendência que RT-IAM-001 deixou explícita: nascia um estabelecimento sem ninguém que
pudesse entrar nele.

Estabelecimento e admin são criados numa **única instrução SQL** (`with novo as (insert ...)
insert into usuario select ...`). Um comando só é atômico sem precisar de gerenciador de transação
na conexão de plataforma — que, aliás, não poderia usar o da aplicação, porque ele exige um tenant
que ainda não existe. Duas instruções separadas abririam uma janela em que o estabelecimento
existe e está inacessível.

Senha do admin: mínimo de **12 caracteres, sem exigência de composição**. Comprimento vale mais
que símbolos, e regra de composição empurra a pessoa para `Salao@2026`, que é pior.

## 8. Segurança da API

`anyRequest().authenticated()`: **endpoint novo nasce protegido**, e abrir exige ato deliberado. O
contrário — abrir por padrão e lembrar de fechar — falha na primeira distração.

CSRF desabilitado enquanto não há cookie: sem sessão e sem cookie, não há o que um site terceiro
possa forjar, porque ele não consegue definir o cabeçalho `Authorization`. **Isso muda em
RT-IAM-003**, quando o refresh passar a viver num cookie.

O segredo do JWT **não tem valor padrão**: a aplicação não sobe sem ele. Um padrão em código vira
o segredo de produção de alguém. Chave menor que 32 bytes falha na subida — HS256 com chave curta
é trivialmente quebrável.

## 9. Testes

- [x] `AutenticacaoIT` — 8: admin do provisionamento entra · token carrega o estabelecimento ·
      e-mail case-insensitive · não permite enumerar · bloqueia após falhas · sucesso zera falhas ·
      inativo não entra · logins de dois salões não se misturam
- [x] `PoliticaDeBloqueioTest` — 4, domínio puro, sem esperar o relógio
- [x] `ObservabilidadeIT.erro_traz_codigo_e_traceid` — primeiro endpoint exercitado por HTTP real,
      confirmando 401 e `ER-IAM-CREDENCIAIS_INVALIDAS` no formato Problem Details

## 10. O que a implementação revelou

**Boot 4: biblioteca no classpath não significa fiação — terceira vez.** Já havia acontecido com o
Flyway (`spring-boot-flyway`) e com o cache. Agora com o tracing: investiguei o `traceId` a fundo
com o primeiro controller no ar e o contexto sobe com um `noopTracer`. `spring-boot-opentelemetry`
entrega SDK e *logging*, **nenhuma autoconfiguração de tracing**, e os artefatos que fariam a ponte
não existem para 4.1.1. Removi as dependências: produziam a aparência de tracing sem tracing.
Detalhes completos em RT-INF-008, seção 10.

**Uma falha de teste não reproduzida.** Numa das execuções de verificação, o build falhou uma vez;
os relatórios já haviam sido sobrescritos quando fui investigar, e **não consegui identificar
qual teste foi**. Quatro execuções seguintes passaram. Fica registrado como risco de intermitência
— o CI já publica os relatórios como artefato, então a próxima ocorrência será rastreável.

## 11. Pendências

- [ ] **Bloqueio por IP.** Hoje só por usuário. Credential stuffing distribui e-mails diferentes
      pelo mesmo IP e não encosta no bloqueio atual. Precisa de contagem por IP com janela
- [ ] Parâmetros do bloqueio por estabelecimento (hoje constantes no domínio)
- [ ] Refresh rotativo com detecção de reuso (RT-IAM-003) — sem ele, a sessão morre em 15 min
- [ ] MFA TOTP para `ADMIN` (RT-IAM-005), prometido em `05-seguranca`
- [ ] Rotação do segredo do JWT: hoje um valor só, e trocá-lo invalida todos os tokens de uma vez
- [ ] Auditar login e falha de login em `auditoria` (RT-IAM-008)
