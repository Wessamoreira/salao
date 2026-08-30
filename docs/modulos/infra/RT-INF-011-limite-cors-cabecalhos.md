---
id: RT-INF-011
titulo: Rate limit por IP, CORS e cabeçalhos de segurança
modulo: infra
fase: 0
perfil: completo
status: implementado
depende_de: [RT-IAM-002, RT-INF-010]
permissoes: []
eventos: []
regras: [RN-INF-012]
atualizado_em: 2026-08-30
---

# RT-INF-011 — Rate limit por IP, CORS e cabeçalhos

Nasceu da auditoria de segurança de 29/08/2026 (`docs/15-checklist-de-seguranca.md`), itens 4, 7
e 8. Não é melhoria oportunista: são lacunas entre o que `05-seguranca` promete e o que existia.

## 1. O ataque que o bloqueio por usuário não pega

O bloqueio progressivo de RT-IAM-002 conta falhas **por conta**. Um atacante que tente uma senha
comum contra mil e-mails diferentes acumula **uma** falha em cada conta e nunca dispara bloqueio
nenhum.

É o *password spraying*, e ele é invisível para qualquer defesa que olhe só a conta. Só um limite
por **origem** o detém.

## 2. RN-INF-012 — o cabeçalho do cliente só vale atrás de proxy declarado

**A decisão que separa um limite real de um limite decorativo.**

Atrás de um proxy, o IP verdadeiro chega em `X-Forwarded-For` e `getRemoteAddr()` devolve o IP do
proxy — todos os clientes viram um só, e o limite passa a limitar o mundo inteiro junto.

A correção óbvia — ler o cabeçalho — é **pior** feita sem cuidado: **o cabeçalho é enviado pelo
cliente**. Quem quiser burlar manda um `X-Forwarded-For` diferente a cada requisição e ganha
buckets infinitos. O limite deixa de existir **sem parar de parecer que funciona**, que é o pior
modo de falha possível numa defesa.

Por isso o cabeçalho só é considerado com `app.rede.atras-de-proxy` ligado — e ligá-lo é uma
afirmação sobre a topologia: *nada alcança esta aplicação sem passar pelo proxy*. Se a porta da
aplicação estiver exposta ao lado, ligar isso abre exatamente o buraco que se queria fechar.

**O padrão é desligado.**

## 3. Duas faixas, porque os riscos são diferentes

| Faixa | Limite | Por quê |
|---|---|---|
| `/api/v1/auth/**` | 12/min por IP | Quem entra no salão erra a senha duas ou três vezes. Vinte é ataque |
| Demais `/api/**` | 300/min por IP | Abrir a agenda dispara várias chamadas de uma vez; apertar aqui transformaria uso normal em erro |

Baldes separados: sem isso, quem errou a senha uma vez ficaria sem conseguir abrir a agenda.

## 4. Contagem local, e a consequência assumida

Os baldes vivem em memória, por instância. Com N instâncias, o limite efetivo é **N vezes maior** —
um atacante distribuído passa mais do que o número diz.

É o gatilho de Redis já registrado em [ADR-0004](../../adr/0004-cache-caffeine-notify.md) ("rate
limit distribuído preciso"). Até lá o número é um **teto aproximado, não uma garantia** — e isso é
preferível a um teto exato que custa um contêiner novo numa VM pequena.

O cache tem teto de tamanho e expiração: um mapa sem limite indexado por IP é, ele próprio, um
caminho para esgotar memória — o ataque que o limitador deveria conter.

## 5. A recusa é acionável

429 com `Retry-After` e o código `ER-INF-LIMITE_DE_REQUISICOES`. Sem `Retry-After`, o cliente só
pode adivinhar quando voltar — e adivinhar geralmente significa tentar de novo imediatamente.

**O IP não vai para o log.** É dado pessoal sob a LGPD, e a contagem já está na métrica
`rede.limite.recusas`.

## 6. CORS explícito

Lista de origens por configuração, **nunca curinga**, e vazia por padrão. Com credenciais
habilitadas o navegador recusaria `*` de qualquer forma, mas a razão de fundo é outra: a lista diz
quais front-ends existem, e é curta e revisável. Um curinga não é nem uma coisa nem outra.

`allowCredentials` é necessário porque o refresh viaja em cookie.

## 7. Cabeçalhos

HSTS com subdomínios, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: strict-origin-when-cross-origin`, e CSP `default-src 'none'`.

Vários já são padrão do Spring Security. Estão explícitos porque **um padrão que ninguém escreveu
é um padrão que ninguém revisa** — e porque a próxima pessoa precisa saber que foram considerados.

A CSP daqui é `'none'` porque esta aplicação serve JSON e nada mais. **A CSP do front é outra**, e
pertence ao servidor que entrega os arquivos estáticos — ele nunca atravessa esta cadeia.

## 8. Testes

- [x] `EnderecoDoClienteTest` — 3: **sem proxy declarado o cabeçalho é ignorado** · atrás de proxy
      usa o primeiro da cadeia · ausente cai na conexão
- [x] `LimiteDeTaxaWebIT` — 5: tentativas repetidas são barradas · a recusa traz `Retry-After` e o
      código · as duas faixas são independentes · CORS só para a origem declarada · cabeçalhos
      presentes

## 9. O que a implementação revelou

**O limitador quebrou os testes de autenticação existentes** — eles fazem muito mais chamadas
seguidas de `/auth/**` do que uma pessoa faria, e passaram a bater no 429.

Era o limitador funcionando. A correção foi declarar o limite alto **naqueles testes**, não
afrouxá-lo no produto: cada teste sobre um assunto só, e o limite tem o seu próprio.

**`CorsConfigurationSource` é ambíguo por tipo** — o `mvcHandlerMappingIntrospector` do Spring MVC
também implementa a interface. O Spring Security resolve pelo **nome do bean**
(`corsConfigurationSource`), então `Customizer.withDefaults()` funciona onde a injeção por tipo
falha.

## 10. Pendências

- [ ] Limite distribuído, quando houver mais de uma instância — gatilho de Redis do ADR-0004
- [ ] Limite por **usuário autenticado**, além de por IP: um escritório inteiro atrás de um NAT
      compartilha o IP, e hoje compartilha o balde
- [ ] CSP do front, no servidor de estáticos — não pertence a esta cadeia
- [ ] `app.rede.atras-de-proxy` precisa entrar no runbook de deploy junto com a regra de firewall
      que o justifica; ligado sem ela, piora a segurança
